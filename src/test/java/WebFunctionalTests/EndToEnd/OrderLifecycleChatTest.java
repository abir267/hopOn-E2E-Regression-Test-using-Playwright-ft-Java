package WebFunctionalTests.EndToEnd;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.Geolocation;
import foodAPIs.driverModule.DriverAPI;
import foodAPIs.driverModule.DriverChat;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.CheckoutPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import pages.WebPages.customerPages.OrderTrackingPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import pages.WebPages.webrestaurantPages.LiveOrdersPage;
import pages.WebPages.webrestaurantPages.RestaurantPortalPage;
import pages.WebPages.webrestaurantPages.WebRestaurantLogin;
import utils.ConfigReader;
import utils.MqttConsoleCapture;
import utils.RetryAnalyzer;
import utils.TestDataGenerator;

import java.util.List;

/**
 * End-to-end order lifecycle WITH the customer <-> driver chat in the middle, in this order:
 * <ol>
 *   <li>customer places the order</li>
 *   <li>restaurant accepts it -> "Preparing" (NOT ready yet)</li>
 *   <li>driver accepts (gets assigned) -> the customer's "Chat with Rider" button appears</li>
 *   <li>customer sends 4 chat messages (typed + quick reply)</li>
 *   <li>driver answers "ok"</li>
 *   <li>restaurant marks "Ready for Pickup"</li>
 *   <li>driver says "I am coming"</li>
 *   <li>driver completes: Arrived -> PickedUp -> Delivered</li>
 * </ol>
 * The customer's chat goes through the real web UI; the driver has no UI, so the driver's replies are
 * published over MQTT exactly like the driver app does (see {@link DriverChat}). Each reply is then
 * asserted to show up in the customer's open chat panel. Like OrderLifecycleTest this manages its own
 * Playwright/Browser because it drives three systems at once.
 */
