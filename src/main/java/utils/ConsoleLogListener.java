package utils;

import org.testng.ITestListener;
import org.testng.ITestResult;

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
public class ConsoleLogListener implements ITestListener {

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
        System.out.println("[TEST] START  " + name(result));
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        System.out.println("[TEST] PASSED " + name(result) + " (" + seconds(result) + "s)");
    }

    @Override
    public void onTestFailure(ITestResult result) {
        Throwable t = result.getThrowable();
        System.out.println("[TEST] FAILED " + name(result) + " (" + seconds(result) + "s)"
                + (t == null ? "" : " - " + t.getClass().getSimpleName() + ": " + t.getMessage()));
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        System.out.println("[TEST] SKIPPED " + name(result));
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
            String out;
            if (text.startsWith("    ")) { // continuation (pretty JSON body): dim, no time/icon
                out = paint(DIM, text);
            } else {
                String time = "[" + LocalTime.now().format(TIME) + "] ";
                out = paint(DIM, time) + decorate(text);
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
