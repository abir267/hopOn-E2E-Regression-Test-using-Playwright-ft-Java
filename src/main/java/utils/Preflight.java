package utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Fails fast, with ONE clear message listing everything that is wrong, before a long run starts.
 *
 * The admin-created-restaurant flow depends on things that are NOT in git or that depend on how/where it is
 * launched, so a fresh clone or an IntelliJ run configuration trips over them one at a time, deep into the run:
 *   - TestData.properties          (gitignored, per machine; also must be filled in - no CHANGE_ME values)
 *   - working directory            (everything is resolved relative to the repo root)
 *   - admin-portal-automation/     (sibling Maven project that the test shells out to)
 *   - google-credentials.json      (gitignored; that project logs to a Google Sheet)
 *   - Maven on PATH                (the test runs `mvn` as a child process; an IDE often has no mvn on PATH)
 *
 * Run it on its own to see the verdict without starting a test:
 *   java -cp target/classes utils.Preflight
 */
public final class Preflight {

    private static final String ADMIN_DIR = "admin-portal-automation";
    private static final String ADMIN_CONFIG = ADMIN_DIR + "/src/test/resources/config.properties";
    private static final String TEST_DATA = "TestData.properties";

    /** Keys the admin-created-restaurant lifecycle test reads. admin.portal.* is optional (falls back to the admin project's config). */
    private static final String[] REQUIRED_KEYS = {
            "base.url", "restaurant.admin.url", "driver.api.base.url", "driver.auth.token", "driver.device.id",
            "test.user.otp", "headless"};

    private Preflight() {
    }

    /** Throws IllegalStateException listing every problem found; returns quietly (and logs one line) if all is well. */
    public static void checkAdminCreatedRestaurantFlow() {
        List<String> problems = new ArrayList<>();
        String cwd = Paths.get("").toAbsolutePath().toString();

        // 1. TestData.properties present and filled in
        Path testData = Paths.get(TEST_DATA);
        if (!Files.exists(testData)) {
            problems.add(TEST_DATA + " not found in the working directory (" + cwd + "). It is gitignored: copy "
                    + "TestData.properties.example to TestData.properties in the repo root and fill it in. "
                    + "If the file exists, the run's working directory is wrong - in IntelliJ: Run > Edit "
                    + "Configurations > Working directory = $PROJECT_DIR$ (the repo root).");
        } else {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(testData)) {
                props.load(in);
            } catch (IOException e) {
                problems.add("Could not read " + TEST_DATA + ": " + e.getMessage());
            }
            List<String> unset = new ArrayList<>();
            for (String key : REQUIRED_KEYS) {
                if (ConfigReader.isPlaceholder(props.getProperty(key))) {
                    unset.add(key);
                }
            }
            if (!unset.isEmpty()) {
                problems.add(TEST_DATA + " is missing or has placeholder values for: " + String.join(", ", unset));
            }
            // Seen live: "driver.auth.token is not a JWT" 100+ seconds into a run, after the whole admin step.
            String token = props.getProperty("driver.auth.token");
            if (!ConfigReader.isPlaceholder(token) && token.trim().split("[.]").length < 3) {
                problems.add("driver.auth.token is not a JWT (expected header.payload.signature, three parts separated by "
                        + "dots) - paste the full bearer token from a logged-in driver session, without a 'Bearer ' "
                        + "prefix or quotes.");
            }
        }

