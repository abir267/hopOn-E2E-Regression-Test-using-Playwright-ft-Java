package utils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin adb wrapper for driving a REAL phone - used for the driver Android app, which is Jetpack Compose
 * (no resource-ids), so everything is found by on-screen text / content-description from a
 * {@code uiautomator dump}. Deliberately not Appium: an Appium findElement hung against this app
 * (CONFIRMED), while a plain {@code uiautomator dump} over adb is reliable, just slow (~1s), so
 * callers poll it one dump at a time (never overlapping).
 *
 * Device selection: {@code android.device.name} in TestData.properties if set, otherwise the single
 * attached device. An {@code unauthorized} device is reported with the fix (accept the "Allow USB
 * debugging?" prompt on the phone) instead of a bare adb error.
 */
public final class AdbDevice {

    /** One element of the uiautomator hierarchy that carries text or a content-description. */
    public record UiNode(String text, String desc, String resourceId, boolean clickable,
                         int left, int top, int right, int bottom) {
        public int centerX() {
            return (left + right) / 2;
        }

        public int centerY() {
            return (top + bottom) / 2;
        }

        public String label() {
            return !text.isEmpty() ? text : desc;
        }

        public boolean matches(Pattern pattern) {
            return pattern.matcher(text).find() || pattern.matcher(desc).find();
        }
    }

    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HHmmss");
    private static final Path DIAGNOSTICS_DIR = Paths.get("target", "driver-app-diagnostics");

    private final String adb;
    private String serial;

    public AdbDevice() {
        this.adb = resolveAdb();
        this.serial = ConfigReader.androidDeviceName();
    }

    private static String resolveAdb() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exe = windows ? "adb.exe" : "adb";
        for (String envKey : new String[] {"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String sdk = System.getenv(envKey);
            if (sdk != null && !sdk.isBlank()) {
                Path candidate = Paths.get(sdk, "platform-tools", exe);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return exe; // on PATH
    }

    // ---------------------------------------------------------------- process plumbing

    private byte[] runBytes(int timeoutSeconds, String... args) {
        List<String> command = new ArrayList<>();
        command.add(adb);
        if (serial != null && !serial.isBlank()) {
            command.add("-s");
            command.add(serial);
        }
        command.addAll(Arrays.asList(args));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            try {
                return stdout.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
                throw new IllegalStateException("adb " + String.join(" ", args) + " did not finish within "
                        + timeoutSeconds + "s (phone locked / USB dropped?)", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("adb " + String.join(" ", args) + " failed", e);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not run adb (" + adb + "). Install Android platform-tools and put "
                    + "them on PATH, or set ANDROID_HOME.", e);
        }
    }

    private String run(int timeoutSeconds, String... args) {
        return new String(runBytes(timeoutSeconds, args), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- device selection

    /**
     * Fails fast, with the actual fix, unless exactly the intended phone is attached and authorized.
     * Pins {@link #serial} so later calls don't depend on adb's "only one device" default.
     */
    public void requireReady() {
        String[] lines = run(15, "devices").split("\\R");
        List<String> ready = new ArrayList<>();
        List<String> unauthorized = new ArrayList<>();
        List<String> offline = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2 || line.startsWith("List of") || line.startsWith("*")) {
                continue;
            }
            switch (parts[1]) {
                case "device" -> ready.add(parts[0]);
                case "unauthorized" -> unauthorized.add(parts[0]);
                default -> offline.add(parts[0] + " (" + parts[1] + ")");
            }
        }
        if (serial != null && !serial.isBlank()) {
            if (ready.contains(serial)) {
                return;
            }
            throw new IllegalStateException("Configured android.device.name '" + serial + "' is not ready. Ready: "
                    + ready + ", unauthorized: " + unauthorized + ", other: " + offline);
        }
        if (ready.size() == 1) {
            serial = ready.get(0);
            return;
        }
        if (ready.isEmpty()) {
            String hint = unauthorized.isEmpty()
                    ? "No phone attached. Plug it in with USB debugging on, then check `adb devices`."
                    : "Phone " + unauthorized + " is 'unauthorized': unlock it and tap Allow on the \"Allow USB debugging?\" "
                    + "prompt (tick Always allow), then rerun.";
            throw new IllegalStateException(hint + (offline.isEmpty() ? "" : " Other adb entries: " + offline));
        }
        throw new IllegalStateException("More than one phone attached " + ready + " - set android.device.name in "
                + "TestData.properties to the driver phone's serial.");
    }

    public boolean hasPackage(String packageName) {
        return run(15, "shell", "pm", "list", "packages", packageName).contains("package:" + packageName);
    }

    // ---------------------------------------------------------------- input

    public void tap(int x, int y) {
        run(10, "shell", "input", "tap", String.valueOf(x), String.valueOf(y));
    }

    public void tap(UiNode node) {
        tap(node.centerX(), node.centerY());
    }

    public void typeText(String digitsOrWord) {
        // `input text` treats spaces/specials specially; callers only pass a PIN / plain word.
        run(10, "shell", "input", "text", digitsOrWord);
    }

    public void keyEvent(int keyCode) {
        run(10, "shell", "input", "keyevent", String.valueOf(keyCode));
    }

    /**
     * Wakes the screen, dismisses the lock screen and collapses the notification shade. Seen live: a pulled-down
     * shade (7 piled-up driver notifications) covered the whole screen, so the app was running but nothing of it was on
     * screen to read or tap. All three are no-ops when the phone is already awake and clear.
     */
    public void wakeAndClearScreen() {
        run(10, "shell", "input", "keyevent", "224"); // KEYCODE_WAKEUP
        run(10, "shell", "wm", "dismiss-keyguard");
        run(10, "shell", "cmd", "statusbar", "collapse");
    }

    /** One `settings get global <key>` value, trimmed ("" if unset / unreadable). */
    public String globalSetting(String key) {
        return run(10, "shell", "settings", "get", "global", key).trim();
    }

    /** True while the lock screen is up (a secure lock cannot be dismissed over adb - the phone has to be unlocked by hand). */
    public boolean isKeyguardShowing() {
        return run(10, "shell", "dumpsys window | grep isKeyguardShowing").contains("isKeyguardShowing=true");
    }

    public void launchApp(String packageName) {
        run(15, "shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1");
    }

    /** Package that currently has window focus ("" if it cannot be read). */
    public String foregroundPackage() {
        String out = run(10, "shell", "dumpsys window | grep mCurrentFocus");
        Matcher m = Pattern.compile("([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+").matcher(out);
        return m.find() ? m.group(1) : "";
    }

    public int[] screenSize() {
        Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(run(10, "shell", "wm", "size"));
        return m.find() ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : new int[] {720, 1600};
    }

    // ---------------------------------------------------------------- reading the screen

    /**
     * One uiautomator dump, parsed to the nodes that carry text / content-desc. Empty list when the
     * dump could not be read (the app was mid-animation: "null root node") - callers just poll again.
     */
    public List<UiNode> dump() {
        for (int attempt = 0; attempt < 3; attempt++) {
            String xml = attempt < 2
                    ? run(25, "exec-out", "uiautomator", "dump", "--compressed", "/dev/stdout")
                    : readViaSdcard();
            int start = xml.indexOf("<?xml");
            if (start < 0) {
                start = xml.indexOf("<hierarchy");
            }
            int end = xml.indexOf("</hierarchy>");
            if (start >= 0 && end > start) {
                return parse(xml.substring(start, end + "</hierarchy>".length()));
            }
            sleep(250);
        }
        return List.of();
    }

    private String readViaSdcard() {
        run(25, "shell", "uiautomator", "dump", "/sdcard/hopon_ui.xml");
        return run(15, "exec-out", "cat", "/sdcard/hopon_ui.xml");
    }

    private static List<UiNode> parse(String xml) {
        List<UiNode> nodes = new ArrayList<>();
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            NodeList all = document.getElementsByTagName("node");
            for (int i = 0; i < all.getLength(); i++) {
                Element e = (Element) all.item(i);
                String text = e.getAttribute("text");
                String desc = e.getAttribute("content-desc");
                String id = e.getAttribute("resource-id");
                boolean clickable = "true".equals(e.getAttribute("clickable"));
                if (text.isEmpty() && desc.isEmpty() && id.isEmpty() && !clickable) {
                    continue;
                }
                Matcher b = BOUNDS.matcher(e.getAttribute("bounds"));
                if (!b.find()) {
                    continue;
                }
                nodes.add(new UiNode(text, desc, id, clickable,
                        Integer.parseInt(b.group(1)), Integer.parseInt(b.group(2)),
                        Integer.parseInt(b.group(3)), Integer.parseInt(b.group(4))));
            }
        } catch (Exception e) {
            return List.of(); // half-written dump - caller polls again
        }
        return nodes;
    }

    /** First node whose text/desc matches, preferring a clickable one (a Compose button's label sits in or under it). */
    public static Optional<UiNode> find(List<UiNode> screen, Pattern pattern) {
        UiNode firstAny = null;
        for (UiNode n : screen) {
            if (n.matches(pattern)) {
                if (n.clickable()) {
                    return Optional.of(n);
                }
                if (firstAny == null) {
                    firstAny = n;
                }
            }
        }
        return Optional.ofNullable(firstAny);
    }

    /** Polls dump() until something matches or the time is up. */
    public Optional<UiNode> waitFor(Pattern pattern, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            Optional<UiNode> hit = find(dump(), pattern);
            if (hit.isPresent()) {
                return hit;
            }
        } while (System.currentTimeMillis() < deadline);
        return Optional.empty();
    }

    // ---------------------------------------------------------------- logcat

    /** Process id of the running app ("" if it is not running). */
    public String pidOf(String packageName) {
        String out = run(10, "shell", "pidof", packageName).trim();
        return out.isEmpty() ? "" : out.split("\\s+")[0];
    }

    /**
     * Starts `adb logcat` from the phone's current time on (so lines from earlier runs are not replayed), either for one
     * app's process (pid non-empty: everything the app logs) or for just the given tags. The caller reads the process's
     * stdout (UTF-8) and destroys it when done.
     */
    public Process startLogcat(String pid, String... tagSpecs) throws IOException {
        String now = run(10, "shell", "date", "+%m-%d %H:%M:%S.000").trim();
        List<String> command = new ArrayList<>(List.of(adb));
        if (serial != null && !serial.isBlank()) {
            command.add("-s");
            command.add(serial);
        }
        command.addAll(List.of("logcat", "-v", "time", "-T", now));
        if (pid != null && !pid.isEmpty()) {
            command.add("--pid=" + pid);
        } else {
            command.addAll(Arrays.asList(tagSpecs));
            command.add("*:S");
        }
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    // ---------------------------------------------------------------- screen recording

    private static final String DEVICE_RECORDING = "/sdcard/hopon_run.mp4";
    private Process recording;

    /**
     * Starts `screenrecord` on the phone (max 170s - the Android limit is 180s). The accept step is timed in
     * seconds and a text dump takes ~1s, so a film is the only way to see what really happened between two dumps.
     */
    public void startRecording() {
        stopRecordingQuietly();
        List<String> command = new ArrayList<>(List.of(adb));
        if (serial != null && !serial.isBlank()) {
            command.add("-s");
            command.add(serial);
        }
        command.addAll(List.of("shell", "screenrecord", "--time-limit", "170", "--bit-rate", "2000000", DEVICE_RECORDING));
        try {
            recording = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            recording = null; // a missing film must never fail the test itself
        }
    }

    /** Stops the recording and returns the local mp4 path, or null if there is none. */
    public String stopRecordingAndSave(String label) {
        if (recording == null) {
            return null;
        }
        run(10, "shell", "pkill", "-2", "screenrecord"); // SIGINT = let it finish the mp4 properly
        sleep(1500);
        stopRecordingQuietly();
        try {
            Files.createDirectories(DIAGNOSTICS_DIR);
            Path local = DIAGNOSTICS_DIR.resolve(STAMP.format(LocalTime.now()) + "-" + label.replaceAll("[^A-Za-z0-9_-]", "_") + ".mp4");
            Files.write(local, runBytes(120, "exec-out", "cat", DEVICE_RECORDING));
            return local.toAbsolutePath().toString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void stopRecordingQuietly() {
        if (recording != null) {
            recording.destroy();
            recording = null;
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /** Screenshot + full text dump under target/driver-app-diagnostics/, so a failure shows what was really on the phone. */
    public String saveDiagnostics(String label) {
        String base = STAMP.format(LocalTime.now()) + "-" + label.replaceAll("[^A-Za-z0-9_-]", "_");
        try {
            Files.createDirectories(DIAGNOSTICS_DIR);
            Files.write(DIAGNOSTICS_DIR.resolve(base + ".png"), runBytes(20, "exec-out", "screencap", "-p"));
            StringBuilder sb = new StringBuilder("foreground=" + foregroundPackage() + "\n");
            for (UiNode n : dump()) {
                sb.append(String.format("text=%-40s desc=%-30s id=%-40s clickable=%-5s [%d,%d][%d,%d]%n",
                        '"' + n.text() + '"', '"' + n.desc() + '"', n.resourceId(), n.clickable(),
                        n.left(), n.top(), n.right(), n.bottom()));
            }
            Files.writeString(DIAGNOSTICS_DIR.resolve(base + ".txt"), sb.toString());
        } catch (IOException | RuntimeException e) {
            return "(could not save diagnostics: " + e.getMessage() + ")";
        }
        return DIAGNOSTICS_DIR.resolve(base + ".png").toAbsolutePath() + " (+ .txt)";
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
