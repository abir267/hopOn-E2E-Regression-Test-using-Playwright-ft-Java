package WebFunctionalTests.EndToEnd;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
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
 * Full cross-system order lifecycle, CONFIRMED end-to-end (2026-09-19, real
 * order #01A-FA6 on QA Burger Express [AA]):
 *   customer places an order -> restaurant admin accepts it into "Preparing"
 *   -> driver-app API accepts that same order.
 *
 * Three independent systems (customer web app, restaurant-admin web app,
 * driver-app backend API), so this manages its own Playwright/Browser rather
 * than extending BaseRunner or AdminBaseRunner - neither owns all three
 * actors at once, and the customer/restaurant legs need different base URLs
 * and geolocation grants active at the same time.
 */
public class OrderLifecycleTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String RESTAURANT_NAME = "QA Burger Express [AA]";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";

    @Test(description = "Customer places order -> restaurant accepts -> driver accepts, one continuous run",
            retryAnalyzer = RetryAnalyzer.class)
    public void customerToRestaurantToDriverAcceptance() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            try {
                String orderNo = placeCustomerOrder(browser);
                System.out.println("[CUSTOMER] Order placed: #" + orderNo);

                RestaurantAcceptance acceptance = acceptOrderAsRestaurant(browser, orderNo);
                System.out.println("[RESTAURANT] Order #" + orderNo + " accepted and marked Ready for Pickup (uuid="
                        + acceptance.orderUuid() + ", pin=" + acceptance.pin() + ")");

                runDriverFlow(playwright, orderNo, acceptance.orderUuid(), acceptance.pin());

                browser.close();
                System.out.println("[RESULT] SUCCESS: full lifecycle completed - customer -> restaurant -> driver (Accepted -> Arrived -> PickedUp -> Delivered).");
            } catch (Throwable t) {
                System.out.println("[FAILED] customerToRestaurantToDriverAcceptance: " + t.getClass().getSimpleName()
                        + " - " + t.getMessage());
                throw t;
            }
        }
    }

    private String placeCustomerOrder(Browser browser) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1366, 768)
                .setGeolocation(new Geolocation(ConfigReader.deliveryLatitude(), ConfigReader.deliveryLongitude()))
                .setPermissions(List.of("geolocation")));
        Page page = context.newPage();

        HomePage home = new HomePage(page).open(ConfigReader.baseUrl());
        home.openLogin();
        String phone = TestDataGenerator.randomRiderPhone();
        new LoginPage(page).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
        Assert.assertTrue(home.isLoggedIn(), "Customer must be logged in before ordering");
        System.out.println("[CUSTOMER] Logged in as " + phone);

        home.setDeliveryLocationToCurrentLocation();
        home.openRestaurantById(ConfigReader.baseUrl(), RESTAURANT_ID);
        RestaurantMenuPage menuPage = new RestaurantMenuPage(page);
        Assert.assertTrue(menuPage.restaurantTitle().contains(RESTAURANT_NAME),
                "Expected to land on " + RESTAURANT_NAME);

        menuPage.addItemToCartWithSize(MENU_ITEM_NAME, MENU_ITEM_SIZE);
        Assert.assertFalse(menuPage.isRestaurantUnavailable(), "Restaurant rejected order as unavailable");
        System.out.println("[CUSTOMER] Added to cart: " + MENU_ITEM_NAME + " (" + MENU_ITEM_SIZE + ")");

        CartPage cartPage = new CartPage(page);
        Assert.assertTrue(cartPage.containsItem(MENU_ITEM_NAME), "Expected item in basket");

        CheckoutPage checkoutPage = cartPage.proceedToCheckout();
        checkoutPage.placeOrder();
        Assert.assertTrue(checkoutPage.isOrderConfirmed(), "Expected order confirmation");

        String orderNo = checkoutPage.orderId();
        Assert.assertNotNull(orderNo, "Expected a non-null order number on confirmation");

        return orderNo;
    }

    private record RestaurantAcceptance(String orderUuid, String pin) {
    }

    private RestaurantAcceptance acceptOrderAsRestaurant(Browser browser, String orderNo) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(ConfigReader.restaurantAdminUrl())
                .setViewportSize(1440, 900));
        Page page = context.newPage();

        RestaurantPortalPage restaurant = new WebRestaurantLogin(page)
                .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());
        restaurant.openLiveOrders();

        LiveOrdersPage liveOrders = new LiveOrdersPage(page);
        String orderUuid = liveOrders.orderUuidFor(orderNo);
        Assert.assertNotNull(orderUuid, "Expected to find order " + orderNo + " in the on-going orders list");

        liveOrders.openTab("New");
        liveOrders.openOrder(orderNo);
        liveOrders.acceptAndStartPreparing();
        String pin = liveOrders.pickupPin();
        liveOrders.markReadyForPickup();

        return new RestaurantAcceptance(orderUuid, pin);
    }

    /** Runs the driver side through every CONFIRMED status step: Accepted -> Arrived -> PickedUp -> Delivered. */
    private void runDriverFlow(Playwright playwright, String orderNo, String orderUuid, String pin) {
        DriverAPI driverAPI = new DriverAPI(playwright, ConfigReader.driverApiBaseUrl(),
                ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId());

        JsonNode accepted = driverAPI.acceptOrder(orderUuid);
        assertDriverStatus(accepted, "Accepted");
        System.out.println("[DRIVER] Order #" + orderNo + " -> Accepted");

        JsonNode arrived = driverAPI.markArrived(orderUuid);
        assertDriverStatus(arrived, "Arrived");
        System.out.println("[DRIVER] Order #" + orderNo + " -> Arrived at restaurant");

        JsonNode pickedUp = driverAPI.markPickedUp(orderUuid, pin);
        assertDriverStatus(pickedUp, "PickedUp");
        System.out.println("[DRIVER] Order #" + orderNo + " -> PickedUp, heading to customer");

        JsonNode delivered = driverAPI.markDeliveredWithRandomProof(orderUuid);
        Assert.assertTrue(DriverAPI.isDelivered(delivered), "Expected order to be confirmed Delivered: " + delivered);
        System.out.println("[DRIVER] Order #" + orderNo + " -> Delivered (photo proof uploaded)");

        driverAPI.close();
    }

    private void assertDriverStatus(JsonNode response, String expectedStatus) {
        String status = response.at("/data/content/status").asText();
        Assert.assertEquals(status, expectedStatus, "Expected driver call to move order to " + expectedStatus);
    }
}
