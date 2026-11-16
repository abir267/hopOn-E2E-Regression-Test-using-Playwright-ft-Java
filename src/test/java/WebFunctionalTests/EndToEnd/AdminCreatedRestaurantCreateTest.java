package WebFunctionalTests.EndToEnd;

import org.testng.Assert;
import org.testng.annotations.Test;
import utils.AdminCreatedRestaurant;
import utils.Preflight;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Step 1 of the "fresh restaurant" chain: runs admin-portal-automation's e2e suite (creates a NEW restaurant, its category
 * and its food through the admin portal) and leaves it as AdminCreatedRestaurant.latest() for the next test in the suite -
 * AdminCreatedRestaurantRealDriverTest, which orders from it with the real driver app. This is the same creation step the
 * full lifecycle test does first (AdminCreatedRestaurantOrderLifecycleTest.createRestaurantCategoryAndFoodViaAdminPortal).
 */
public class AdminCreatedRestaurantCreateTest {

    @Test(description = "Admin portal: creates a new restaurant + category + food (admin-portal-automation e2e-testng.xml)")
    public void createRestaurantCategoryAndFood() {
        Preflight.checkRestaurantCreation();
        System.out.println("[ADMIN] Running admin-portal-automation e2e suite (create restaurant -> category -> food)...");
        // Resolved by Preflight (PATH first, then MAVEN_HOME/M2_HOME) - an IDE often has no mvn on PATH.
        ProcessBuilder processBuilder = new ProcessBuilder(Preflight.mavenExecutable(), "clean", "test", "-DsuiteFile=e2e-testng.xml")
                .directory(new File("admin-portal-automation"))
                .redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            // Re-printed through OUR System.out (not inheritIO) so it gets the same formatting as the rest of the run.
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("SLF4J:")) {
                        continue;
                    }
                    System.out.println(line);
                }
            }
            int exitCode = process.waitFor();
            Assert.assertEquals(exitCode, 0,
                    "admin-portal-automation e2e-testng.xml failed (restaurant/category/food not created) - see its output above");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Could not run admin-portal-automation's e2e-testng.xml suite", e);
        }
        AdminCreatedRestaurant.CreatedRestaurant restaurant = AdminCreatedRestaurant.latest();
        System.out.println("[ADMIN] Restaurant: " + restaurant.name() + " (id=" + restaurant.restaurantId()
                + ") | Category: " + restaurant.category() + " | Food: " + restaurant.food());
        System.out.println("[RESULT] SUCCESS: created " + restaurant.name() + " - ready for the next test in the suite");
    }
}
