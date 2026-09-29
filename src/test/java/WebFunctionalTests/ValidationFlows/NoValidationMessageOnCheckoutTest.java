package WebFunctionalTests.ValidationFlows;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Geolocation;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.adminPages.AdminLoginPage;
import pages.WebPages.adminPages.LiveOrdersPage;
import pages.WebPages.adminPages.WebRestaurantPage;
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.CheckoutPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import utils.ConfigReader;
import utils.RetryAnalyzer;
import utils.TestDataGenerator;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Bug report (Issue 6): "Missing validation when restaurant stops accepting
 * orders / is blocked / item becomes unavailable" - repro steps as filed:
 *   1. Add item(s) to cart from a restaurant.
 *   2. Restaurant stops accepting new orders (backend-side), while the item
 *      is ALREADY in the cart.
 *   3. Attempt to proceed to checkout on web.
 *   4. Observe: no clear validation message is shown.
 *
 * This is the SAME underlying bug this suite already confirmed in
 * RestaurantNotAcceptingOrdersTest (there, the restaurant is turned off
 * BEFORE adding to cart; here, it's turned off AFTER, matching the bug
 * report's exact repro order) - CONFIRMED both orderings hit the same gap:
 * checkout proceeds all the way to a real placed order with zero backend
 * message surfaced to the customer anywhere in the flow.
 *
 * Expected per the report: the backend's unavailability message should
 * surface at cart or checkout - "similar to how order cancellation is
 * currently messaged on mobile" (see the sibling CancelOrderModalBrokenTest
 * for why that specific comparison point is itself currently broken on web
 * too).
 */
public class NoValidationMessageOnCheckoutTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";
    private static final Path SCREENSHOT_PATH = Paths.get("target", "bug-evidence", "issue6_no_validation_on_checkout.png");

    @Test(description = "Restaurant going offline AFTER item is already in cart must show a validation message at checkout",
            retryAnalyzer = RetryAnalyzer.class)
    public void checkoutProceedsSilentlyAfterRestaurantGoesOffline() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            BrowserContext custContext = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1366, 900)
                    .setGeolocation(new Geolocation(ConfigReader.deliveryLatitude(), ConfigReader.deliveryLongitude()))
                    .setPermissions(List.of("geolocation")));
            Page custPage = custContext.newPage();

            HomePage home = new HomePage(custPage).open(ConfigReader.baseUrl());
            home.openLogin();
            String phone = TestDataGenerator.randomRiderPhone();
            new LoginPage(custPage).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
            System.out.println("[CUSTOMER] Logged in as " + phone);

            home.setDeliveryLocationToCurrentLocation();
            home.openRestaurantById(ConfigReader.baseUrl(), RESTAURANT_ID);
            new RestaurantMenuPage(custPage).addItemToCartWithSize(MENU_ITEM_NAME, MENU_ITEM_SIZE);
            System.out.println("[CUSTOMER] Added " + MENU_ITEM_NAME + " to cart (restaurant still accepting orders at this point)");

            // Step 2 of the reported repro: restaurant goes offline AFTER the
            // item is already sitting in the customer's cart.
            Page adminPage = browser.newContext(new Browser.NewContextOptions()
                    .setBaseURL(ConfigReader.restaurantAdminUrl())
                    .setViewportSize(1440, 900)).newPage();
            WebRestaurantPage restaurant = new AdminLoginPage(adminPage)
                    .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());
            restaurant.openLiveOrders();
            LiveOrdersPage liveOrders = new LiveOrdersPage(adminPage);

            try {
                liveOrders.setAcceptingOrders(false);
                Assert.assertFalse(liveOrders.isAcceptingOrders(), "Precondition failed: could not turn restaurant status off");
                System.out.println("[RESTAURANT] Status set to: " + liveOrders.restaurantStatusLabel() + " (item already in customer's cart)");

                // Step 3: attempt checkout on web with the cart untouched.
                CartPage cartPage = new CartPage(custPage);
                Assert.assertFalse(cartPage.isEmpty(), "Expected the item to still be in the cart going into checkout");

                CheckoutPage checkoutPage = cartPage.proceedToCheckout();
                custPage.waitForTimeout(1000);

                boolean warningVisible = custPage.getByText("unavailable", new Page.GetByTextOptions().setExact(false)).isVisible()
                        || custPage.getByText("closed", new Page.GetByTextOptions().setExact(false)).isVisible()
                        || custPage.getByText("not accepting", new Page.GetByTextOptions().setExact(false)).isVisible();
                System.out.println("[CUSTOMER] Any unavailability warning visible on checkout page: " + warningVisible);

                checkoutPage.placeOrder();
                boolean confirmed = checkoutPage.isOrderConfirmed();
                String orderId = confirmed ? checkoutPage.orderId() : null;

                SCREENSHOT_PATH.getParent().toFile().mkdirs();
                custPage.screenshot(new Page.ScreenshotOptions().setPath(SCREENSHOT_PATH).setFullPage(false));
                System.out.println("[EVIDENCE] Screenshot saved to " + SCREENSHOT_PATH.toAbsolutePath());
                custContext.close();

                Assert.assertFalse(warningVisible,
                        "This assertion documents the CURRENT bug (no unavailability message shown at checkout) - "
                                + "if it now fails because a warning WAS shown, the gap has likely been fixed.");
                Assert.assertTrue(confirmed,
                        "This assertion documents the CURRENT bug (order id=" + orderId + " placed with zero warning) - "
                                + "if order placement now fails/blocks instead, that's the fix landing; update this "
                                + "test to assert the block + message instead of silent success.");
                System.out.println("[RESULT] CONFIRMED BUG: order " + orderId + " placed with no validation message, "
                        + "despite the restaurant being 'Not Accepting Orders' when checkout was attempted.");
            } finally {
                // Restore BEFORE closing the browser - the admin page needs it
                // alive (see RestaurantNotAcceptingOrdersTest's finally block
                // for the exact same lesson learned the hard way).
                liveOrders.setAcceptingOrders(true);
                System.out.println("[CLEANUP] Restaurant status restored to: " + liveOrders.restaurantStatusLabel());
                browser.close();
            }
        }
    }
}
