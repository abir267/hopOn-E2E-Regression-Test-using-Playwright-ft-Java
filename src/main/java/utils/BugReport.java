package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Keeps every bug a run turns up in the repo-root {@code bug_results/} folder, across ALL runs - nothing is overwritten and
 * {@code mvn clean} does not delete it (unlike target/, where the per-run error log lives).
 *
 *   bug_results/bug-log.csv          one row per finding, appended by every run (open it in Excel / filter by kind or run_id)
 *   bug_results/error-logs/*.txt     the full error log (cause chain + trimmed stack) of every failed test, one file each
 *   bug_results/KNOWN_FINDINGS.md    the findings written up by hand (what they are, how they were confirmed)
 *
 * Kinds in the CSV:
 *   HTTP          a backend 4xx/5xx response seen by a browser page (the same ones printed as "[BUG][ROLE] ..." in the terminal)
 *   TEST_FAILURE  a test that failed (or a retried attempt), with the message and the first stack frame
 *   ENVIRONMENT   something outside the code under test that stopped a run: the dev login host not answering, a locked phone...
 *
 * Writing here is best-effort and silent on error - reporting a bug must never be what breaks a test.
 */
public final class BugReport {

    public static final Path DIR = Paths.get("bug_results");
    private static final Path CSV = DIR.resolve("bug-log.csv");
    private static final Path ERROR_LOGS = DIR.resolve("error-logs");
    private static final String HEADER = "timestamp,run_id,test,kind,role,status,method,where,detail";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** One id per JVM, so every row of one `mvn test` invocation shares it. */
    private static final String RUN_ID = LocalDateTime.now().format(FILE_STAMP);

    private static volatile String currentTest = "";

    private BugReport() {
    }

    /** Called by ConsoleLogListener when a test starts, so rows say which test was running. */
    public static void setCurrentTest(String name) {
        currentTest = name == null ? "" : name;
    }

    /** A backend 4xx/5xx response (status/method/url only - see NetworkErrorCapture for why no body). */
    public static void http(String role, int status, String method, String url) {
        append("HTTP", role, String.valueOf(status), method, url, "");
    }

    /** A failed test (or a retried attempt, which TestNG reports as skipped but which still carries the exception). */
    public static void testFailure(String test, Throwable t) {
        if (t == null) {
            return;
        }
        StackTraceElement[] stack = t.getStackTrace();
        String where = stack.length == 0 ? "" : stack[0].toString();
        append("TEST_FAILURE", "", "", "", where, t.getClass().getSimpleName() + ": " + t.getMessage(), test);
    }

    /** Something outside the code under test that stopped or disturbed a run. */
    public static void environment(String role, String where, String detail) {
        append("ENVIRONMENT", role, "", "", where, detail);
    }

    /** The full error log of one failed test, as its own file. */
    public static void saveErrorLog(String test, String header, String body) {
        try {
            Files.createDirectories(ERROR_LOGS);
            String file = LocalDateTime.now().format(FILE_STAMP) + "-" + test.replaceAll("[^A-Za-z0-9_.-]", "_") + ".txt";
            Files.writeString(ERROR_LOGS.resolve(file),
                    "run " + RUN_ID + System.lineSeparator() + header + System.lineSeparator() + body,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException | RuntimeException ignored) {
            // best-effort
        }
    }

    private static void append(String kind, String role, String status, String method, String where, String detail) {
        append(kind, role, status, method, where, detail, currentTest);
    }

    private static synchronized void append(String kind, String role, String status, String method, String where,
                                            String detail, String test) {
        try {
            Files.createDirectories(DIR);
            StringBuilder row = new StringBuilder();
            if (!Files.exists(CSV)) {
                // BOM so Excel opens the UTF-8 file with the right characters (the dev-site error text has curly quotes)
                row.append('﻿').append(HEADER).append(System.lineSeparator());
            }
            row.append(csv(LocalDateTime.now().format(STAMP))).append(',')
                    .append(csv(RUN_ID)).append(',')
                    .append(csv(test)).append(',')
                    .append(csv(kind)).append(',')
                    .append(csv(role)).append(',')
                    .append(csv(status)).append(',')
                    .append(csv(method)).append(',')
                    .append(csv(where)).append(',')
                    .append(csv(detail)).append(System.lineSeparator());
            Files.writeString(CSV, row.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // best-effort
        }
    }

    /** One CSV field: quoted, quotes doubled, line breaks flattened, capped so one huge message cannot bloat the file. */
    private static String csv(String value) {
        String v = value == null ? "" : value.replaceAll("\\s*\\R\\s*", " | ");
        if (v.length() > 400) {
            v = v.substring(0, 400) + "...";
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
