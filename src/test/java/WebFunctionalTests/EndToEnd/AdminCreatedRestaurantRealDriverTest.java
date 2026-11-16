package WebFunctionalTests.EndToEnd;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.MobilePages.DriverFoodApp;
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.CheckoutPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import pages.WebPages.customerPages.OrderTrackingPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import pages.WebPages.webrestaurantPages.LiveOrdersPage;
import pages.WebPages.webrestaurantPages.RestaurantPortalPage;
import pages.WebPages.webrestaurantPages.WebRestaurantLogin;
import utils.AdbDevice;
import utils.AdminCreatedRestaurant;
import utils.DriverAppLog;
import utils.ConfigReader;
import utils.Preflight;
import utils.RestaurantActivationApi;
import utils.TestDataGenerator;

import java.util.List;

/**
 * Same shape as {@link AdminCreatedRestaurantSuspendTest}: takes the restaurant admin-portal-automation most recently
 * created (AdminCreatedRestaurant.latest()) - it does NOT create one. The difference is the actor: the DRIVER is the real
 * Android app (com.hoponbd.driver) on the USB-attached phone, not DriverAPI/DriverChat.
 *
 *   restaurant switched ON + activation ON (web / admin API)  ->  customer orders (web)  ->  restaurant accepts into
 *   Preparing (web)  ->  phone: driver accepts, taps "Arrived at Restaurant"  ->  restaurant marks Ready (web)  ->
 *   phone: driver confirms pickup with the PIN, completes delivery with photo proof  ->  restaurant turned OFF + activation OFF.
 *
 * Run it after a lifecycle run (which leaves the restaurant activation-OFF - this test switches it back on) and BEFORE
 * the suspend test: a suspended restaurant cannot take orders and Unblock is not automated.
 *
 * Needs the phone attached with USB debugging authorized and the driver app logged in as the test driver; the app starts
 * offline after a cold launch and this test taps GO, and the phone's animation scales must not be 0 (the app's request
 * countdown is an animation - with animations off it cancels every request instantly). Customer <-> driver chat runs the
 * same way as the API-driver lifecycle, but the driver's side is typed and read in the real app.
 * Every phone step logs "[DRIVER-APP]"; a failed one saves a screenshot + text dump to target/driver-app-diagnostics/.
 */
public class AdminCreatedRestaurantRealDriverTest {

    private static final String FOOD_INSTRUCTIONS = "No onions please, extra pickles on the side";
    private static final String DELIVERY_INSTRUCTIONS = "Please call on arrival, flat 4B - do not ring the bell";

    /** See AdminCreatedRestaurantOrderLifecycleTest.GULSHAN_1_LATITUDE: the generic default is outside every admin-created restaurant's delivery area. */
    private static final double GULSHAN_1_LATITUDE = 23.7806;
    private static final double GULSHAN_1_LONGITUDE = 90.4166;

    private static final long ACCEPT_TIMEOUT_MS = 3 * 60_000L;
    private static final long DELIVERY_TIMEOUT_MS = 3 * 60_000L;

