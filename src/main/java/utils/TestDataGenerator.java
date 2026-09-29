package utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Ad-hoc test data generation — random BD phone numbers, emails, order notes, etc. */
public class TestDataGenerator {

    private static final Random RANDOM = new Random();
    private static final String[] BD_MOBILE_PREFIXES = {
            "013", "014", "015", "016", "017", "018", "019"
    };
    private static final Path RIDER_LIST_CSV = Paths.get("DataSource", "riderlist.csv");
    private static List<String> riderPhones;

    private TestDataGenerator() {
    }

    /**
     * Picks a random phone number from DataSource/riderlist.csv (CONFIRMED
     * against the live site 2026-09-15: every number in that file logs in with
     * the fixed dev OTP "1122").
     *
     * Why not a single fixed number in TestData.properties: the backend rate-limits
     * repeated /login/request calls per phone number with "Too many attempts! ...
     * wait 24 hours" (confirmed by burning 01700000000's quota during probing).
     * A test suite that logs in on every run against one fixed number will hit
     * that lockout within a handful of runs; rotating across riderlist.csv's ~350
     * numbers spreads that load out.
     */
    public static synchronized String randomRiderPhone() {
        if (riderPhones == null) {
            try {
                riderPhones = Files.readAllLines(RIDER_LIST_CSV).stream()
                        .skip(1) // header row: "phone"
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .toList();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + RIDER_LIST_CSV.toAbsolutePath(), e);
            }
            if (riderPhones.isEmpty()) {
                throw new IllegalStateException("No phone numbers found in " + RIDER_LIST_CSV.toAbsolutePath());
            }
        }
        return riderPhones.get(RANDOM.nextInt(riderPhones.size()));
    }

    /** Generates a syntactically valid Bangladeshi mobile number, e.g. "017XXXXXXXX". */
    public static String randomBdPhoneNumber() {
        String prefix = BD_MOBILE_PREFIXES[RANDOM.nextInt(BD_MOBILE_PREFIXES.length)];
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < 8; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    public static String randomEmail() {
        return "qa+" + UUID.randomUUID().toString().substring(0, 8) + "@hoponbd.test";
    }

    public static String randomOrderNote() {
        String[] notes = {
                "Please ring the bell twice.",
                "Leave at the door.",
                "Call before delivery.",
                "No onions please.",
                "Extra spicy."
        };
        return notes[RANDOM.nextInt(notes.length)];
    }
}
