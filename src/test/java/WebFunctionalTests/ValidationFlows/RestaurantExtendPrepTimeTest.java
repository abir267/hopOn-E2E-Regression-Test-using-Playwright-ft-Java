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
import pages.WebPages.customerPages.OrderTrackingPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import utils.ConfigReader;
import utils.RetryAnalyzer;
import utils.TestDataGenerator;

import java.util.List;

/**
 * Observational validation: when a restaurant extends its preparation time
 * via "Need More Time" (see LiveOrdersPage.extendPreparationTime()), the
 * customer's order-tracking page must reflect that - the customer should not
 * be left staring at a stale ETA while the kitchen has actually asked for
 * more time.
 *
 * CONFIRMED live (2026-09-26, order #020-469): a "+20 Min" extension shifted
 * the displayed delivery window from "02:20 PM - 02:29 PM" to
 * "02:40 PM - 02:53 PM" - roughly the extension amount, though not an exact
 * +20/+20 shift on both ends (start +20 min, end +24 min). No separate
 * "delayed"/"kitchen needs more time" banner was shown anywhere on the
 * tracking page - the shifted window is the ONLY visible signal to the
 * customer. This test asserts the window actually changes; if a future
 * change silently stops updating it (leaving a stale ETA with zero customer
 * notice), that's the bug this guards against.
 */
public class RestaurantExtendPrepTimeTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";

    @Test(description = "Restaurant extending prep time must update the customer's visible delivery ETA",
            retryAnalyzer = RetryAnalyzer.class)
    public void extendingPrepTimeUpdatesCustomerEta() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            BrowserContext custContext = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1366, 768)
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
            CheckoutPage checkoutPage = new CartPage(custPage).proceedToCheckout();
            checkoutPage.placeOrder();
            Assert.assertTrue(checkoutPage.isOrderConfirmed(), "Expected order confirmation");
            String orderNo = checkoutPage.orderId();
            OrderTrackingPage trackingPage = checkoutPage.trackOrder();
            System.out.println("[CUSTOMER] Order placed: #" + orderNo);

            String etaBefore = trackingPage.deliveryTimeWindow();
            System.out.println("[CUSTOMER] ETA before extension: " + etaBefore);

            Page adminPage = browser.newContext(new Browser.NewContextOptions()
                    .setBaseURL(ConfigReader.restaurantAdminUrl())
                    .setViewportSize(1440, 900)).newPage();
            WebRestaurantPage restaurant = new AdminLoginPage(adminPage)
                    .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());
            restaurant.openLiveOrders();
            LiveOrdersPage liveOrders = new LiveOrdersPage(adminPage);
            liveOrders.openTab("New");
            liveOrders.openOrder(orderNo);
            liveOrders.acceptAndStartPreparing();
            liveOrders.extendPreparationTime("+20 Min", "Kitchen is busy");
            System.out.println("[RESTAURANT] Order #" + orderNo + " prep time extended by +20 Min (Kitchen is busy)");

            custPage.reload();
            custPage.waitForTimeout(1500);
            String etaAfter = trackingPage.deliveryTimeWindow();
            System.out.println("[CUSTOMER] ETA after extension: " + etaAfter);

            custContext.close();
            browser.close();

            Assert.assertNotEquals(etaAfter, etaBefore,
                    "BUG: delivery ETA did not change after the restaurant extended prep time by +20 Min - "
                            + "customer would be left looking at a stale ETA with no indication of the delay.");
            System.out.println("[RESULT] CORRECT: customer ETA updated from '" + etaBefore + "' to '" + etaAfter + "' after extension.");
        }
    }
}
