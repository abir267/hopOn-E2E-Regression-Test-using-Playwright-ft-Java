package utils;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.SkipException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Auto-registered through META-INF/services/org.testng.ITestNGListener, so it is active
 * for every run (mvn test, -Dtest=..., init.TestRunner, IDE). Does two things:
 *   1. prints "[TEST] ..." start/pass/fail/skip lines with the running test name;
 *   2. rewrites every line written to System.out/System.err as
 *      "hh:mm:ss AM  <icon> <line>" in colour (ANSI), keyed off the line's [TAG]:
 *      [TEST] blue/green/red, [CUSTOMER]/[RESTAURANT]/[DRIVER]/[RESULT] green,
 *      [LOGIN]/[WARN] yellow, [FAILED]/[BUG]/[ADMIN] red, [MQTT] magenta. [BUG] lines come
 *      from utils.BugWatcher - console errors, uncaught JS exceptions, and failed
 *      XHR/fetch calls caught live during a test, independent of its assertions.
 * Disable colour with -Dno.color=true (timestamps stay).
 */
public class ConsoleLogListener implements ITestListener, IInvokedMethodListener {

    /**
     * STOP AT THE FIRST FAILURE (user instruction 2026-11-16): once a test has failed, every test that has not started yet is
     * skipped, so the run ends where the problem is instead of carrying on - and nothing is re-run (see RetryAnalyzer).
     * -Dfail.continue=true switches this off and lets the remaining tests run.
     */
    private static volatile boolean runFailed = false;
    private static final String FAIL_FAST_MESSAGE = "Not run: an earlier test failed and the run stops at the first failure";

    /** The most recent "[CUSTOMER] ...", "[RESTAURANT] ...", "[DRIVER-APP] ..." style line - the last flow step before a failure. */
    private static volatile String lastStep = "";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm:ss a");
    private static final boolean COLOR = !Boolean.getBoolean("no.color");
    private static boolean installed = false;

    /** True only when the console can render UTF-8 symbols; otherwise ASCII fallbacks are used. */
    public static final boolean UNICODE = detectUnicode();

    private static boolean detectUnicode() {
        if (Boolean.getBoolean("no.unicode")) {
            return false;
        }
        String enc = System.getProperty("stdout.encoding", System.getProperty("sun.stdout.encoding",
                System.getProperty("native.encoding", "")));
        return enc.toUpperCase().replace("-", "").equals("UTF8");
    }

    public ConsoleLogListener() {
        install();
    }

    private static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        System.setOut(new PrintStream(new DecoratingStream(System.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new DecoratingStream(System.err), true, StandardCharsets.UTF_8));
    }