public class OrderLifecycleChatTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String RESTAURANT_NAME = "QA Burger Express [AA]";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";

    @Test(description = "Customer orders, restaurant prepares, driver assigned, customer<->driver chat, restaurant ready, driver delivers",
            retryAnalyzer = RetryAnalyzer.class)
    public void orderWithCustomerDriverChat() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));
            DriverAPI driverAPI = new DriverAPI(playwright, ConfigReader.driverApiBaseUrl(),
                    ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId());

            try (DriverChat driverChat = new DriverChat(ConfigReader.driverAuthToken())) {
                // 1. customer places the order and stays on the tracking page
                CustomerSession customer = placeCustomerOrder(browser);
                String orderNo = customer.orderNo();
                System.out.println("[CUSTOMER] Order placed: #" + orderNo);

                // 2. restaurant accepts -> Preparing (stays on the order for the later "Ready" click)
                RestaurantSession restaurant = acceptAsRestaurant(browser, orderNo);
                System.out.println("[RESTAURANT] Order #" + orderNo + " accepted -> Preparing (uuid="
                        + restaurant.orderUuid() + ")");

                // 3. driver gets assigned
                JsonNode accepted = driverAPI.acceptOrder(restaurant.orderUuid());
                Assert.assertEquals(accepted.at("/data/content/status").asText(), "Accepted",
                        "Expected driver call to move order to Accepted");
                System.out.println("[DRIVER] Order #" + orderNo + " -> Accepted (assigned to "
                        + accepted.at("/data/content/driverName").asText("driver") + ")");

                // 4. customer chats with the rider (3-4 messages)
                OrderTrackingPage tracking = customer.tracking();
                tracking.openRiderChat();
                System.out.println("[CUSTOMER] Opened chat with rider");
                customerSays(tracking, "Hi, how long will it take?");
                customerQuickReply(tracking, "Wait for few minutes");
                customerSays(tracking, "Please bring extra napkins");
                customerSays(tracking, "Thank you!");

                // 5. driver answers "ok"
                driverSays(driverChat, tracking, restaurant, customer, "ok");

                // 6. restaurant marks Ready for Pickup
                JsonNode ready = restaurant.liveOrders().markReadyForPickup();
                System.out.println("[RESTAURANT] Order #" + orderNo + " marked Ready for Pickup (status="
                        + ready.at("/data/content/status").asText("?") + ", pin=" + restaurant.pin() + ")");

                // 7. driver says "I am coming"
                driverSays(driverChat, tracking, restaurant, customer, "I am coming");

                // 8. driver completes the delivery
                JsonNode arrived = driverAPI.markArrived(restaurant.orderUuid());
                Assert.assertEquals(arrived.at("/data/content/status").asText(), "Arrived");
                System.out.println("[DRIVER] Order #" + orderNo + " -> Arrived at restaurant");

                JsonNode pickedUp = driverAPI.markPickedUp(restaurant.orderUuid(), restaurant.pin());
                Assert.assertEquals(pickedUp.at("/data/content/status").asText(), "PickedUp");
                System.out.println("[DRIVER] Order #" + orderNo + " -> PickedUp, heading to customer");

                JsonNode delivered = driverAPI.markDeliveredWithRandomProof(restaurant.orderUuid());
                Assert.assertTrue(DriverAPI.isDelivered(delivered), "Expected order to be confirmed Delivered: " + delivered);
                System.out.println("[DRIVER] Order #" + orderNo + " -> Delivered (photo proof uploaded)");

                MqttConsoleCapture.awaitStatus(orderNo, "Completed", 10);
                driverAPI.close();
                browser.close();
                System.out.println("[RESULT] SUCCESS: order -> preparing -> driver assigned -> chat (customer x4, driver ok) "
                        + "-> ready for pickup -> driver 'I am coming' -> delivered.");
            } catch (Throwable t) {
                System.out.println("[FAILED] orderWithCustomerDriverChat: " + t.getClass().getSimpleName()
                        + " - " + t.getMessage());
                throw new RuntimeException(t);
            }
        }
    }

    private void customerSays(OrderTrackingPage tracking, String text) {
        tracking.sendChatMessage(text);
        Assert.assertTrue(tracking.chatShows(text, 10), "Customer's message never appeared in the chat: " + text);
        System.out.println("[CUSTOMER] Chat sent: \"" + text + "\"");
    }

    private void customerQuickReply(OrderTrackingPage tracking, String label) {
        tracking.sendQuickReply(label);
        Assert.assertTrue(tracking.chatShows(label, 10), "Quick reply never appeared in the chat: " + label);
        System.out.println("[CUSTOMER] Chat sent (quick reply): \"" + label + "\"");
    }

    /** Driver publishes a chat message, then the customer's open chat panel must show it. */
    private void driverSays(DriverChat driverChat, OrderTrackingPage tracking, RestaurantSession restaurant,
                            CustomerSession customer, String text) {
        driverChat.send(restaurant.orderUuid(), customer.customerId(), text);
        System.out.println("[DRIVER] Chat sent: \"" + text + "\"");
        Assert.assertTrue(tracking.chatShows(text, 15),
                "Driver's chat message never reached the customer's chat panel: " + text);
        System.out.println("[CUSTOMER] Chat received from driver: \"" + text + "\"");
    }

    private record CustomerSession(String orderNo, String customerId, OrderTrackingPage tracking) {
    }

    private record RestaurantSession(String orderUuid, String pin, LiveOrdersPage liveOrders) {
    }

    private CustomerSession placeCustomerOrder(Browser browser) {
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

        // The chat sender id is the logged-in user's id: the same value the web app uses as its MQTT username.
        String customerId = null;
        for (Cookie cookie : context.cookies()) {
            if ("_ssl_mu".equals(cookie.name)) {
                customerId = cookie.value;
            }
        }
        Assert.assertNotNull(customerId, "Expected the _ssl_mu session cookie (customer user id) after login");

        OrderTrackingPage tracking = checkoutPage.trackOrder();
        return new CustomerSession(orderNo, customerId, tracking);
    }

    private RestaurantSession acceptAsRestaurant(Browser browser, String orderNo) {
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
        return new RestaurantSession(orderUuid, pin, liveOrders);
    }
}
