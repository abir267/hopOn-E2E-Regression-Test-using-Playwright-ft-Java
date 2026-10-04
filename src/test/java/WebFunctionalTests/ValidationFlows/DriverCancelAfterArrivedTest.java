package WebFunctionalTests.ValidationFlows;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Validation: a driver who has already arrived at the restaurant can still
 * back out before confirming pickup. CONFIRMED live (2026-09-26, order
 * 020-3A0): "Cancelled" from the assigned driver succeeds even after
 * "Arrived", clearing "driverInfo" back to null. Never tried once a driver
 * has gone as far as "PickedUp" - that's a materially different real-world
 * case (food already left the restaurant) and isn't covered here.
 */
public class DriverCancelAfterArrivedTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";

    @Test(description = "A driver can cancel an order after arriving at the restaurant, before confirming pickup",
            retryAnalyzer = RetryAnalyzer.class)
    public void canCancelAfterArriving() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            String orderNo = placeCustomerOrder(browser);
            System.out.println("[CUSTOMER] Order placed: #" + orderNo);

            String orderUuid = acceptOrderAsRestaurant(browser, orderNo);
            System.out.println("[RESTAURANT] Order #" + orderNo + " accepted into Preparing (uuid=" + orderUuid + ")");

            DriverAPI driverAPI = new DriverAPI(playwright, ConfigReader.driverApiBaseUrl(),
                    ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId());

            JsonNode accepted = driverAPI.acceptOrder(orderUuid);
            Assert.assertEquals(accepted.at("/data/content/status").asText(), "Accepted");
            System.out.println("[DRIVER] Order #" + orderNo + " -> Accepted");

            JsonNode arrived = driverAPI.markArrived(orderUuid);
            Assert.assertEquals(arrived.at("/data/content/status").asText(), "Arrived");
            System.out.println("[DRIVER] Order #" + orderNo + " -> Arrived at restaurant");

            JsonNode cancelled = driverAPI.cancelOrder(orderUuid);
            Assert.assertTrue(DriverAPI.isCancelled(cancelled),
                    "Expected driverInfo to clear after cancelling: " + cancelled);
            System.out.println("[DRIVER] Order #" + orderNo + " -> Cancelled after arriving, before confirming pickup (driver unassigned)");

            driverAPI.close();
            browser.close();
            System.out.println("[RESULT] CORRECT: driver was able to cancel after arriving, before pickup.");
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
