package com.hopon.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigReader {

    private static final Properties props = new Properties();

    static {
        try (InputStream in = ConfigReader.class.getResourceAsStream("/admin-config.properties")) {
            if (in == null) {
                throw new RuntimeException("config.properties not found in src/test/resources");
            }
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config.properties", e);
        }
    }

    /** JVM -D override first, then config.properties. */
    public static String get(String key) {
        String value = System.getProperty(key, props.getProperty(key));
        if (value == null) {
            throw new RuntimeException("Missing config key: " + key);
        }
        return value.trim();
    }

    public static String getOrDefault(String key, String defaultValue) {
        return System.getProperty(key, props.getProperty(key, defaultValue)).trim();
    }
}
