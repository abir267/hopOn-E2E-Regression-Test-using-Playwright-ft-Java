package WebFunctionalTests.ValidationFlows;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Geolocation;
import foodAPIs.driverModule.DriverAPI;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.webrestaurantPages.WebRestaurantLogin;
import pages.WebPages.webrestaurantPages.LiveOrdersPage;
import pages.WebPages.webrestaurantPages.RestaurantPortalPage;
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.CheckoutPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import utils.ConfigReader;
import utils.RetryAnalyzer;
import utils.TestDataGenerator;

import java.util.List;

/**
 * Validation: a driver who was never assigned to an order (never called
 * Accepted) must not be able to cancel it - CONFIRMED live (2026-09-26, order
 * 020-3A0): a "Cancelled" acknowledge call from an unassigned driver 400s with
 * "Invalid user". This is CORRECT behavior, not a bug - this test documents
 * and guards it (unlike the sibling RestaurantNotAcceptingOrdersTest /
 * MenuItemNotAvailableTest, which document confirmed bugs).
 */
public class DriverCancelBeforeAcceptTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";

    @Test(description = "An unassigned driver must not be able to cancel an order they never accepted",
            retryAnalyzer = RetryAnalyzer.class)
    public void cannotCancelBeforeAccepting() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            String orderNo = placeCustomerOrder(browser);
            System.out.println("[CUSTOMER] Order placed: #" + orderNo);

            String orderUuid = acceptOrderAsRestaurant(browser, orderNo);
            System.out.println("[RESTAURANT] Order #" + orderNo + " accepted into Preparing (uuid=" + orderUuid + "), no driver assigned yet");

            DriverAPI driverAPI = new DriverAPI(playwright, ConfigReader.driverApiBaseUrl(),
                    ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId());
            try {
                driverAPI.cancelOrder(orderUuid);
                Assert.fail("BUG: an unassigned driver was able to cancel order #" + orderNo + " - expected a 400 'Invalid user' rejection");
            } catch (RuntimeException e) {
                Assert.assertTrue(e.getMessage().contains("Invalid user"),
                        "Expected 'Invalid user' rejection, got a different error: " + e.getMessage());
                System.out.println("[RESULT] CORRECT: unassigned driver's cancel attempt was rejected - " + e.getMessage());
            } finally {
                driverAPI.close();
            }

            browser.close();
        }
    }

    private String placeCustomerOrder(Browser browser) {
        Page page = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1366, 768)
                .setGeolocation(new Geolocation(ConfigReader.deliveryLatitude(), ConfigReader.deliveryLongitude()))
                .setPermissions(List.of("geolocation"))).newPage();

        HomePage home = new HomePage(page).open(ConfigReader.baseUrl());
        home.openLogin();
        new LoginPage(page).loginWithPhoneAndOtp(TestDataGenerator.randomRiderPhone(), ConfigReader.testUserOtp());
        home.setDeliveryLocationToCurrentLocation();
        home.openRestaurantById(ConfigReader.baseUrl(), RESTAURANT_ID);

        RestaurantMenuPage menuPage = new RestaurantMenuPage(page);
        menuPage.addItemToCartWithSize(MENU_ITEM_NAME, MENU_ITEM_SIZE);
        CheckoutPage checkoutPage = new CartPage(page).proceedToCheckout();
        checkoutPage.placeOrder();
        Assert.assertTrue(checkoutPage.isOrderConfirmed(), "Expected order confirmation");
        return checkoutPage.orderId();
    }

    private String acceptOrderAsRestaurant(Browser browser, String orderNo) {
        Page page = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(ConfigReader.restaurantAdminUrl())
                .setViewportSize(1440, 900)).newPage();
        RestaurantPortalPage restaurant = new WebRestaurantLogin(page)
                .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());
        restaurant.openLiveOrders();

        LiveOrdersPage liveOrders = new LiveOrdersPage(page);
        String orderUuid = liveOrders.orderUuidFor(orderNo);
        liveOrders.openTab("New");
        liveOrders.openOrder(orderNo);
        liveOrders.acceptAndStartPreparing();
        return orderUuid;
    }
}
