package utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Loads TestData.properties from the repo root (relative to wherever Maven/the
 * JVM is run from). Not on the classpath deliberately — the file is gitignored
 * and per-machine, so it must live outside src/main/resources.
 */
public class ConfigReader {

    private static final Properties PROPERTIES = new Properties();
    private static final String CONFIG_FILE_NAME = "TestData.properties";
    private static boolean loaded = false;

    private ConfigReader() {
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        Path configPath = Paths.get(CONFIG_FILE_NAME);
        if (!Files.exists(configPath)) {
            throw new IllegalStateException(
                    "Missing " + CONFIG_FILE_NAME + " at " + configPath.toAbsolutePath() +
                    ". Copy TestData.properties.example to TestData.properties in the repo root " +
                    "and fill in real values before running tests.");
        }
        try (InputStream in = Files.newInputStream(configPath)) {
            PROPERTIES.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + CONFIG_FILE_NAME, e);
        }
        loaded = true;
    }

    public static String get(String key) {
        load();
        String value = PROPERTIES.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing key '" + key + "' in " + CONFIG_FILE_NAME);
        }
        return value.trim();
    }

    public static String get(String key, String defaultValue) {
        load();
        return PROPERTIES.getProperty(key, defaultValue).trim();
    }

    public static boolean getBoolean(String key) {
        return Boolean.parseBoolean(get(key));
    }

    public static int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    public static String baseUrl() {
        return get("base.url");
    }

    public static String apiBaseUrl() {
        return get("api.base.url");
    }

    public static boolean headless() {
        return getBoolean("headless");
    }

    public static String browser() {
        return get("browser", "chromium");
    }

    public static double slowMo() {
        return Double.parseDouble(get("slowmo.ms", "0"));
    }

    public static double defaultTimeout() {
        return Double.parseDouble(get("default.timeout.ms", "30000"));
    }

    public static String testUserPhone() {
        return get("test.user.phone");
    }

    public static String testUserOtp() {
        return get("test.user.otp");
    }

    public static double deliveryLatitude() {
        return Double.parseDouble(get("delivery.latitude", "23.7925"));
    }

    public static double deliveryLongitude() {
        return Double.parseDouble(get("delivery.longitude", "90.4078"));
    }

    public static String restaurantAdminUrl() {
        return get("restaurant.admin.url");
    }

    public static String restaurantAdminEmail() {
        return get("restaurant.admin.email");
    }

    public static String restaurantAdminPassword() {
        return get("restaurant.admin.password");
    }

    public static String driverApiBaseUrl() {
        return get("driver.api.base.url");
    }

    public static String driverAuthToken() {
        return get("driver.auth.token");
    }

    public static String driverDeviceId() {
        return get("driver.device.id");
    }
}