    @Override
    public void onTestStart(ITestResult result) {
        BugReport.setCurrentTest(name(result));
        System.out.println("[TEST] START  " + name(result));
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        System.out.println("[TEST] PASSED " + name(result) + " (" + seconds(result) + "s)");
    }

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
        if (method.isTestMethod() && runFailed && !Boolean.getBoolean("fail.continue")) {
            throw new SkipException(FAIL_FAST_MESSAGE + " (add -Dfail.continue=true to keep going)");
        }
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
        // nothing to do after a method
    }

    @Override
    public void onTestFailure(ITestResult result) {
        runFailed = true;
        Throwable t = result.getThrowable();
        System.out.println("[TEST] FAILED " + name(result) + " (" + seconds(result) + "s)"
                + (t == null ? "" : " - " + t.getClass().getSimpleName() + ": " + t.getMessage()));
        printErrorLog(result);
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        Throwable skip = result.getThrowable();
        if (skip != null && String.valueOf(skip.getMessage()).startsWith(FAIL_FAST_MESSAGE)) {
            System.out.println("[TEST] SKIPPED " + name(result) + " - an earlier test failed, the run stopped there");
            return;
        }
        System.out.println("[TEST] SKIPPED " + name(result));
        // A retried attempt is reported as SKIPPED but still carries the exception that caused it.
        printErrorLog(result);
    }

    /**
     * Copy-paste friendly error block: full cause chain + stack trace (test-framework/JDK frames
     * trimmed), printed between BEGIN/END markers and also saved to target/error-log.txt.
     * Stack lines are indented with spaces (not tabs) so they print without a timestamp prefix.
     */
    private static void printErrorLog(ITestResult result) {
        Throwable t = result.getThrowable();
        if (t == null) {
            return;
        }
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        StringBuilder body = new StringBuilder();
        for (String line : sw.toString().split("\\R")) {
            boolean noise = line.startsWith("\tat org.testng.") || line.startsWith("\tat java.base/")
                    || line.startsWith("\tat jdk.internal.") || line.startsWith("\tat org.apache.maven.")
                    || line.startsWith("\tat sun.reflect.");
            if (!noise && !line.isBlank()) {
                body.append(line.replace("\t", "    ")).append(System.lineSeparator());
            }
        }
        String header = "===== ERROR LOG: " + name(result) + " (" + seconds(result) + "s) =====";
        printWhere(result, t);
        // Kept for good in bug_results/ (target/error-log.txt below is wiped by every `mvn clean`).
        BugReport.testFailure(name(result), t);
        BugReport.saveErrorLog(name(result), header, body.toString());
        System.out.println("[FAILED] " + header);
        System.out.print(body);
        System.out.println("[FAILED] ===== END ERROR LOG =====");
        try {
            java.nio.file.Path file = java.nio.file.Paths.get("target", "error-log.txt");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, header + System.lineSeparator() + body,
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // terminal copy is the main output; the file is a convenience
        }
    }

    /** Frames from these packages are framework/library code, not ours. */
    private static final String[] LIBRARY_PREFIXES = {"java.", "javax.", "jdk.", "sun.", "org.testng.", "org.apache.",
            "com.microsoft.playwright.", "com.fasterxml.", "org.slf4j.", "kotlin."};

    private static boolean isLibrary(String className) {
        for (String prefix : LIBRARY_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static StackTraceElement firstProjectFrame(Throwable t) {
        for (StackTraceElement e : t.getStackTrace()) {
            if (!isLibrary(e.getClassName())) {
                return e;
            }
        }
        return null;
    }

    /** The few message lines that say what went wrong (Playwright's multi-line "Error { message=... Call log: - waiting for ... }" in particular). */
    private static String reasonLines(String message) {
        if (message == null || message.isBlank()) {
            return "(no message)";
        }
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String raw : message.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("stack=") || line.startsWith("at ") || line.startsWith("name=")
                    || line.equals("}") || line.equals("Error {") || line.equals("Call log:")) {
                continue;
            }
            sb.append(kept++ == 0 ? "" : " | ").append(line);
            if (kept == 3) {
                break;
            }
        }
        String out = sb.length() == 0 ? message.trim() : sb.toString();
        return out.length() > 400 ? out.substring(0, 400) + "..." : out;
    }

    /** One short block that says WHERE the test failed - so you do not have to read the stack trace to find it. */
    private static void printWhere(ITestResult result, Throwable t) {
        Throwable root = t;
        for (int i = 0; i < 20 && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        StackTraceElement inCode = firstProjectFrame(root);
        if (inCode == null) {
            inCode = firstProjectFrame(t);
        }
        // The line in the @Test method itself (not a helper it called): same class AND the test method's own name.
        String testClassName = result.getTestClass().getRealClass().getName();
        String testMethodName = result.getMethod().getMethodName();
        StackTraceElement testFrame = null;
        for (Throwable x = t; x != null && testFrame == null; x = x.getCause() == x ? null : x.getCause()) {
            for (StackTraceElement e : x.getStackTrace()) {
                if (e.getClassName().equals(testClassName) && e.getMethodName().equals(testMethodName)) {
                    testFrame = e;
                    break;
                }
            }
        }
        System.out.println("[FAILED] ===== WHERE IT FAILED =====");
        System.out.println("[FAILED] Test       : " + name(result));
        System.out.println("[FAILED] In code    : " + (inCode == null ? "(not in project code)" : inCode));
        System.out.println("[FAILED] Called from: " + (testFrame == null ? "(see the stack below)" : testFrame));
        System.out.println("[FAILED] Reason     : " + root.getClass().getSimpleName() + ": " + reasonLines(root.getMessage()));
        System.out.println("[FAILED] Last step  : " + (lastStep.isEmpty() ? "(none logged yet)" : lastStep));
        System.out.println("[FAILED] " + (Boolean.getBoolean("fail.continue")
                ? "Run continues (-Dfail.continue=true)."
                : "The run stops here: tests that had not started are skipped, nothing is re-run. Run it again by hand."));
    }

    private static String name(ITestResult result) {
        return result.getTestClass().getRealClass().getSimpleName() + "." + result.getMethod().getMethodName();
    }

    private static String seconds(ITestResult result) {
        return String.format("%.1f", (result.getEndMillis() - result.getStartMillis()) / 1000.0);
    }

    private static final String RESET = "\u001B[0m";
    private static final String DIM = "\u001B[90m";
    private static final String RED = "\u001B[91m";
    private static final String GREEN = "\u001B[92m";
    private static final String YELLOW = "\u001B[93m";
    private static final String BLUE = "\u001B[94m";
    private static final String MAGENTA = "\u001B[95m";

    /** Buffers one line at a time, then writes it with time prefix, icon and colour. */
    private static final class DecoratingStream extends OutputStream {
        private final OutputStream delegate;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        DecoratingStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (b == '\n') {
                emit(line.toString(StandardCharsets.UTF_8).replaceAll("\r$", ""));
                line.reset();
            } else {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            delegate.flush();
        }

        private void emit(String text) throws IOException {
            if (text.startsWith("[CUSTOMER]") || text.startsWith("[RESTAURANT]") || text.startsWith("[DRIVER")
                    || text.startsWith("[ADMIN") || text.startsWith("[STEP]") || text.startsWith("[CLEANUP]")) {
                lastStep = text.length() > 220 ? text.substring(0, 220) + "..." : text;
            }
            String out;
            if (text.startsWith("    ")) { // continuation (pretty JSON body): dim, no time/icon
                out = paint(DIM, text);
                BugReport.terminal(text);
            } else {
                String time = "[" + LocalTime.now().format(TIME) + "] ";
                out = paint(DIM, time) + decorate(text);
                BugReport.terminal(time + text);   // the same line, kept for good under bug_results/terminal-logs/
            }
            delegate.write((out + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            delegate.flush();
        }

        private static String decorate(String t) {
            if (t.startsWith("[TEST] START")) return paint(BLUE, icon("▶", ">") + t);
            if (t.startsWith("[TEST] PASSED")) return paint(GREEN, icon("✔", "+") + t);
            if (t.startsWith("[TEST] FAILED") || t.startsWith("[FAILED]") || t.startsWith("[BUG]")) return paint(RED, icon("✖", "x") + t);
            if (t.startsWith("[TEST] SKIPPED")) return paint(YELLOW, icon("⚠", "!") + t);
            if (t.startsWith("[LOGIN]") || t.startsWith("[WARN]") || t.startsWith("Retrying")) return paint(YELLOW, icon("⚠", "!") + t);
            if (t.startsWith("[MQTT]")) return paint(MAGENTA, icon("›", "*") + roleBadge(t));
            if (t.startsWith("[ADMIN]")) return paint(RED, icon("●", "*") + t);
            if (t.startsWith("[CUSTOMER]") || t.startsWith("[RESTAURANT]") || t.startsWith("[DRIVER]")
                    || t.startsWith("[RESULT]") || t.startsWith("[STEP]") || t.startsWith("[CLEANUP]")
                    || t.startsWith("[EVIDENCE]")) return paint(GREEN, icon("✔", "+") + t);
            return t;
        }

        private static String icon(String unicode, String ascii) {
            return (UNICODE ? unicode : ascii) + " ";
        }

        /** Gives the [CUSTOMER]/[RESTAURANT]/[DRIVER] tag a coloured background. */
        private static String roleBadge(String t) {
            if (!COLOR) {
                return t;
            }
            return t.replace("[CUSTOMER]", RESET + "[97;44m CUSTOMER " + RESET + MAGENTA)
                    .replace("[RESTAURANT]", RESET + "[30;43m RESTAURANT " + RESET + MAGENTA)
                    .replace("[DRIVER]", RESET + "[97;45m DRIVER " + RESET + MAGENTA);
        }

        private static String paint(String color, String s) {
            return COLOR ? color + s + RESET : s;
        }
    }
}
