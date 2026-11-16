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
import foodAPIs.driverModule.DriverAPI;
import foodAPIs.driverModule.DriverChat;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.webrestaurantPages.WebRestaurantLogin;
import pages.WebPages.webrestaurantPages.LiveOrdersPage;
import pages.WebPages.webrestaurantPages.RestaurantPortalPage;
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.CheckoutPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import pages.WebPages.customerPages.OrderTrackingPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import utils.AdminCreatedRestaurant;
import utils.ConfigReader;
import utils.Preflight;
import utils.RestaurantActivationApi;
import utils.TestDataGenerator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;


public class AdminCreatedRestaurantOrderLifecycleTest {


    private static final String FOOD_INSTRUCTIONS = "No onions please, extra pickles on the side";
    private static final String DELIVERY_INSTRUCTIONS = "Please call on arrival, flat 4B - do not ring the bell";

    
    private static final double GULSHAN_1_LATITUDE = 23.7806;
    private static final double GULSHAN_1_LONGITUDE = 90.4166;

    @Test(description = "Admin creates restaurant+category+food -> customer orders from it (Special/Delivery "
            + "Instructions) -> restaurant accepts into Preparing -> driver accepts+arrives mid-prep, customer<->driver chat "
            + "-> restaurant marks Ready -> driver picks up and delivers")
    public void customerOrdersFromLatestAdminCreatedRestaurant() {
        // One clear list of anything missing (TestData.properties, working dir, Google key, mvn on PATH)
        // before the ~2 minute admin step starts, instead of failing one thing at a time deep into the run.
        Preflight.checkAdminCreatedRestaurantFlow();
        AdminCreatedRestaurant.CreatedRestaurant restaurant = createRestaurantCategoryAndFoodViaAdminPortal();
        System.out.println("[ADMIN] Restaurant: " + restaurant.name()
                + " (id=" + restaurant.restaurantId() + ") | Category: " + restaurant.category()
                + " | Food: " + restaurant.food());

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));
            DriverAPI driverAPI = new DriverAPI(playwright, ConfigReader.driverApiBaseUrl(),
                    ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId());

            try (DriverChat driverChat = new DriverChat(ConfigReader.driverAuthToken())) {
                ensureRestaurantIsAcceptingOrders(browser, restaurant);

                CustomerSession customer = placeCustomerOrder(browser, restaurant);
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
                        + " -> Preparing (pin=" + pin + "), no driver assigned yet");

                JsonNode accepted = driverAPI.acceptOrder(orderUuid);
                assertDriverStatus(accepted, "Accepted");
                System.out.println("[DRIVER] Order #" + orderNo + " -> Accepted (restaurant still Preparing)");

                JsonNode arrived = driverAPI.markArrived(orderUuid);
                assertDriverStatus(arrived, "Arrived");
                System.out.println("[DRIVER] Order #" + orderNo + " -> Arrived at restaurant (still Preparing, waiting)");

                // Same slot OrderLifecycleChatTest uses: driver is assigned (so the customer's
                // "Chat with Rider" button exists) but restaurant hasn't marked Ready yet.
                OrderTrackingPage tracking = customer.tracking();
                tracking.openRiderChat();
                System.out.println("[CUSTOMER] Opened chat with rider");
                customerSays(tracking, "Hi, how long will it take?");
                customerQuickReply(tracking, "Wait for few minutes");
                customerSays(tracking, "Please bring extra napkins");
                customerSays(tracking, "Thank you!");
                driverSays(driverChat, tracking, orderUuid, customer.customerId(), "ok");

                // Re-open the order before acting on it - this page auto-refreshes and can
                // re-render the list/detail panel out from under a stale reference while the
                // driver calls above were happening (see OrderLifecycleDriverInLoopTest).
                liveOrders.openTab("Preparing");
                liveOrders.openOrder(orderNo);
                JsonNode readyResponse = liveOrders.markReadyForPickup();
                System.out.println("[RESTAURANT] Order #" + orderNo + " -> " + statusFrom(readyResponse));
                adminContext.close();

                driverSays(driverChat, tracking, orderUuid, customer.customerId(), "I am coming");

                JsonNode pickedUp = driverAPI.markPickedUp(orderUuid, pin);
                assertDriverStatus(pickedUp, "PickedUp");
                System.out.println("[DRIVER] Order #" + orderNo + " -> PickedUp, heading to customer");

                JsonNode delivered = driverAPI.markDeliveredWithRandomProof(orderUuid);
                Assert.assertTrue(DriverAPI.isDelivered(delivered), "Expected order to be confirmed Delivered: " + delivered);
                System.out.println("[DRIVER] Order #" + orderNo + " -> Delivered (photo proof uploaded)");

                // Driver API response is the confirmed real requirement for both instruction
                // fields - see RestaurantAndDriverSeeCustomerInstructionsTest's own javadoc for
                // which party is actually supposed to see which one.
                String driverFoodInstruction = accepted.at("/data/content/itemDetails/0/foodInstruction").asText("");
                String driverDeliveryInstruction = accepted.at("/data/content/deliveryInstruction").asText("");
                Assert.assertEquals(driverFoodInstruction, FOOD_INSTRUCTIONS,
                        "Driver API response lost the customer's Special Instructions (itemDetails[].foodInstruction).");
                Assert.assertEquals(driverDeliveryInstruction, DELIVERY_INSTRUCTIONS,
                        "Driver API response lost the customer's Delivery Instructions (deliveryInstruction).");
                System.out.println("[DRIVER] Special Instructions + Delivery Instructions both confirmed in the driver API response");

                utils.MqttConsoleCapture.awaitStatus(orderNo, "Completed", 10);
                driverAPI.close();

                turnOffAcceptingOrdersAndVerifyClosedOnCustomerWeb(browser, restaurant);

                // Flow done -> switch the restaurant's ACTIVATION off via the super-admin API
                // (PATCH /services/admin/api/restaurant-activation, activated=false). Prints the reply.
                JsonNode deactivated = RestaurantActivationApi.deactivate(
                        playwright, browser, restaurant.restaurantId(), restaurant.name());
                System.out.println("[ADMIN-API] Activation OFF confirmed for " + restaurant.name()
                        + " (id=" + restaurant.restaurantId() + "): " + deactivated);

                browser.close();
                System.out.println("[RESULT] SUCCESS: " + restaurant.name() + " (" + restaurant.food()
                        + ") - admin -> customer (chat, Special/Delivery Instructions) -> restaurant "
                        + "(Preparing -> Ready) -> driver (Accepted -> Arrived -> PickedUp -> Delivered) -> "
                        + "restaurant turned OFF (customer-web closed-check disabled for now).");
            } catch (Throwable t) {
                System.out.println("[FAILED] customerOrdersFromLatestAdminCreatedRestaurant: " + t.getClass().getSimpleName()
                        + " - " + t.getMessage());
                // Not a bare `throw t;` (what the sibling tests without DriverChat use) - DriverChat's
                // try-with-resources close() declares `throws Exception`, so a precise rethrow of a
                // Throwable here doesn't compile. Same fix OrderLifecycleChatTest already uses.
                throw new RuntimeException(t);
            }
        }
    }

    
    private AdminCreatedRestaurant.CreatedRestaurant createRestaurantCategoryAndFoodViaAdminPortal() {
        System.out.println("[ADMIN] Running admin-portal-automation e2e suite (create restaurant -> category -> food)...");
        // Resolved by Preflight (PATH first, then MAVEN_HOME/M2_HOME) - an IDE often has no mvn on PATH.
        String mvnExecutable = Preflight.mavenExecutable();
        ProcessBuilder processBuilder = new ProcessBuilder(mvnExecutable, "clean", "test", "-DsuiteFile=e2e-testng.xml")
                .directory(new File("admin-portal-automation"))
                .redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            // Not inheritIO(): that piped the child straight to the real OS stdout, bypassing
            // ConsoleLogListener entirely - which is why its noisy "SLF4J: Failed to load class
            // StaticLoggerBinder..." lines showed up raw with no timestamp/colour. Read and
            // re-print through OUR System.out instead, so those specific lines can be dropped
            // and everything else still gets the same formatting as the rest of this test.
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
        return AdminCreatedRestaurant.latest();
    }

    
    private void waitForModalOverlayToClear(Page page, String backdropSelector, String label) {
        try {
            page.locator(backdropSelector).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN).setTimeout(8000));
        } catch (PlaywrightException stillThereAfter8s) {
            // CONFIRMED live (2026-11-01): removing the node outright (e.remove()) broke the
            // Angular app instead of fixing it - the search box never rendered again for the
            // rest of the run (0 matches, not even a retry/click pattern - Angular almost
            // certainly still held an internal reference to the node we deleted out from
            // under it). Only disable its hit-testing, leave the DOM node itself alone - still
            // unblocks every click underneath, without touching anything Angular tracks.
            System.out.println("[CUSTOMER] " + label + " backdrop stuck after 8s - disabling its clicks (CONFIRMED live Angular animation bug)");
            page.evaluate("(sel) => document.querySelectorAll(sel).forEach(e => e.style.pointerEvents = 'none')", backdropSelector);
        }
    }

    
    private void openRestaurantViaSearch(HomePage home, String restaurantName) {
        home.openRestaurantsTab();
        home.searchRestaurantsByName(restaurantName);
        System.out.println("[CUSTOMER] On Restaurants tab, searched \"" + restaurantName + "\"");
        home.openRestaurantFromSearchResults(restaurantName);
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

    private record CustomerSession(String orderNo, String customerId, OrderTrackingPage tracking) {
    }

    /**
     * After the order lifecycle is done: turns Accepting Orders OFF for this restaurant on the
     * restaurant-admin portal, re-confirmed with a fresh page reload (not just the toggle click's
     * own optimistic state). The customer-side "is it really shown as closed" check is currently
     * DISABLED below pending a live root-cause investigation - see that comment block.
     */
    private void turnOffAcceptingOrdersAndVerifyClosedOnCustomerWeb(Browser browser, AdminCreatedRestaurant.CreatedRestaurant restaurant) {
        BrowserContext adminContext = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(ConfigReader.restaurantAdminUrl())
                .setViewportSize(1440, 900));
        Page adminPage = adminContext.newPage();
        RestaurantPortalPage portal = new WebRestaurantLogin(adminPage)
                .loginWithEmailAndPassword(restaurant.email(), restaurant.password());
        portal.openLiveOrders();
        LiveOrdersPage liveOrders = new LiveOrdersPage(adminPage);
        liveOrders.setAcceptingOrders(false);
        // Not just trusting the toggle click's own optimistic client-side state - reload the
        // page and re-read the label fresh, so this is a real round trip to the backend
        // confirming the OFF state actually persisted, not just what the UI assumed right
        // after clicking.
        adminPage.reload();
        adminPage.waitForLoadState(LoadState.NETWORKIDLE);
        boolean confirmedOffAfterReload = !liveOrders.isAcceptingOrders();
        System.out.println("[RESTAURANT] " + restaurant.name() + " accepting-orders status: turned OFF (re-checked "
                + "after page reload: " + (confirmedOffAfterReload ? "confirmed OFF" : "STILL SHOWS ON") + ")");
        Assert.assertTrue(confirmedOffAfterReload,
                "Expected " + restaurant.name() + " to show Accepting Orders OFF after a fresh page reload");
        adminContext.close();

        // Disabled (2026-11-03, user instruction): the customer-side "really off" check below
        // failed on a real run ("CLOSED" badge not visible) even though the restaurant-portal
        // side above was freshly re-confirmed OFF. The listing page's "Unavailable" badge passed
        // first, which rules out a simple propagation-delay theory. Root cause not yet confirmed
        // live - see this method's git history/PR notes for the working theory (the listing
        // badge and the restaurant-page "CLOSED" badge may be driven by two different signals -
        // accepting-orders vs the delivery-area/geofence check that was the real cause of
        // unavailability earlier in this file, see GULSHAN_1_LATITUDE's javadoc). Needs a live
        // probe on a real admin-created restaurant before re-enabling, not a guess.
        // BrowserContext customerContext = browser.newContext(new Browser.NewContextOptions()
        //         .setViewportSize(1366, 768)
        //         .setGeolocation(new Geolocation(GULSHAN_1_LATITUDE, GULSHAN_1_LONGITUDE))
        //         .setPermissions(List.of("geolocation")));
        // Page customerPage = customerContext.newPage();
        // HomePage home = new HomePage(customerPage).open(ConfigReader.baseUrl());
        // home.openLogin();
        // new LoginPage(customerPage).loginWithPhoneAndOtp(TestDataGenerator.randomRiderPhone(), ConfigReader.testUserOtp());
        // home.setDeliveryLocationToCurrentLocation();
        // waitForModalOverlayToClear(customerPage, "div.fixed.inset-0", "Location modal");
        //
        // home.openRestaurantsTab();
        // home.searchRestaurantsByName(restaurant.name());
        // System.out.println("[CUSTOMER] Searched \"" + restaurant.name() + "\" after turning it off");
        //
        // boolean showsUnavailableOnListing = customerPage.locator("hopon-food-nearby-restaurant-card")
        //         .filter(new Locator.FilterOptions().setHasText(restaurant.name())).first()
        //         .getByText("Unavailable").isVisible();
        // System.out.println("[CUSTOMER] Listing card shows \"Unavailable\": " + showsUnavailableOnListing);
        // Assert.assertTrue(showsUnavailableOnListing,
        //         "Expected " + restaurant.name() + " to show \"Unavailable\" on the listing after turning Accepting Orders off");
        //
        // home.openRestaurantFromSearchResults(restaurant.name());
        // boolean showsClosedBadge = customerPage.getByText("CLOSED", new Page.GetByTextOptions().setExact(true)).isVisible();
        // System.out.println("[CUSTOMER] Restaurant page shows \"CLOSED\" badge: " + showsClosedBadge);
        // Assert.assertTrue(showsClosedBadge,
        //         "Expected a \"CLOSED\" badge on " + restaurant.name() + "'s page after turning Accepting Orders off");
        //
        // customerContext.close();
    }

    private CustomerSession placeCustomerOrder(Browser browser, AdminCreatedRestaurant.CreatedRestaurant restaurant) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1366, 768)
                // GULSHAN_1_LATITUDE/LONGITUDE, not ConfigReader.deliveryLatitude/Longitude() - see
                // that constant's javadoc for why the generic default falls outside every
                // admin-created restaurant's delivery area.
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
        // Real search, not an id shortcut - see openRestaurantViaSearch()'s javadoc.
        openRestaurantViaSearch(home, restaurant.name());
        System.out.println("[CUSTOMER] Opened " + restaurant.name());

        RestaurantMenuPage menuPage = new RestaurantMenuPage(page);
        Assert.assertTrue(menuPage.restaurantTitle().contains(restaurant.name()),
                "Expected to land on " + restaurant.name());

        // CONFIRMED live (2026-11-02): TestDataGenerator.randomRiderPhone() reuses a small
        // pool of numbers (DataSource/riderlist.csv), so an earlier run can leave stale items
        // from a DIFFERENT restaurant in this account's cart. Adding our item would then pop a
        // "Start a New Order?" modal, which RestaurantMenuPage.addItemToCartWithSize() auto-
        // confirms internally ("Yes, Start New Order") so the add itself doesn't hang - but
        // that happens silently. Check for it explicitly here first so it's visible in the
        // log instead of invisible, per the second screenshot in this task.
        CartPage cartPage = new CartPage(page);
        if (!cartPage.isEmpty()) {
            System.out.println("[CUSTOMER] Cart already has items from a previous session - adding "
                    + restaurant.food() + " will trigger \"Start a New Order?\" (auto-confirmed Yes, replacing it)");
        }

        menuPage.openCategoryTab(restaurant.category());
        // Real in-page search, not just relying on the category tab already showing it - see
        // RestaurantMenuPage.searchMenuByName()'s javadoc.
        menuPage.searchMenuByName(restaurant.food());
        System.out.println("[CUSTOMER] Searched restaurant's own menu for \"" + restaurant.food() + "\"");
        // Special Instructions (per-item modal) - RESTAURANT-facing, see
        // RestaurantAndDriverSeeCustomerInstructionsTest for which backend field this is and
        // who is actually supposed to see it.
        menuPage.addItemToCartWithSize(restaurant.food(), "L", FOOD_INSTRUCTIONS);
        waitForModalOverlayToClear(page, "hopon-food-detail-modal div.fixed.inset-0", "Item options modal");
        Assert.assertFalse(menuPage.isRestaurantUnavailable(), "Restaurant rejected order as unavailable");
        System.out.println("[CUSTOMER] Added to cart: " + restaurant.food() + " (L)");
        System.out.println("[CUSTOMER] Special Instructions (food item) written: \"" + FOOD_INSTRUCTIONS + "\"");

        Assert.assertTrue(cartPage.containsItem(restaurant.food()), "Expected item in basket");
        // Disabled (2026-11-02, user instruction): CartPage.couponStatus() ran at the wrong
        // point in the flow - returned "could not determine coupon panel state" and may have
        // been colliding with the item-detail modal's own close animation right before it.
        // Not deleted - couponStatus() itself stays in CartPage.java, just not called here
        // until the right place/timing for it is worked out.
        // System.out.println("[CUSTOMER] Coupon check: " + cartPage.couponStatus());

        CheckoutPage checkoutPage = cartPage.proceedToCheckout();
        // Delivery Instructions (checkout page) - DRIVER-facing only, by design (see
        // RestaurantAndDriverSeeCustomerInstructionsTest).
        checkoutPage.addOrderNotes(DELIVERY_INSTRUCTIONS);
        System.out.println("[CUSTOMER] Delivery Instructions (checkout page) written: \"" + DELIVERY_INSTRUCTIONS + "\"");
        checkoutPage.placeOrder();
        Assert.assertTrue(checkoutPage.isOrderConfirmed(), "Expected order confirmation");

        String orderNo = checkoutPage.orderId();
        Assert.assertNotNull(orderNo, "Expected a non-null order number on confirmation");

        // The chat sender id is the logged-in user's id - same value the web app uses as its
        // MQTT username (see OrderLifecycleChatTest).
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
    private void driverSays(DriverChat driverChat, OrderTrackingPage tracking, String orderUuid, String customerId, String text) {
        driverChat.send(orderUuid, customerId, text);
        System.out.println("[DRIVER] Chat sent: \"" + text + "\"");
        Assert.assertTrue(tracking.chatShows(text, 15),
                "Driver's chat message never reached the customer's chat panel: " + text);
        System.out.println("[CUSTOMER] Chat received from driver: \"" + text + "\"");
    }

    private void assertDriverStatus(JsonNode response, String expectedStatus) {
        String status = response.at("/data/content/status").asText();
        Assert.assertEquals(status, expectedStatus, "Expected driver call to move order to " + expectedStatus);
    }

    /** Same fallback as OrderLifecycleDriverInLoopTest: the restaurant-admin API's markReadyForPickup response shape wasn't CONFIRMED. */
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
