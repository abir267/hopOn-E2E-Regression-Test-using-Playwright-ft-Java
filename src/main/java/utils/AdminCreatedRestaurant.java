package utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;


public class AdminCreatedRestaurant {

    static final String FILE_PATH ="admin-portal-automation/target/last-created-restaurant.properties";

    public record CreatedRestaurant(String name, String email, String password,
                                     String restaurantId, String category, String food) {
    }

    /** The restaurant admin-portal-automation most recently finished creating - see FILE_PATH above. */
    public static CreatedRestaurant latest() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(FILE_PATH)) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + FILE_PATH
                    + " - run admin-portal-automation's e2e-testng.xml suite first", e);
        }
        return new CreatedRestaurant(
                require(props, "name"), require(props, "email"), require(props, "password"),
                require(props, "restaurantId"), require(props, "category"), require(props, "food"));
    }

    private static String require(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing \"" + key + "\" in " + FILE_PATH);
        }
        return value;
    }
}
