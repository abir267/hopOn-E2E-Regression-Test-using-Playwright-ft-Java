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
 * Same three-system order lifecycle as {@link OrderLifecycleTest}, but with
 * the realistic interleaving CONFIRMED in the very first restaurant-admin
 * screenshot this project started from: an order sits in "Preparing" showing
 * "Assigning Rider..." (and its pickup PIN) BEFORE any driver is assigned -
 * i.e. the driver accepts (and arrives) WHILE the restaurant is still cooking,
 * not after the restaurant has already marked it ready.
 *
 * Sequence here: customer orders -> restaurant accepts into "Preparing" ->
 * driver accepts + arrives (order still Preparing) -> restaurant marks Ready
 * for Pickup -> driver picks up. OrderLifecycleTest instead does both
 * restaurant actions back to back before any driver call - keep both: they
 * exercise different orderings the real app allows.
 */
public class OrderLifecycleDriverInLoopTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String RESTAURANT_NAME = "QA Burger Express [AA]";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";

    @Test(description = "Restaurant accepts into Preparing -> driver accepts+arrives mid-prep -> restaurant marks Ready -> driver picks up",
            retryAnalyzer = RetryAnalyzer.class)
    public void driverAcceptsWhileStillPreparing() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            try {
            String orderNo = placeCustomerOrder(browser);
            System.out.println("[CUSTOMER] Order placed: #" + orderNo);

            BrowserContext adminContext = browser.newContext(new Browser.NewContextOptions()
                    .setBaseURL(ConfigReader.restaurantAdminUrl())
                    .setViewportSize(1440, 900));
            Page adminPage = adminContext.newPage();
            RestaurantPortalPage restaurant = new WebRestaurantLogin(adminPage)
                    .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());
            restaurant.openLiveOrders();
            LiveOrdersPage liveOrders = new LiveOrdersPage(adminPage);

            String orderUuid = liveOrders.orderUuidFor(orderNo);
            Assert.assertNotNull(orderUuid, "Expected to find order " + orderNo + " in the on-going orders list");

            liveOrders.openTab("New");
            liveOrders.openOrder(orderNo);
            liveOrders.acceptAndStartPreparing();
            String pin = liveOrders.pickupPin();
            System.out.println("[RESTAURANT] Order #" + orderNo + " accepted -> Preparing (pin=" + pin + "), no driver assigned yet");

            DriverAPI driverAPI = new DriverAPI(playwright, ConfigReader.driverApiBaseUrl(),
                    ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId());

            JsonNode accepted = driverAPI.acceptOrder(orderUuid);
            assertDriverStatus(accepted, "Accepted");
            System.out.println("[DRIVER] Order #" + orderNo + " -> Accepted (restaurant still Preparing)");

            JsonNode arrived = driverAPI.markArrived(orderUuid);
            assertDriverStatus(arrived, "Arrived");
            System.out.println("[DRIVER] Order #" + orderNo + " -> Arrived at restaurant (still Preparing, waiting)");

            // Re-open the order before acting on it: this page auto-refreshes
            // (CONFIRMED "Auto-refresh Active" badge) and can re-render the list/
            // detail panel out from under a stale reference while the driver
            // calls above were happening - a click that lands on a replaced
            // button never fires the update (see LiveOrdersPage.markReadyForPickup()).
            liveOrders.openTab("Preparing");
            liveOrders.openOrder(orderNo);
            JsonNode readyResponse = liveOrders.markReadyForPickup();
            System.out.println("[RESTAURANT] Order #" + orderNo + " -> " + statusFrom(readyResponse));
            adminContext.close();

            JsonNode pickedUp = driverAPI.markPickedUp(orderUuid, pin);
            assertDriverStatus(pickedUp, "PickedUp");
            System.out.println("[DRIVER] Order #" + orderNo + " -> PickedUp, heading to customer");

            JsonNode delivered = driverAPI.markDeliveredWithRandomProof(orderUuid);
            Assert.assertTrue(DriverAPI.isDelivered(delivered), "Expected order to be confirmed Delivered: " + delivered);
            System.out.println("[DRIVER] Order #" + orderNo + " -> Delivered (photo proof uploaded)");

            driverAPI.close();
            browser.close();
            System.out.println("[RESULT] SUCCESS: driver accepted mid-prep, restaurant readied after, driver completed delivery.");
            } catch (Throwable t) {
                System.out.println("[FAILED] driverAcceptsWhileStillPreparing: " + t.getClass().getSimpleName()
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

    private void assertDriverStatus(JsonNode response, String expectedStatus) {
        String status = response.at("/data/content/status").asText();
        Assert.assertEquals(status, expectedStatus, "Expected driver call to move order to " + expectedStatus);
    }

    /**
     * The restaurant-admin API's response envelope for markReadyForPickup was
     * never confirmed the way the driver-app one was (see DriverAPI) - try the
     * couple of shapes seen elsewhere in this app, else fall back to the raw
     * body so the real status is always what gets printed, not an assumption.
     */
    private String statusFrom(JsonNode response) {
        for (String pointer : new String[] {"/data/content/status", "/data/status", "/status"}) {
            JsonNode node = response.at(pointer);
            if (!node.isMissingNode() && !node.isNull()) {
                return node.asText();
            }
        }
        return "status field not found, raw response: " + response;
    }
}
