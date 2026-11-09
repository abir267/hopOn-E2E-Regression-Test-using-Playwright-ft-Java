package WebFunctionalTests.EndToEnd;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.adminPortalPages.AdminPortalLoginPage;
import pages.WebPages.adminPortalPages.RestaurantOverviewPage;
import pages.WebPages.adminPortalPages.RestaurantsListPage;
import utils.AdminCreatedRestaurant;
import utils.ConfigReader;

/**
 * Takes the restaurant admin-portal-automation most recently created (see
 * AdminCreatedRestaurant.latest()) and suspends it from the Hop On Admin
 * Management portal: Restaurants -> that restaurant's Overview page (opened
 * by its backend id, via the row's View link found by href - see
 * RestaurantsListPage.openRestaurantOverview()'s javadoc) -> Suspend Account ->
 * Restaurant Block modal (Block Reason, Block Status ON) -> Confirm Block. Every locator is
 * structural (component tag / formcontrolname / icon name), none use visible text.
 */
public class AdminCreatedRestaurantSuspendTest {

    private static final String BLOCK_REASON = "Automated test - restaurant no longer needed";

    @Test(description = "Admin portal: suspends the most recently admin-created restaurant via Restaurants > View > Suspend Account")
    public void suspendLatestAdminCreatedRestaurant() {
        AdminCreatedRestaurant.CreatedRestaurant restaurant = AdminCreatedRestaurant.latest();
        System.out.println("[ADMIN] Target restaurant: " + restaurant.name() + " (id=" + restaurant.restaurantId() + ")");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 900));
            Page page = context.newPage();

            RestaurantsListPage restaurantsList = new AdminPortalLoginPage(page)
                    .loginWithEmailAndPassword(ConfigReader.adminPortalEmail(), ConfigReader.adminPortalPassword());
            System.out.println("[ADMIN] Logged in, on Restaurants list");

            RestaurantOverviewPage overview = restaurantsList.openRestaurantOverview(restaurant.restaurantId());
            boolean alreadySuspended = overview.isSuspended();
            System.out.println("[ADMIN] Opened Restaurant Overview, already suspended before: " + alreadySuspended);

            if (alreadySuspended) {
                System.out.println("[ADMIN] Already suspended (header shows the Unblock action) - nothing to do");
            } else {
                overview.suspendAccount(BLOCK_REASON);
                System.out.println("[ADMIN] Submitted Suspend Account (Block Reason=\"" + BLOCK_REASON + "\", Block Status=ON)");
            }

            // Not just trusting the modal's own optimistic client-side state - reload and
            // re-read fresh, same confirm-after-reload pattern AdminCreatedRestaurantOrderLifecycleTest
            // uses for Accepting Orders. The signal is the header action flipping from the
            // Suspend (ban icon) button to the Unblock (check-circle icon) button; the status
            // pill is not used (it reads "Inactive" for blocked and unblocked alike).
            page.reload();
            boolean suspendedAfter = overview.isSuspended();
            System.out.println("[ADMIN] Suspended after reload (Unblock action shown): " + suspendedAfter);
            Assert.assertTrue(suspendedAfter,
                    "Expected " + restaurant.name() + " to show the Unblock action (i.e. be suspended) after a fresh reload");

            context.close();
            browser.close();
            System.out.println("[RESULT] SUCCESS: " + restaurant.name() + " suspended via admin portal (verified after reload)");
        }
    }
}