        checkAdminProject(problems, cwd);

        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder("[PREFLIGHT] " + problems.size() + " problem(s) - fix these before running:");
            for (int i = 0; i < problems.size(); i++) {
                message.append(System.lineSeparator()).append("  ").append(i + 1).append(". ").append(problems.get(i));
            }
            throw new IllegalStateException(message.toString());
        }
        System.out.println("[PREFLIGHT] OK - TestData.properties filled in, " + ADMIN_DIR + " + Google key present, Maven runnable (cwd " + cwd + ")");
    }

    /**
     * For AdminCreatedRestaurantCreateTest, the step that makes a NEW restaurant (admin -> category -> food) before the
     * real-driver test: it needs only the sibling admin-portal-automation project, its Google key and Maven.
     */
    public static void checkRestaurantCreation() {
        List<String> problems = new ArrayList<>();
        String cwd = Paths.get("").toAbsolutePath().toString();
        checkAdminProject(problems, cwd);
        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder("[PREFLIGHT] " + problems.size() + " problem(s) - fix these before running:");
            for (int i = 0; i < problems.size(); i++) {
                message.append(System.lineSeparator()).append("  ").append(i + 1).append(". ").append(problems.get(i));
            }
            throw new IllegalStateException(message.toString());
        }
        System.out.println("[PREFLIGHT] OK - " + ADMIN_DIR + " + Google key present, Maven runnable (cwd " + cwd + ")");
    }

    /** The sibling Maven project the creation step shells out to: its pom, its Google key, and a runnable Maven. */
    private static void checkAdminProject(List<String> problems, String cwd) {
        // 2. the sibling Maven project the test shells out to
        if (!new File(ADMIN_DIR, "pom.xml").isFile()) {
            problems.add(ADMIN_DIR + "/pom.xml not found relative to " + cwd
                    + " - the working directory must be the repo root (the folder that contains " + ADMIN_DIR + ").");
        } else {
            // 3. Google Sheets key the admin project needs (gitignored)
            String credentials = adminCredentialsPath();
            if (!new File(ADMIN_DIR, credentials).isFile()) {
                problems.add(ADMIN_DIR + "/" + credentials + " not found. It is gitignored (a service-account key), so "
                        + "a fresh clone does not have it - get it from whoever set up admin-portal-automation. Without it "
                        + "the admin step fails at 'Save restaurant to the sheet'.");
            }
        }

        // 4. Maven runnable as a child process
        if (mavenExecutable() == null) {
            problems.add("Maven ('mvn') could not be run from this process. The test starts admin-portal-automation with "
                    + "`mvn clean test`, and an IDE often has no mvn on PATH. Install Maven and add its bin folder to "
                    + "PATH (or set MAVEN_HOME), then restart IntelliJ so it picks the new PATH up.");
        }
    }

    /**
     * For AdminCreatedRestaurantSuspendTest, which needs no driver/customer settings but does need the restaurant that
     * admin-portal-automation created last, and a working admin-portal login.
     */
    public static void checkSuspendFlow() {
        List<String> problems = new ArrayList<>();
        String cwd = Paths.get("").toAbsolutePath().toString();
        if (!Files.exists(Paths.get(TEST_DATA))) {
            problems.add(TEST_DATA + " not found in the working directory (" + cwd + ") - copy TestData.properties.example "
                    + "to TestData.properties in the repo root; in IntelliJ set Working directory = $PROJECT_DIR$.");
        } else {
            try {
                ConfigReader.adminPortalUrl();
                ConfigReader.adminPortalEmail();
                ConfigReader.adminPortalPassword();
            } catch (IllegalStateException e) {
                problems.add(e.getMessage());
            }
        }
        if (!Files.exists(Paths.get(AdminCreatedRestaurant.FILE_PATH))) {
            problems.add(AdminCreatedRestaurant.FILE_PATH + " not found (relative to " + cwd + "): there is no "
                    + "restaurant to suspend yet. Run admin-portal-automation's e2e-testng.xml first, or run the "
                    + "lifecycle-then-suspend suite (adminCreatedRestaurantOrderThenSuspend.xml) which does both. Note "
                    + "`mvn clean` in admin-portal-automation deletes this file.");
        }
        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder("[PREFLIGHT] " + problems.size() + " problem(s) - fix these before running:");
            for (int i = 0; i < problems.size(); i++) {
                message.append(System.lineSeparator()).append("  ").append(i + 1).append(". ").append(problems.get(i));
            }
            throw new IllegalStateException(message.toString());
        }
        System.out.println("[PREFLIGHT] OK - admin portal login configured, last-created restaurant file present");
    }

    /**
     * For AdminCreatedRestaurantRealDriverTest: orders from the restaurant admin-portal-automation created last, with the
     * driver played by the real Android app over adb. Needs the customer/restaurant web settings, the admin-portal login
     * (to switch the restaurant's activation back on), that handoff file, and a ready phone with the driver app installed -
     * checked together so a locked/unauthorized phone fails in seconds instead of mid-order.
     */
    public static void checkRealDriverOrderFlow() {
        List<String> problems = new ArrayList<>();
        String cwd = Paths.get("").toAbsolutePath().toString();
        if (!Files.exists(Paths.get(TEST_DATA))) {
            problems.add(TEST_DATA + " not found in the working directory (" + cwd + ") - copy TestData.properties.example "
                    + "to TestData.properties in the repo root; in IntelliJ set Working directory = $PROJECT_DIR$.");
        } else {
            for (String key : new String[] {"base.url", "restaurant.admin.url", "test.user.otp", "headless"}) {
                if (ConfigReader.isPlaceholder(ConfigReader.get(key, ""))) {
                    problems.add("'" + key + "' in " + TEST_DATA + " is missing or still a placeholder (CHANGE_ME).");
                }
            }
            try {
                ConfigReader.adminPortalUrl();
                ConfigReader.adminPortalEmail();
                ConfigReader.adminPortalPassword();
            } catch (IllegalStateException e) {
                problems.add(e.getMessage());
            }
        }
        if (!Files.exists(Paths.get(AdminCreatedRestaurant.FILE_PATH))) {
            problems.add(AdminCreatedRestaurant.FILE_PATH + " not found (relative to " + cwd + "): no restaurant has been "
                    + "created yet. It is written when admin-portal-automation's e2e-testng.xml (or the full lifecycle test) "
                    + "finishes; `mvn clean` there deletes it, so a failed admin run leaves none.");
        }
        try {
            AdbDevice device = new AdbDevice();
            device.requireReady();
            if (!device.hasPackage(ConfigReader.driverAppPackage())) {
                problems.add("Driver app " + ConfigReader.driverAppPackage() + " is not installed on the phone "
                        + "(set driver.app.package in TestData.properties if the build id differs).");
            }
        } catch (IllegalStateException e) {
            problems.add(e.getMessage());
        }
        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder("[PREFLIGHT] " + problems.size() + " problem(s) - fix these before running:");
            for (int i = 0; i < problems.size(); i++) {
                message.append(System.lineSeparator()).append("  ").append(i + 1).append(". ").append(problems.get(i));
            }
            throw new IllegalStateException(message.toString());
        }
        System.out.println("[PREFLIGHT] OK - web settings + admin login configured, last-created restaurant file present, phone ready with "
                + ConfigReader.driverAppPackage());
    }

    /**
     * The command that runs Maven here: "mvn"/"mvn.cmd" if it is on PATH, else MAVEN_HOME/M2_HOME's bin. Null if none
     * of them work. The first success is remembered.
     */
    public static synchronized String mavenExecutable() {
        if (resolvedMaven != null) {
            return resolvedMaven;
        }
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        List<String> candidates = new ArrayList<>();
        candidates.add(name);
        for (String envName : new String[] {"MAVEN_HOME", "M2_HOME"}) {
            String home = System.getenv(envName);
            if (home != null && !home.isBlank()) {
                candidates.add(Paths.get(home, "bin", name).toString());
            }
        }
        for (String candidate : candidates) {
            if (runsOk(candidate)) {
                resolvedMaven = candidate;
                return candidate;
            }
        }
        return null;
    }

    private static String resolvedMaven;

    private static boolean runsOk(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "-v").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String adminCredentialsPath() {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(ADMIN_CONFIG))) {
            props.load(in);
        } catch (IOException ignored) {
            // fall through to the default
        }
        return props.getProperty("google.credentials.path", "google-credentials.json").trim();
    }

    public static void main(String[] args) {
        try {
            if (args.length > 0 && "suspend".equalsIgnoreCase(args[0])) {
                checkSuspendFlow();
            } else {
                checkAdminCreatedRestaurantFlow();
            }
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
    }
}