    @Test(description = "Customer orders from the most recently admin-created restaurant; the REAL driver app on the phone accepts, "
            + "arrives, picks up with the PIN and delivers")
    public void deliverLatestAdminCreatedRestaurantOrderWithRealDriverApp() {
        Preflight.checkRealDriverOrderFlow();
        AdminCreatedRestaurant.CreatedRestaurant restaurant = AdminCreatedRestaurant.latest();
        System.out.println("[ADMIN] Target restaurant: " + restaurant.name() + " (id=" + restaurant.restaurantId()
                + ") | Category: " + restaurant.category() + " | Food: " + restaurant.food());

        // Before any order exists, so a phone that is locked / on the wrong screen fails here, not mid-order.
        AdbDevice adb = new AdbDevice();
        DriverFoodApp driverApp = new DriverFoodApp(adb);
        driverApp.openAndGoOnline();
        // The driver's MQTT traffic and HTTP errors, read from the phone's own log (no browser page exists for it) and
        // printed/saved like the web roles ([MQTT] ... DRIVER, [BUG][DRIVER_APP] ...).
        DriverAppLog driverMqtt = DriverAppLog.start(adb, ConfigReader.driverAppPackage());

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));
            try {
                // The lifecycle test leaves activation OFF when it finishes; ordering needs it ON.
                RestaurantActivationApi.setActivated(playwright, browser, restaurant.restaurantId(), restaurant.name(), true);
                ensureRestaurantIsAcceptingOrders(browser, restaurant);

                CustomerOrder customer = placeCustomerOrder(browser, restaurant);
                String orderNo = customer.orderNo();
                System.out.println("[CUSTOMER] Order placed: #" + orderNo);

                BrowserContext adminContext = browser.newContext(new Browser.NewContextOptions()
                        .setBaseURL(ConfigReader.restaurantAdminUrl())
                        .setViewportSize(1440, 900));
                Page adminPage = adminContext.newPage();
                RestaurantPortalPage restaurantPortal = new WebRestaurantLogin(adminPage)
                        .loginWithEmailAndPassword(restaurant.email(), restaurant.password());
                restaurantPortal.openLiveOrders();
                LiveOrdersPage liveOrders = new LiveOrdersPage(adminPage);

                String orderUuid = liveOrders.orderUuidFor(orderNo);
                Assert.assertNotNull(orderUuid, "Expected to find order " + orderNo + " in the on-going orders list");

                // The platform now often puts a new order straight into Preparing (the customer's modal reads
                // "Preparing your food" at once), so only accept it when it is really waiting in the New tab.
                boolean acceptedByRestaurant = liveOrders.acceptIfNew(orderNo);
                String pin = liveOrders.pickupPin();
                System.out.println("[RESTAURANT] Order #" + orderNo + (acceptedByRestaurant ? " accepted" : " was already Preparing (auto-accepted)")
                        + " -> Preparing (pin=" + pin + "), waiting for the driver app");

                driverApp.acceptRequest(ACCEPT_TIMEOUT_MS);
                System.out.println("[DRIVER-APP] Order #" + orderNo + " -> Accepted on the phone (restaurant still Preparing)");

                // What the driver app received over MQTT, independent of what its screen shows: the offer for THIS order,
                // carrying the customer's Delivery Instructions (the API-driver lifecycle asserts the same on its accept response).
                Assert.assertTrue(driverMqtt.awaitStatus(orderNo, "Pending", 10),
                        "The driver app accepted order " + orderNo + " but its MQTT log shows no Pending offer for it");
                Assert.assertEquals(driverMqtt.firstValue(orderNo, "/deliveryInstruction"), DELIVERY_INSTRUCTIONS,
                        "The driver's MQTT offer lost the customer's Delivery Instructions (deliveryInstruction).");
                System.out.println("[DRIVER-MQTT] Offer for #" + orderNo + " reached the driver app over MQTT with the Delivery Instructions");

                driverApp.markArrivedAtRestaurant();
                System.out.println("[DRIVER-APP] Order #" + orderNo + " -> Arrived at restaurant on the phone");

                // Same slot the API-driver lifecycle uses: a driver is assigned (so the customer's "Chat with Rider"
                // exists) but the restaurant has not marked Ready yet. Customer types on the web, the driver app on
                // the phone must show it and answer.
                OrderTrackingPage tracking = customer.tracking();
                tracking.openRiderChat();
                System.out.println("[CUSTOMER] Opened chat with rider");
                customerSays(tracking, "Hi, how long will it take?");
                customerQuickReply(tracking, "Wait for few minutes");
                customerSays(tracking, "Please bring extra napkins");
                customerSays(tracking, "Thank you!");
                driverApp.openChat();
                for (String fromCustomer : new String[] {"Hi, how long will it take?", "Wait for few minutes",
                        "Please bring extra napkins", "Thank you!"}) {
                    Assert.assertTrue(driverApp.chatShows(fromCustomer, 20_000),
                            "Customer's chat message never reached the driver app on the phone: " + fromCustomer);
                    System.out.println("[DRIVER-APP] Chat received from customer: \"" + fromCustomer + "\"");
                }
                driverSays(driverApp, tracking, "ok");
                driverApp.closeChat();

                // Re-open the order before acting on it - the page auto-refreshes and can re-render the
                // list/detail panel out from under a stale reference while the phone steps were running.
                liveOrders.openTab("Preparing");
                liveOrders.openOrder(orderNo);
                JsonNode readyResponse = liveOrders.markReadyForPickup();
                System.out.println("[RESTAURANT] Order #" + orderNo + " -> " + statusFrom(readyResponse));
                adminContext.close();

                driverApp.openChat();
                driverSays(driverApp, tracking, "I am coming");
                driverApp.closeChat();

                driverApp.confirmPickup(pin);
                System.out.println("[DRIVER-APP] Order #" + orderNo + " -> PickedUp on the phone (PIN " + pin + "), heading to customer");

                driverApp.completeDelivery(DELIVERY_TIMEOUT_MS);
                System.out.println("[DRIVER-APP] Order #" + orderNo + " -> Delivered on the phone (photo proof)");

                // The phone showing the home screen again is only what the app believes. The real proof is the
                // backend pushing "Completed" to the customer - an earlier version of this step passed on the
                // completion screen's title while the order was still Delivering.
                utils.MqttConsoleCapture.awaitStatus(orderNo, "Completed", 20);
                Assert.assertTrue(utils.MqttConsoleCapture.sawStatus(orderNo, "Completed"),
                        "Driver app finished on the phone, but order " + orderNo + " never reached Completed (no Completed push to the customer within 20s)");

                System.out.println("[DRIVER-MQTT] Driver app received a Completed push for #" + orderNo + ": "
                        + driverMqtt.awaitStatus(orderNo, "Completed", 10));

                turnOffAcceptingOrders(browser, restaurant);
                JsonNode deactivated = RestaurantActivationApi.deactivate(
                        playwright, browser, restaurant.restaurantId(), restaurant.name());
                System.out.println("[ADMIN-API] Activation OFF confirmed for " + restaurant.name()
                        + " (id=" + restaurant.restaurantId() + "): " + deactivated);

                browser.close();
                System.out.println("[RESULT] SUCCESS: " + restaurant.name() + " (" + restaurant.food()
                        + ") - customer (Track My Order, chat) -> restaurant (Preparing -> Ready) -> REAL driver app "
                        + "(Accepted -> Arrived -> chat both ways -> PickedUp -> Delivered, backend Completed) -> restaurant turned OFF.");
            } catch (Throwable t) {
                System.out.println("[FAILED] deliverLatestAdminCreatedRestaurantOrderWithRealDriverApp: "
                        + t.getClass().getSimpleName() + " - " + t.getMessage());
                throw t;
            }
        } finally {
            driverMqtt.close();
        }
    }

    // ------------------------------------------------------------------ web steps (same as the API-driver lifecycle)

    private void waitForModalOverlayToClear(Page page, String backdropSelector, String label) {
        try {
            page.locator(backdropSelector).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN).setTimeout(8000));
        } catch (PlaywrightException stillThereAfter8s) {
            // Same CONFIRMED Angular animation bug handled in the API-driver lifecycle: only disable hit-testing,
            // never remove the node (removing it broke the app for the rest of the run).
            System.out.println("[CUSTOMER] " + label + " backdrop stuck after 8s - disabling its clicks");
            page.evaluate("(sel) => document.querySelectorAll(sel).forEach(e => e.style.pointerEvents = 'none')", backdropSelector);
        }
    }

    private void ensureRestaurantIsAcceptingOrders(Browser browser, AdminCreatedRestaurant.CreatedRestaurant restaurant) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(ConfigReader.restaurantAdminUrl())
                .setViewportSize(1440, 900));
        Page page = context.newPage();
        RestaurantPortalPage portal = new WebRestaurantLogin(page)
                .loginWithEmailAndPassword(restaurant.email(), restaurant.password());
        portal.openLiveOrders();
        LiveOrdersPage liveOrders = new LiveOrdersPage(page);
        boolean wasAccepting = liveOrders.isAcceptingOrders();
        liveOrders.setAcceptingOrders(true);
        System.out.println("[RESTAURANT] " + restaurant.name() + " accepting-orders status: "
                + (wasAccepting ? "already ON" : "was OFF, turned ON"));
        context.close();
    }

    private record CustomerOrder(String orderNo, String customerId, OrderTrackingPage tracking) {
    }

    private void turnOffAcceptingOrders(Browser browser, AdminCreatedRestaurant.CreatedRestaurant restaurant) {
        BrowserContext adminContext = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(ConfigReader.restaurantAdminUrl())
                .setViewportSize(1440, 900));
        Page adminPage = adminContext.newPage();
        RestaurantPortalPage portal = new WebRestaurantLogin(adminPage)
                .loginWithEmailAndPassword(restaurant.email(), restaurant.password());
        portal.openLiveOrders();
        LiveOrdersPage liveOrders = new LiveOrdersPage(adminPage);
        liveOrders.setAcceptingOrders(false);
        // Reload and re-read so this is a real backend round trip, not the toggle's optimistic state.
        adminPage.reload();
        adminPage.waitForLoadState(LoadState.NETWORKIDLE);
        boolean confirmedOffAfterReload = !liveOrders.isAcceptingOrders();
        System.out.println("[RESTAURANT] " + restaurant.name() + " accepting-orders status: turned OFF (re-checked "
                + "after page reload: " + (confirmedOffAfterReload ? "confirmed OFF" : "STILL SHOWS ON") + ")");
        Assert.assertTrue(confirmedOffAfterReload,
                "Expected " + restaurant.name() + " to show Accepting Orders OFF after a fresh page reload");
        adminContext.close();
    }

    private CustomerOrder placeCustomerOrder(Browser browser, AdminCreatedRestaurant.CreatedRestaurant restaurant) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1366, 768)
                .setGeolocation(new Geolocation(GULSHAN_1_LATITUDE, GULSHAN_1_LONGITUDE))
                .setPermissions(List.of("geolocation")));
        Page page = context.newPage();

        HomePage home = new HomePage(page).open(ConfigReader.baseUrl());
        home.openLogin();
        String phone = TestDataGenerator.randomRiderPhone();
        new LoginPage(page).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
        Assert.assertTrue(home.isLoggedIn(), "Customer must be logged in before ordering");
        System.out.println("[CUSTOMER] Logged in as " + phone);

        home.setDeliveryLocationToCurrentLocation();
        waitForModalOverlayToClear(page, "div.fixed.inset-0", "Location modal");
        home.openRestaurantsTab();
        home.searchRestaurantsByName(restaurant.name());
        home.openRestaurantFromSearchResults(restaurant.name());
        System.out.println("[CUSTOMER] Opened " + restaurant.name());

        RestaurantMenuPage menuPage = new RestaurantMenuPage(page);
        Assert.assertTrue(menuPage.restaurantTitle().contains(restaurant.name()),
                "Expected to land on " + restaurant.name());

        CartPage cartPage = new CartPage(page);
        if (!cartPage.isEmpty()) {
            System.out.println("[CUSTOMER] Cart already has items from a previous session - adding "
                    + restaurant.food() + " will trigger \"Start a New Order?\" (auto-confirmed Yes, replacing it)");
        }

        menuPage.openCategoryTab(restaurant.category());
        menuPage.searchMenuByName(restaurant.food());
        menuPage.addItemToCartWithSize(restaurant.food(), "L", FOOD_INSTRUCTIONS);
        waitForModalOverlayToClear(page, "hopon-food-detail-modal div.fixed.inset-0", "Item options modal");
        Assert.assertFalse(menuPage.isRestaurantUnavailable(), "Restaurant rejected order as unavailable");
        System.out.println("[CUSTOMER] Added to cart: " + restaurant.food() + " (L)");

        Assert.assertTrue(cartPage.containsItem(restaurant.food()), "Expected item in basket");

        CheckoutPage checkoutPage = cartPage.proceedToCheckout();
        checkoutPage.addOrderNotes(DELIVERY_INSTRUCTIONS);
        checkoutPage.placeOrder();
        Assert.assertTrue(checkoutPage.isOrderConfirmed(), "Expected order confirmation");

        String orderNo = checkoutPage.orderId();
        Assert.assertNotNull(orderNo, "Expected a non-null order number on confirmation");

        String customerId = null;
        for (Cookie cookie : context.cookies()) {
            if ("_ssl_mu".equals(cookie.name)) {
                customerId = cookie.value;
            }
        }
        Assert.assertNotNull(customerId, "Expected the _ssl_mu session cookie (customer user id) after login");
        // "Track My Order" on the confirmation modal: the customer's tracking page is where Chat with Rider lives.
        OrderTrackingPage tracking = checkoutPage.trackOrder();
        return new CustomerOrder(orderNo, customerId, tracking);
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

    /** The driver types in the REAL app on the phone; the customer's open chat panel on the web must show it. */
    private void driverSays(DriverFoodApp driverApp, OrderTrackingPage tracking, String text) {
        driverApp.sendChat(text);
        Assert.assertTrue(tracking.chatShows(text, 15),
                "Driver's chat message never reached the customer's chat panel: " + text);
        System.out.println("[CUSTOMER] Chat received from driver: \"" + text + "\"");
    }

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
