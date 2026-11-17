package com.hopon.utils;

import java.util.stream.Collectors;

/** Runs one named step and times it. If it fails, the error says which step and shows only the useful lines. */
public class Steps {

    public static void run(String name, Runnable action) {
        System.out.println("STEP: " + name);
        long start = System.nanoTime();
        try {
            action.run();
            PerformanceLog.record(name, elapsedMs(start), true);          // NEW
        } catch (RuntimeException e) {
            PerformanceLog.record(name, elapsedMs(start), false);         // NEW
            String msg = String.valueOf(e.getMessage());
            String details = msg.lines()
                    .map(String::trim)
                    .filter(l -> l.startsWith("message=")
                            || l.contains("waiting for locator")
                            || l.contains("resolved to")
                            || l.contains("intercepts pointer events"))
                    .distinct()
                    .limit(4)
                    .collect(Collectors.joining(" | "));
            if (details.isEmpty()) {
                details = msg.lines().findFirst().orElse("");
            }
            throw new RuntimeException("FAILED AT STEP '" + name + "' -> " + details);
        }
    }

    // NEW
    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}