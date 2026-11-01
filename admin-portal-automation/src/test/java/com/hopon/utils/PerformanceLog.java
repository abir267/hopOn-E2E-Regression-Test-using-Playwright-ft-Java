package com.hopon.utils;

import com.hopon.config.ConfigReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Records how long every step takes in every run, and writes target/performance/steps.csv and report.html. */
public class PerformanceLog {

    private record Entry(int run, String step, long ms, boolean passed) { }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static int currentRun = 0;

    public static synchronized void newRun() {
        currentRun++;
    }

    public static synchronized void record(String step, long ms, boolean passed) {
        ENTRIES.add(new Entry(currentRun, step, ms, passed));
    }

    public static synchronized void writeReport() {
        if (ENTRIES.isEmpty()) {
            return;
        }
        try {
            Path dir = Paths.get("target", "performance");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("steps.csv"), buildCsv(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("report.html"), buildHtml(), StandardCharsets.UTF_8);
            System.out.println("PERFORMANCE REPORT: " + dir.resolve("report.html").toAbsolutePath());
        } catch (IOException e) {
            System.out.println("Could not write the performance report: " + e.getMessage());
        }
    }

    private static String buildCsv() {
        StringBuilder csv = new StringBuilder("run,step,duration_ms,result\n");
        for (Entry e : ENTRIES) {
            csv.append(e.run()).append(",\"").append(e.step().replace("\"", "\"\"")).append("\",")
                    .append(e.ms()).append(',').append(e.passed() ? "PASS" : "FAIL").append('\n');
        }
        return csv.toString();
    }

    private static String buildHtml() {
        Map<String, List<Entry>> byStep = new LinkedHashMap<>();
        Map<Integer, List<Entry>> byRun = new TreeMap<>();
        for (Entry e : ENTRIES) {
            byStep.computeIfAbsent(e.step(), k -> new ArrayList<>()).add(e);
            byRun.computeIfAbsent(e.run(), k -> new ArrayList<>()).add(e);
        }

        List<Long> totals = new ArrayList<>();
        long failedRuns = 0;
        for (List<Entry> run : byRun.values()) {
            totals.add(run.stream().mapToLong(Entry::ms).sum());
            if (run.stream().anyMatch(e -> !e.passed())) {
                failedRuns++;
            }
        }
        double avgTotal = totals.stream().mapToLong(Long::longValue).average().orElse(0);
        long fastest = totals.stream().mapToLong(Long::longValue).min().orElse(0);
        long slowest = totals.stream().mapToLong(Long::longValue).max().orElse(0);
        double maxAvgStep = byStep.values().stream()
                .mapToDouble(l -> l.stream().mapToLong(Entry::ms).average().orElse(0))
                .max().orElse(1);

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset='utf-8'><title>Create restaurant - timing report</title><style>")
                .append("body{font-family:Segoe UI,Arial,sans-serif;margin:24px;color:#1e293b}")
                .append("table{border-collapse:collapse;margin:12px 0 28px}")
                .append("th,td{border:1px solid #e2e8f0;padding:6px 12px;text-align:right}")
                .append("th{background:#f1f5f9}td.l,th.l{text-align:left}")
                .append(".bar{display:inline-block;height:10px;background:#2f6f4f;vertical-align:middle}")
                .append(".fail{color:#b91c1c;font-weight:600}")
                .append("</style></head><body>");

        h.append("<h1>Create restaurant: step timing report</h1>");
        h.append("<p>Generated ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss")))
                .append(" &middot; Runs: <b>").append(byRun.size()).append("</b>")
                .append(" &middot; Runs with a failed step: <b>").append(failedRuns).append("</b>")
                .append(" &middot; Average run: <b>").append(secs(avgTotal)).append(" s</b>")
                .append(" &middot; Fastest: <b>").append(secs(fastest)).append(" s</b>")
                .append(" &middot; Slowest: <b>").append(secs(slowest)).append(" s</b></p>");

        h.append("<h2>Time per step (seconds)</h2><table><tr><th class='l'>Step</th><th>Runs</th><th>Failed</th>")
                .append("<th>Min</th><th>Average</th><th>Max</th><th class='l'>Average (bar)</th></tr>");
        for (Map.Entry<String, List<Entry>> s : byStep.entrySet()) {
            List<Entry> list = s.getValue();
            long min = list.stream().mapToLong(Entry::ms).min().orElse(0);
            double avg = list.stream().mapToLong(Entry::ms).average().orElse(0);
            long max = list.stream().mapToLong(Entry::ms).max().orElse(0);
            long failed = list.stream().filter(e -> !e.passed()).count();
            int barWidth = (int) Math.round(avg / Math.max(maxAvgStep, 1) * 240);
            h.append("<tr><td class='l'>").append(esc(s.getKey())).append("</td><td>").append(list.size())
                    .append("</td><td").append(failed > 0 ? " class='fail'" : "").append(">").append(failed)
                    .append("</td><td>").append(secs(min)).append("</td><td>").append(secs(avg))
                    .append("</td><td>").append(secs(max)).append("</td><td class='l'><span class='bar' style='width:")
                    .append(barWidth).append("px'></span></td></tr>");
        }
        h.append("</table>");

        h.append("<h2>Time per run</h2><table><tr><th>Run</th><th>Total (s)</th><th class='l'>Slowest step</th><th class='l'>Result</th></tr>");
        for (Map.Entry<Integer, List<Entry>> r : byRun.entrySet()) {
            List<Entry> list = r.getValue();
            long total = list.stream().mapToLong(Entry::ms).sum();
            Entry slow = list.stream().max(Comparator.comparingLong(Entry::ms)).orElse(null);
            Entry failedStep = list.stream().filter(e -> !e.passed()).findFirst().orElse(null);
            h.append("<tr><td>").append(r.getKey()).append("</td><td>").append(secs(total))
                    .append("</td><td class='l'>").append(slow == null ? "" : esc(slow.step()) + " (" + secs(slow.ms()) + " s)")
                    .append("</td><td class='l").append(failedStep != null ? " fail" : "").append("'>")
                    .append(failedStep == null ? "All steps passed" : "Failed at: " + esc(failedStep.step()))
                    .append("</td></tr>");
        }
        h.append("</table>");

        h.append("<p><small>One user at a time, measured from the browser. This is a timing report, not a load test. ")
                .append("Each upload step includes a fixed wait of ")
                .append(ConfigReader.getOrDefault("upload.wait.ms", "3000"))
                .append(" ms (upload.wait.ms) after the file is chosen.</small></p></body></html>");
        return h.toString();
    }

    private static String secs(double ms) {
        return String.format(Locale.ROOT, "%.2f", ms / 1000.0);
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}