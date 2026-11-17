package WebFunctionalTests.EndToEnd;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hopon.pages.CreateRestaurantPage;
import com.hopon.pages.MenuManagementPage;
import com.hopon.utils.CategoryData;
import com.hopon.utils.GoogleSheetsUtil;
import com.hopon.utils.RestaurantApi;
import com.hopon.utils.RestaurantData;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import foodAPIs.driverModule.DriverAPI;
import foodAPIs.driverModule.DriverChat;
import org.testng.Assert;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import pages.WebPages.adminPortalPages.AdminPortalLoginPage;
import pages.WebPages.adminPortalPages.RestaurantOverviewPage;
import pages.WebPages.adminPortalPages.RestaurantsListPage;
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.CheckoutPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import pages.WebPages.customerPages.OrderTrackingPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import pages.WebPages.webrestaurantPages.LiveOrdersPage;
import pages.WebPages.webrestaurantPages.RestaurantPortalPage;
import pages.WebPages.webrestaurantPages.WebRestaurantLogin;
import utils.BugWatcher;
import utils.ConfigReader;
import utils.TestDataGenerator;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole story in ONE browser: admin creates a restaurant, the restaurant adds a category and food, a customer
 * orders, the restaurant and the driver complete the order, the restaurant turns Accepting Orders OFF and the admin
 * API turns activation OFF.
 *
 * Each role gets its own window (opened the first time it is needed) and logs in once. The windows stay open, so
 * nobody signs out and back in, and the customer's tracking page and chat stay live the whole time.
 *
 * No retries: a retry would create a second restaurant. A failure prints one line saying which phase and step
 * broke, the page address and a screenshot path. If any phase breaks, the restaurant is still turned OFF through
 * the admin API at the end.
 *
 * Options (add to the mvn command): -Dheadless=true   -Dslowmo.ms=400
 */
public class FullEndToEndSingleBrowserTest {

    private static final String FOOD_INSTRUCTIONS = "No onions please, extra pickles on the side";
    private static final String DELIVERY_INSTRUCTIONS = "Please call on arrival, flat 4B - do not ring the bell";

    // Gulshan 1: the same point the admin map flow and the customer delivery area use
    private static final double GULSHAN_1_LATITUDE = 23.7806;
    private static final double GULSHAN_1_LONGITUDE = 90.4166;

    public static class NoRetry implements IRetryAnalyzer {
        @Override
        public boolean retry(ITestResult result) {
            return false;
        }
    }

    private Playwright playwright;
    private Browser browser;
    private BrowserContext adminContext;
    private BrowserContext restaurantContext;
    private BrowserContext customerContext;
    private Page adminPage;
    private Page restaurantPage;
    private Page customerPage;
    private RestaurantApi restaurantApi;
    private DriverAPI driverAPI;
    private DriverChat driverChat;

    // Shared between the phases
    private RestaurantData restaurantData;
    private String restaurantId;
    private int sheetRow;
    private CategoryData categoryData;
    private String foodName;
    private RestaurantPortalPage portal;
    private LiveOrdersPage liveOrders;
    private String orderNo;
    private String orderUuid;
    private String pin;
    private String customerId;
    private OrderTrackingPage tracking;
    private JsonNode acceptedByDriver;
    private static final String BLOCK_REASON = "Automated test - restaurant no longer needed";

    private BrowserContext suspendContext;
    private Page suspendPage;
    private boolean suspended = false;   // true once the restaurant has been suspended in the admin portal
    private boolean activationTurnedOff = false;   // true once the restaurant has been turned OFF through the API

    @BeforeClass(alwaysRun = true)
    public void startBrowser() {
        preflight();
        playwright = Playwright.create();
        BrowserType browserType = switch (ConfigReader.browser().toLowerCase()) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> playwright.chromium();
        };
        // Slow motion is OFF here (TestData.properties has 400 ms per action, which makes a long run much longer)
        double slowMo = Double.parseDouble(System.getProperty("slowmo.ms", "0"));
        boolean headless = System.getProperty("headless") != null
                ? Boolean.getBoolean("headless") : ConfigReader.headless();
        browser = browserType.launch(new BrowserType.LaunchOptions().setHeadless(headless).setSlowMo(slowMo));

        driverAPI = new DriverAPI(playwright, ConfigReader.driverApiBaseUrl(),
                ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId());
    }

    @AfterClass(alwaysRun = true)
    public void closeEverything() {
        cleanUpRestaurantIfRunStoppedEarly();                 // first, while Playwright is still open

        try {
            if (driverChat != null) {
                driverChat.close();
            }
        } catch (Exception ignored) {
            // closing only
        }
        try {
            if (driverAPI != null) {
                driverAPI.close();
            }
        } catch (Exception ignored) {
            // closing only
        }
        for (BrowserContext window : new BrowserContext[] {adminContext, restaurantContext, customerContext, suspendContext}) {
            try {
                if (window != null) {
                    window.close();
                }
            } catch (RuntimeException ignored) {
                // closing only
            }
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    /** Safety net: if any phase broke before the normal "activation OFF", turn the restaurant OFF through the admin API. */
    private void turnRestaurantOffIfRunStoppedEarly() {
        if (restaurantData == null || activationTurnedOff || restaurantApi == null) {
            return;                                           // nothing was created, or phase 11 already did it
        }
        System.out.println("[CLEANUP] The run stopped before the restaurant was turned OFF - turning "
                + restaurantData.name() + " OFF through the admin API");
        if (restaurantId == null) {
            System.out.println("[CLEANUP] The restaurant ID was never read, so it can't be turned OFF automatically. "
                    + "Turn off " + restaurantData.name() + " (" + restaurantData.email() + ") by hand");
            return;
        }
        try {
            JsonObject reply = restaurantApi.deactivateRestaurant(restaurantId);
            activationTurnedOff = true;
            System.out.println("[CLEANUP] " + restaurantData.name() + " (id=" + restaurantId + ") is now OFF: " + reply);
        } catch (RuntimeException e) {
            System.out.println("[CLEANUP] COULD NOT turn " + restaurantData.name() + " OFF: " + e.getMessage()
                    + " -> turn it OFF by hand (id=" + restaurantId + ")");
        }
    }

    // ---------------------------------------------------------------- Phase 1: admin creates the restaurant

    @Test(retryAnalyzer = NoRetry.class,
            description = "Admin logs in and creates the restaurant, then verifies and activates it through the APIs")
    public void phase1_adminCreatesRestaurant() {
        step("ADMIN", "Open the admin window", this::openAdminWindow);
        com.hopon.pages.LoginPage adminLogin = new com.hopon.pages.LoginPage(adminPage);
        CreateRestaurantPage create = new CreateRestaurantPage(adminPage);
        GoogleSheetsUtil sheet = GoogleSheetsUtil.getInstance();

        stepWithNetworkRetry("ADMIN", "Log in as admin", adminLogin::loginAsAdmin);
        step("ADMIN", "Switch to Hop on Food", create::switchToHopOnFood);
        step("ADMIN", "Open the Restaurants list", create::openRestaurants);
        step("ADMIN", "Click Add Restaurant", create::clickAddRestaurant);

        step("ADMIN", "Read the next restaurant number from the sheet",
                () -> restaurantData = RestaurantData.forNumber(sheet.nextBotNumber()));
        System.out.println("[ADMIN] Creating " + restaurantData.name() + " (" + restaurantData.email() + ")");

        step("ADMIN", "Fill business info", () -> create.fillBusinessInfo(restaurantData));
        step("ADMIN", "Pick the current location on the map", create::pickCurrentLocation);
        step("ADMIN", "Select the operation zone",
                () -> create.selectOperationZone("gulshan", "Hopon Food [Gulshan-banani]"));
        step("ADMIN", "Set the minimum prep time", () -> create.setMinPrepTime(30));
        step("ADMIN", "Upload the logo", create::uploadLogo);
        step("ADMIN", "Upload the cover", create::uploadCover);
        step("ADMIN", "Activate the store status", create::activateStoreStatus);
        step("ADMIN", "Upload the BIN document", create::uploadBinDocument);
        step("ADMIN", "Upload the TIN document", create::uploadTinDocument);
        step("ADMIN", "Upload the Trade Licence document", create::uploadTradeLicenceDocument);
        step("ADMIN", "Upload the Food Safety document", create::uploadFoodSafetyDocument);
        step("ADMIN", "Upload the Owner NID (front and back)", create::uploadOwnerNid);
        step("ADMIN", "Check the entered name and address", () -> {
            Assert.assertEquals(create.getEnteredRestaurantName(), restaurantData.name());
            Assert.assertFalse(create.getEnteredAddress().isBlank(), "Business address was not filled from the map");
        });
        step("ADMIN", "Click Continue", create::clickContinue);

        restaurantId = restaurantApi.waitForRestaurantId();
        System.out.println("[ADMIN] Restaurant ID: " + restaurantId);
        // The row is saved even if the ID was not found, so the next run still moves on to the next number
        final String idForSheet = restaurantId == null ? "" : restaurantId;
        step("ADMIN", "Save the restaurant to the sheet", () -> sheet.appendCredential(
                restaurantData.name(), restaurantData.email(), restaurantData.password(), idForSheet));
        Assert.assertNotNull(restaurantId, "FAILED in ADMIN at step 'Read the restaurant ID': the page address did not "
                + "change to /restaurant/edit/<id> after Continue | page: " + adminPage.url());
        step("ADMIN", "Read the restaurant's sheet row", () -> sheetRow = sheet.lastCredential().row());

        step("ADMIN", "Select the cuisine", () -> create.selectCuisine("Bangladeshi"));
        step("ADMIN", "Apply Monday hours to all days", create::applyMondayHoursToAllDays);
        step("ADMIN", "Update cuisines and hours", create::clickUpdateCuisinesAndHours);
        step("ADMIN", "Select the bank", () -> create.selectBank("Sonali Bank PLC"));
        step("ADMIN", "Fill the account details", () -> create.fillAccountDetails("Abir", "1234567890"));
        step("ADMIN", "Click Add Banking Details", create::clickUpdateBankingDetails);
        step("ADMIN", "Set the order limits and fees", () -> create.setupOrderLimitsAndFees("99", "15"));
        step("ADMIN", "Open Manage Area", create::openManageArea);
        step("ADMIN", "Go to the current location on the delivery map", create::clickCurrentLocationOnDeliveryMap);
        step("ADMIN", "Draw the delivery area", create::drawDeliveryPolygon);
        step("ADMIN", "Save the delivery area", create::saveDeliveryArea);
        step("ADMIN", "Click Submit", create::clickSubmit);

        JsonObject[] saved = new JsonObject[1];
        step("ADMIN-API", "Verify the restaurant", () -> saved[0] = restaurantApi.verifyRestaurant(restaurantId));
        step("ADMIN-API", "Activate the restaurant", () -> restaurantApi.activateRestaurant(restaurantId));
        step("ADMIN-API", "Check the saved restaurant against what was entered", () -> checkSavedRestaurant(saved[0]));
        System.out.println("[ADMIN] " + restaurantData.name() + " created, verified and activated");
    }

    // ---------------------------------------------------------------- Phase 2: the restaurant adds category and food

    @Test(dependsOnMethods = "phase1_adminCreatesRestaurant", retryAnalyzer = NoRetry.class,
            description = "The new restaurant logs in and adds a category and a food, then Accepting Orders is confirmed ON")
    public void phase2_restaurantAddsCategoryAndFood() {
        step("RESTAURANT", "Open the restaurant window", this::openRestaurantWindow);
        MenuManagementPage menu = new MenuManagementPage(restaurantPage);
        GoogleSheetsUtil sheet = GoogleSheetsUtil.getInstance();

        stepWithNetworkRetry("RESTAURANT", "Log in as " + restaurantData.name(), () ->
                portal = new WebRestaurantLogin(restaurantPage).loginWithEmailAndPassword(
                        restaurantData.email(), restaurantData.password()));

        step("RESTAURANT", "Read the next category number from the sheet",
                () -> categoryData = CategoryData.forNumber(sheet.nextCategoryNumber()));
        step("RESTAURANT", "Open Menu Management", menu::openMenuManagement);
        step("RESTAURANT", "Click + on Categories", menu::openAddCategory);
        step("RESTAURANT", "Fill the category details", () -> menu.fillCategory(categoryData));
        step("RESTAURANT", "Create the category", () -> menu.createCategory(categoryData.nameEn()));
        step("RESTAURANT", "Save the category to the sheet", () -> sheet.saveCategory(sheetRow, categoryData.nameEn()));

        step("RESTAURANT", "Read the next food number from the sheet",
                () -> foodName = "Cheese Automated Burger " + sheet.nextFoodNumber());
        System.out.println("[RESTAURANT] Category: " + categoryData.nameEn() + " | Food: " + foodName);
        step("RESTAURANT", "Open Menu Management", menu::openMenuManagement);
        step("RESTAURANT", "Click Add Food Item", menu::clickAddFoodItem);
        step("RESTAURANT", "Enter the food name", () -> menu.enterFoodName(foodName));
        step("RESTAURANT", "Select the category", () -> menu.selectFoodCategory(categoryData.nameEn()));
        step("RESTAURANT", "Select the food type", () -> menu.selectFoodType("Burger"));
        step("RESTAURANT", "Enter the description", () -> menu.enterFoodDescription("Test Automation"));
        step("RESTAURANT", "Enable variation", menu::enableVariation);
        step("RESTAURANT", "Enter the variation name", () -> menu.setVariationName("Size"));
        step("RESTAURANT", "Fill portion L", () -> menu.fillPortion(0, "L", "350"));
        step("RESTAURANT", "Add a portion", menu::addPortion);
        step("RESTAURANT", "Fill portion M", () -> menu.fillPortion(1, "M", "250"));
        step("RESTAURANT", "Upload the food photo", menu::uploadFoodPhoto);
        step("RESTAURANT", "Save and publish", menu::saveAndPublish);
        step("RESTAURANT", "Save the food to the sheet", () -> sheet.saveFood(sheetRow, foodName));

        step("RESTAURANT", "Open Live Orders", () -> {
            portal.openLiveOrders();
            liveOrders = new LiveOrdersPage(restaurantPage);
        });
        step("RESTAURANT", "Make sure Accepting Orders is ON", () -> {
            boolean wasAccepting = liveOrders.isAcceptingOrders();
            liveOrders.setAcceptingOrders(true);
            System.out.println("[RESTAURANT] " + restaurantData.name() + " accepting-orders status: "
                    + (wasAccepting ? "already ON" : "was OFF, turned ON"));
        });
    }

    // ---------------------------------------------------------------- Phase 3: the customer orders

    @Test(dependsOnMethods = "phase2_restaurantAddsCategoryAndFood", retryAnalyzer = NoRetry.class,
            description = "A customer orders the new food, with Special and Delivery Instructions")
    public void phase3_customerPlacesOrder() {
        step("CUSTOMER", "Open the customer window", this::openCustomerWindow);
        HomePage home = new HomePage(customerPage);
        RestaurantMenuPage menuPage = new RestaurantMenuPage(customerPage);
        CartPage cartPage = new CartPage(customerPage);
        CheckoutPage[] checkout = new CheckoutPage[1];

        String phone = TestDataGenerator.randomRiderPhone();
        step("CUSTOMER", "Open the customer site and log in as " + phone, () -> {
            home.open(ConfigReader.baseUrl());
            home.openLogin();
            new LoginPage(customerPage).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
            Assert.assertTrue(home.isLoggedIn(), "Customer must be logged in before ordering");
        });
        step("CUSTOMER", "Set the delivery location to the current location", () -> {
            home.setDeliveryLocationToCurrentLocation();
            waitForModalOverlayToClear("div.fixed.inset-0", "Location modal");
        });
        step("CUSTOMER", "Find and open " + restaurantData.name(), () -> {
            openRestaurantsTabTolerant(home);
            home.searchRestaurantsByName(restaurantData.name());
            tolerantCustomerNavigation("/restaurant?id=", "The restaurant page", () -> home.openRestaurantFromSearchResults(restaurantData.name()));
            Assert.assertTrue(menuPage.restaurantTitle().contains(restaurantData.name()),
                    "Expected to land on " + restaurantData.name());
        });
        step("CUSTOMER", "Add " + foodName + " (L) to the cart", () -> {
            if (!cartPage.isEmpty()) {
                System.out.println("[CUSTOMER] Cart already has items from a previous session - adding " + foodName
                        + " will trigger \"Start a New Order?\" (auto-confirmed Yes, replacing it)");
            }
            menuPage.openCategoryTab(categoryData.nameEn());
            menuPage.searchMenuByName(foodName);
            menuPage.addItemToCartWithSize(foodName, "L", FOOD_INSTRUCTIONS);
            waitForModalOverlayToClear("hopon-food-detail-modal div.fixed.inset-0", "Item options modal");
            Assert.assertFalse(menuPage.isRestaurantUnavailable(), "Restaurant rejected the order as unavailable");
            Assert.assertTrue(cartPage.containsItem(foodName), "Expected the item in the basket");
            System.out.println("[CUSTOMER] Special Instructions written: \"" + FOOD_INSTRUCTIONS + "\"");
        });
        step("CUSTOMER", "Check out with Delivery Instructions", () -> {
            checkout[0] = cartPage.proceedToCheckout();
            checkout[0].addOrderNotes(DELIVERY_INSTRUCTIONS);
            checkout[0].placeOrder();
            Assert.assertTrue(checkout[0].isOrderConfirmed(), "Expected the order confirmation");
            orderNo = checkout[0].orderId();
            Assert.assertNotNull(orderNo, "Expected a non-null order number on confirmation");
            System.out.println("[CUSTOMER] Order placed: #" + orderNo);
        });
        step("CUSTOMER", "Open order tracking", () -> {
            // The chat sender id is the logged-in user's id: the web app's own MQTT username
            for (Cookie cookie : customerContext.cookies()) {
                if ("_ssl_mu".equals(cookie.name)) {
                    customerId = cookie.value;
                }
            }
            Assert.assertNotNull(customerId, "Expected the _ssl_mu session cookie (customer user id) after login");
            tracking = checkout[0].trackOrder();
        });
    }

    // ---------------------------------------------------------------- Phase 4: the restaurant accepts

    @Test(dependsOnMethods = "phase3_customerPlacesOrder", retryAnalyzer = NoRetry.class,
            description = "The restaurant accepts the order into Preparing")
    public void phase4_restaurantAcceptsOrder() {
        step("RESTAURANT", "Back to the restaurant window", () -> show(restaurantPage));
        step("RESTAURANT", "Open Live Orders", () -> {
            portal.openLiveOrders();
            liveOrders = new LiveOrdersPage(restaurantPage);
        });
        step("RESTAURANT", "Find order #" + orderNo, () -> {
            orderUuid = liveOrders.orderUuidFor(orderNo);
            Assert.assertNotNull(orderUuid, "Expected to find order " + orderNo + " in the on-going orders list");
        });
        step("RESTAURANT", "Accept the order into Preparing", () -> {
            // The platform may put a new order straight into Preparing, so accept only if it waits in the New tab
            boolean accepted = liveOrders.acceptIfNew(orderNo);
            pin = liveOrders.pickupPin();
            System.out.println("[RESTAURANT] Order #" + orderNo + (accepted ? " accepted" : " was already Preparing (auto-accepted)")
                    + " -> Preparing (pin=" + pin + "), no driver assigned yet");
        });
    }

    // ---------------------------------------------------------------- Phase 5: the driver accepts and arrives

    @Test(dependsOnMethods = "phase4_restaurantAcceptsOrder", retryAnalyzer = NoRetry.class,
            description = "The driver accepts and arrives while the restaurant is still Preparing (driver API)")
    public void phase5_driverAcceptsAndArrives() {
        step("DRIVER", "Accept the order", () -> {
            acceptedByDriver = driverAPI.acceptOrder(orderUuid);
            assertDriverStatus(acceptedByDriver, "Accepted");
            System.out.println("[DRIVER] Order #" + orderNo + " -> Accepted (restaurant still Preparing)");
        });
        step("DRIVER", "Mark Arrived at the restaurant", () -> {
            assertDriverStatus(driverAPI.markArrived(orderUuid), "Arrived");
            System.out.println("[DRIVER] Order #" + orderNo + " -> Arrived at restaurant (still Preparing, waiting)");
        });
    }

    // ---------------------------------------------------------------- Phase 6: the customer chats with the driver

    @Test(dependsOnMethods = "phase5_driverAcceptsAndArrives", retryAnalyzer = NoRetry.class,
            description = "The customer chats four messages, the driver replies ok")
    public void phase6_customerChatsWithDriver() {
        step("CUSTOMER", "Back to the customer window", () -> show(customerPage));
        step("DRIVER", "Connect the driver chat", () -> {
            try {
                driverChat = new DriverChat(ConfigReader.driverAuthToken());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        step("CUSTOMER", "Open the chat with the rider", tracking::openRiderChat);
        customerSays("Hi, how long will it take?");
        customerQuickReply("Wait for few minutes");
        customerSays("Please bring extra napkins");
        customerSays("Thank you!");
        driverSays("ok");
    }

    // ---------------------------------------------------------------- Phase 7: the restaurant marks Ready

    @Test(dependsOnMethods = "phase6_customerChatsWithDriver", retryAnalyzer = NoRetry.class,
            description = "The restaurant marks the order Ready for Pickup")
    public void phase7_restaurantMarksReady() {
        step("RESTAURANT", "Back to the restaurant window", () -> show(restaurantPage));
        step("RESTAURANT", "Open Live Orders", () -> {
            portal.openLiveOrders();
            liveOrders = new LiveOrdersPage(restaurantPage);
        });
        step("RESTAURANT", "Mark order #" + orderNo + " Ready for Pickup", () -> {
            // Re-open the order first: this page auto-refreshes and can re-render the list under a stale reference
            liveOrders.openTab("Preparing");
            liveOrders.openOrder(orderNo);
            JsonNode readyResponse = liveOrders.markReadyForPickup();
            System.out.println("[RESTAURANT] Order #" + orderNo + " -> " + statusFrom(readyResponse));
        });
    }

    // ---------------------------------------------------------------- Phase 8: the customer sees "I am coming"

    @Test(dependsOnMethods = "phase7_restaurantMarksReady", retryAnalyzer = NoRetry.class,
            description = "The driver's I am coming message arrives live in the customer's open chat")
    public void phase8_customerSeesDriverComing() {
        step("CUSTOMER", "Back to the customer window", () -> show(customerPage));
        driverSays("I am coming");
    }

    // ---------------------------------------------------------------- Phase 9: the driver delivers

    @Test(dependsOnMethods = "phase8_customerSeesDriverComing", retryAnalyzer = NoRetry.class,
            description = "The driver picks up and delivers (driver API), and the instruction notes are checked")
    public void phase9_driverPicksUpAndDelivers() {
        step("DRIVER", "Mark PickedUp", () -> {
            assertDriverStatus(driverAPI.markPickedUp(orderUuid, pin), "PickedUp");
            System.out.println("[DRIVER] Order #" + orderNo + " -> PickedUp, heading to customer");
        });
        step("DRIVER", "Mark Delivered with photo proof", () -> {
            JsonNode delivered = driverAPI.markDeliveredWithRandomProof(orderUuid);
            Assert.assertTrue(DriverAPI.isDelivered(delivered), "Expected the order to be confirmed Delivered: " + delivered);
            System.out.println("[DRIVER] Order #" + orderNo + " -> Delivered (photo proof uploaded)");
        });
        step("DRIVER", "Check both instruction notes in the driver API reply", () -> {
            Assert.assertEquals(acceptedByDriver.at("/data/content/itemDetails/0/foodInstruction").asText(""),
                    FOOD_INSTRUCTIONS, "Driver API reply lost the customer's Special Instructions");
            Assert.assertEquals(acceptedByDriver.at("/data/content/deliveryInstruction").asText(""),
                    DELIVERY_INSTRUCTIONS, "Driver API reply lost the customer's Delivery Instructions");
            System.out.println("[DRIVER] Special Instructions + Delivery Instructions both confirmed in the driver API response");
        });
    }

    // ---------------------------------------------------------------- Phase 10: Accepting Orders OFF

    @Test(dependsOnMethods = "phase9_driverPicksUpAndDelivers", retryAnalyzer = NoRetry.class,
            description = "The restaurant turns Accepting Orders OFF")
    public void phase10_restaurantTurnsAcceptingOrdersOff() {
        step("RESTAURANT", "Back to the restaurant window", () -> show(restaurantPage));
        step("RESTAURANT", "Open Live Orders", () -> {
            portal.openLiveOrders();
            liveOrders = new LiveOrdersPage(restaurantPage);
        });
        step("RESTAURANT", "Turn Accepting Orders OFF and re-check after a reload", () -> {
            liveOrders.setAcceptingOrders(false);
            restaurantPage.reload();
            restaurantPage.waitForLoadState(LoadState.NETWORKIDLE);
            boolean confirmedOff = !liveOrders.isAcceptingOrders();
            System.out.println("[RESTAURANT] " + restaurantData.name() + " accepting-orders status: turned OFF (re-checked "
                    + "after page reload: " + (confirmedOff ? "confirmed OFF" : "STILL SHOWS ON") + ")");
            Assert.assertTrue(confirmedOff, "Expected Accepting Orders to be OFF after a fresh page reload");
        });
    }

    // ---------------------------------------------------------------- Phase 11: activation OFF

    @Test(dependsOnMethods = "phase10_restaurantTurnsAcceptingOrdersOff", retryAnalyzer = NoRetry.class,
            description = "The restaurant's activation is turned OFF through the super admin API")
    public void phase11_activationTurnedOff() {
        step("ADMIN-API", "Turn the restaurant's activation OFF", () -> {
            System.out.println("[ADMIN-API] DEACTIVATE restaurant " + restaurantData.name() + " (id=" + restaurantId + ")");
            JsonObject reply = restaurantApi.deactivateRestaurant(restaurantId);
            activationTurnedOff = true;                       // the safety net then has nothing left to do
            System.out.println("[ADMIN-API] Activation OFF confirmed for " + restaurantData.name()
                    + " (id=" + restaurantId + "): " + reply);
        });
        System.out.println("[RESULT] SUCCESS: " + restaurantData.name() + " (" + foodName + ") - admin created -> restaurant "
                + "added category and food -> customer ordered -> restaurant (Preparing -> Ready) -> driver (Accepted -> "
                + "Arrived -> PickedUp -> Delivered) -> Accepting Orders OFF -> activation OFF. (Admin suspend: next phase.)");
    }

    // ---------------------------------------------------------------- Windows

    // ---------------------------------------------------------------- Phase 12: admin suspends the restaurant

    @Test(dependsOnMethods = "phase11_activationTurnedOff", retryAnalyzer = NoRetry.class,
            description = "The admin suspends the restaurant from the Hop On Admin Management portal")
    public void phase12_adminSuspendsRestaurant() {
        suspendRestaurantThroughAdminPortal();
        System.out.println("[RESULT] SUCCESS: " + restaurantData.name() + " suspended via the admin portal (verified after reload)");
    }

    /** Suspends the restaurant in the Hop On Admin Management portal, in its own fresh window. Used by phase 12 and the safety net. */
    private void suspendRestaurantThroughAdminPortal() {
        step("ADMIN-PORTAL", "Open the admin portal window", this::openSuspendWindow);
        RestaurantsListPage[] list = new RestaurantsListPage[1];
        RestaurantOverviewPage[] overview = new RestaurantOverviewPage[1];

        stepWithNetworkRetry("ADMIN-PORTAL", "Log in to the Hop On Admin Management portal", () ->
                list[0] = new AdminPortalLoginPage(suspendPage).loginWithEmailAndPassword(
                        ConfigReader.adminPortalEmail(), ConfigReader.adminPortalPassword()));
        step("ADMIN-PORTAL", "Open " + restaurantData.name() + " from the Restaurants list", () ->
                overview[0] = list[0].openRestaurantOverview(restaurantId));
        step("ADMIN-PORTAL", "Suspend the account", () -> {
            boolean alreadySuspended = overview[0].isSuspended();
            System.out.println("[ADMIN-PORTAL] " + restaurantData.name() + " already suspended before: " + alreadySuspended);
            if (!alreadySuspended) {
                overview[0].suspendAccount(BLOCK_REASON);
                System.out.println("[ADMIN-PORTAL] Submitted Suspend Account (Block Reason=\"" + BLOCK_REASON + "\", Block Status=ON)");
            }
        });
        step("ADMIN-PORTAL", "Check it is suspended after a reload", () -> {
            suspendPage.reload();
            boolean suspendedAfter = overview[0].isSuspended();
            System.out.println("[ADMIN-PORTAL] Suspended after reload (Unblock action shown): " + suspendedAfter);
            Assert.assertTrue(suspendedAfter, "Expected " + restaurantData.name()
                    + " to show the Unblock action (i.e. be suspended) after a fresh reload");
            suspended = true;
        });
    }

    private void openSuspendWindow() {
        if (suspendPage == null) {
            suspendContext = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 900));
            suspendContext.setDefaultTimeout(ConfigReader.defaultTimeout());
            suspendPage = suspendContext.newPage();
            BugWatcher.attach(suspendPage);
        }
    }

    /**
     * Safety net, runs after every run. If any phase broke, the restaurant is turned OFF through the admin API and
     * suspended in the admin portal. Never throws, so the original failure stays the one you see.
     */
    private void cleanUpRestaurantIfRunStoppedEarly() {
        if (restaurantData == null || (activationTurnedOff && suspended)) {
            return;                                           // nothing was created, or everything was already done
        }
        System.out.println("[CLEANUP] The run did not finish - making sure " + restaurantData.name()
                + " is turned OFF and suspended");
        if (restaurantId == null) {
            System.out.println("[CLEANUP] The restaurant ID was never read, so it can't be handled automatically. "
                    + "Turn off and suspend " + restaurantData.name() + " (" + restaurantData.email() + ") by hand");
            return;
        }
        if (!activationTurnedOff && restaurantApi != null) {
            try {
                JsonObject reply = restaurantApi.deactivateRestaurant(restaurantId);
                activationTurnedOff = true;
                System.out.println("[CLEANUP] " + restaurantData.name() + " (id=" + restaurantId + ") is now OFF: " + reply);
            } catch (RuntimeException e) {
                System.out.println("[CLEANUP] COULD NOT turn " + restaurantData.name() + " OFF: " + e.getMessage()
                        + " -> turn it OFF by hand (id=" + restaurantId + ")");
            }
        }
        if (!suspended) {
            try {
                suspendRestaurantThroughAdminPortal();
                System.out.println("[CLEANUP] " + restaurantData.name() + " (id=" + restaurantId + ") is now SUSPENDED");
            } catch (Throwable t) {
                System.out.println("[CLEANUP] COULD NOT suspend " + restaurantData.name() + ": " + shortCause(t)
                        + " -> suspend it by hand (id=" + restaurantId + ")");
            }
        }
    }
    private BrowserContext newWindow(String baseUrl, int width, int height) {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setViewportSize(width, height)
                .setGeolocation(new Geolocation(GULSHAN_1_LATITUDE, GULSHAN_1_LONGITUDE))
                .setPermissions(List.of("geolocation"));
        if (baseUrl != null) {
            options.setBaseURL(baseUrl);
        }
        BrowserContext window = browser.newContext(options);
        window.setDefaultTimeout(ConfigReader.defaultTimeout());
        return window;
    }

    private Page openAdminWindow() {
        if (adminPage == null) {
            adminContext = newWindow(ConfigReader.restaurantAdminUrl(), 1440, 900);
            adminPage = adminContext.newPage();
            BugWatcher.attach(adminPage);
            restaurantApi = new RestaurantApi(adminPage, playwright);   // starts watching for the super admin's token
        }
        return adminPage;
    }

    private Page openRestaurantWindow() {
        if (restaurantPage == null) {
            restaurantContext = newWindow(ConfigReader.restaurantAdminUrl(), 1440, 900);
            restaurantPage = restaurantContext.newPage();
            BugWatcher.attach(restaurantPage);
        }
        return restaurantPage;
    }

    private Page openCustomerWindow() {
        if (customerPage == null) {
            customerContext = newWindow(null, 1366, 768);
            useTinyPlaceholderForRestaurantImages(customerContext);
            customerPage = customerContext.newPage();
            BugWatcher.attach(customerPage);
        }
        return customerPage;
    }

    /** Brings a window to the front so you can follow the run. Does nothing when running headless. */
    private void show(Page window) {
        try {
            window.bringToFront();
        } catch (RuntimeException ignored) {
            // only for watching
        }
    }

    // ---------------------------------------------------------------- Helpers

    /**
     * Opens the Restaurants tab. The customer site's Restaurants page keeps loading images and data, so the page's own
     * "wait until the network is quiet" can time out even though the tab is already showing. In that case carry on.
     */
    private void openRestaurantsTabTolerant(HomePage home) {
        customerPage.setDefaultTimeout(10000);               // do not spend 30 s on a page that is already showing
        try {
            home.openRestaurantsTab();
        } catch (PlaywrightException e) {
            if (!customerPage.url().contains("/restaurants")) {
                throw e;                                     // the tab really did not open
            }
            System.out.println("[CUSTOMER] The Restaurants page is showing but did not go quiet within 10 s - carrying on");
        } finally {
            customerPage.setDefaultTimeout(ConfigReader.defaultTimeout());
        }
    }
    // A 1x1 transparent PNG, used in place of the restaurant pictures
    private static final byte[] TINY_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    /**
     * The customer site lists every restaurant with its logo, and every test restaurant uploaded a large picture, so the
     * pages can take a very long time to finish loading. The test does not need the pictures, so the customer window
     * gets a tiny placeholder instead. Add -Dblock.images=false to load the real pictures.
     */
    private void useTinyPlaceholderForRestaurantImages(BrowserContext window) {
        if ("false".equalsIgnoreCase(System.getProperty("block.images"))) {
            return;
        }
        window.route(url -> url.contains("dev-content.hoponbd.com"), route -> {
            if ("image".equals(route.request().resourceType())) {
                route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("image/png").setBodyBytes(TINY_PNG));
            } else {
                route.resume();
            }
        });
    }

    /**
     * Runs a customer-site navigation with a 10 second limit. These pages keep loading in the background, so the page's own
     * "wait until loaded" can time out even though the right page is already showing. If the address is right, carry on.
     */
    private void tolerantCustomerNavigation(String urlMustContain, String what, Runnable action) {
        customerPage.setDefaultTimeout(10000);
        try {
            action.run();
        } catch (PlaywrightException e) {
            if (!customerPage.url().contains(urlMustContain)) {
                throw e;                                     // the page really did not open
            }
            System.out.println("[CUSTOMER] " + what + " is showing but the page did not go quiet within 10 s - carrying on");
        } finally {
            customerPage.setDefaultTimeout(ConfigReader.defaultTimeout());
        }
    }
    /** Runs one step. A failure says which phase and step broke, the page address, what happened and a screenshot. */
    private void step(String phase, String name, Runnable action) {
        System.out.println("[" + phase + "] " + name);
        try {
            action.run();
        } catch (Throwable t) {
            throw failure(phase, name, 1, t);
        }
    }

    /**
     * Like step(), but if the site did not load (connection timeout), waits 10 seconds and tries again, up to 3 times.
     * Only for steps that create nothing, such as logins, so a retry can never duplicate data.
     */
    private void stepWithNetworkRetry(String phase, String name, Runnable action) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            System.out.println("[" + phase + "] " + name + (attempt > 1 ? " (attempt " + attempt + " of 3)" : ""));
            try {
                action.run();
                return;
            } catch (Throwable t) {
                boolean siteDidNotLoad = currentUrl(phase).startsWith("chrome-error:")
                        || String.valueOf(t.getMessage()).contains("ERR_");
                if (!siteDidNotLoad || attempt == 3) {
                    throw failure(phase, name, attempt, t);
                }
                System.out.println("[" + phase + "] The site did not load (" + shortCause(t)
                        + "). Waiting 10 seconds, then trying again");
                sleepQuietly(10000);
            }
        }
    }

    private AssertionError failure(String phase, String name, int attempts, Throwable t) {
        String screenshot = takeScreenshot(phase, name);
        return new AssertionError("FAILED in " + phase + " at step '" + name + "' | page: " + currentUrl(phase)
                + (attempts > 1 ? " | attempts: " + attempts : "") + " | what happened: " + shortCause(t)
                + " | screenshot: " + screenshot, t);
    }

    private void customerSays(String text) {
        step("CUSTOMER", "Send chat message: " + text, () -> {
            tracking.sendChatMessage(text);
            Assert.assertTrue(tracking.chatShows(text, 10), "Customer's message never appeared in the chat: " + text);
            System.out.println("[CUSTOMER] Chat sent: \"" + text + "\"");
        });
    }

    private void customerQuickReply(String label) {
        step("CUSTOMER", "Send quick reply: " + label, () -> {
            tracking.sendQuickReply(label);
            Assert.assertTrue(tracking.chatShows(label, 10), "Quick reply never appeared in the chat: " + label);
            System.out.println("[CUSTOMER] Chat sent (quick reply): \"" + label + "\"");
        });
    }

    /** The driver publishes a chat message, then the customer's open chat panel must show it. */
    private void driverSays(String text) {
        step("DRIVER", "Send chat message: " + text, () -> {
            driverChat.send(orderUuid, customerId, text);
            System.out.println("[DRIVER] Chat sent: \"" + text + "\"");
            Assert.assertTrue(tracking.chatShows(text, 15),
                    "Driver's chat message never reached the customer's chat panel: " + text);
            System.out.println("[CUSTOMER] Chat received from driver: \"" + text + "\"");
        });
    }

    private void waitForModalOverlayToClear(String backdropSelector, String label) {
        try {
            customerPage.locator(backdropSelector).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN).setTimeout(8000));
        } catch (PlaywrightException stillThereAfter8s) {
            // The same live Angular animation bug the existing lifecycle test works around: only disable its clicks
            System.out.println("[CUSTOMER] " + label + " backdrop stuck after 8s - disabling its clicks (known live Angular animation bug)");
            customerPage.evaluate("(sel) => document.querySelectorAll(sel).forEach(e => e.style.pointerEvents = 'none')", backdropSelector);
        }
    }

    private void checkSavedRestaurant(JsonObject restaurant) {
        Assert.assertEquals(restaurant.get("verificationStatus").getAsString(), "VERIFIED", "Verification status");
        Assert.assertEquals(restaurant.get("nameEn").getAsString(), restaurantData.name(), "Restaurant name");
        Assert.assertEquals(restaurant.get("ownerName").getAsString(), restaurantData.owner(), "Owner name");
        Assert.assertEquals(restaurant.get("email").getAsString(), restaurantData.email(), "Email");
        Assert.assertEquals(restaurant.get("phone").getAsString(), restaurantData.phone(), "Phone");
        Assert.assertEquals(restaurant.getAsJsonObject("operationZone").get("name").getAsString(),
                "Hopon Food [Gulshan-banani]", "Operation zone");
        Assert.assertEquals(restaurant.get("expectedPreparationTimeInMin").getAsInt(), 30, "Minimum prep time");
        Assert.assertTrue(hasCuisine(restaurant, "Bangladeshi"), "Cuisine Bangladeshi was not saved");
        Assert.assertTrue(hasPaymentAccount(restaurant, "1234567890", "Abir"), "Bank account was not saved");
        Assert.assertEquals(restaurant.get("minOrderAmount").getAsDouble(), 99.0, 0.001, "Minimum order amount");
        Assert.assertEquals(restaurant.get("vatRatePercent").getAsDouble(), 15.0, 0.001, "VAT rate");
        Assert.assertTrue(restaurant.has("deliveryZone") && !restaurant.get("deliveryZone").isJsonNull(),
                "Delivery area was not saved");
    }

    private static boolean hasCuisine(JsonObject restaurant, String cuisine) {
        for (JsonElement item : restaurant.getAsJsonArray("cuisines")) {
            if (cuisine.equals(item.getAsJsonObject().get("nameEn").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPaymentAccount(JsonObject restaurant, String accountNo, String accountName) {
        JsonElement accounts = restaurant.get("paymentAccounts");
        if (accounts == null || !accounts.isJsonArray()) {
            return false;
        }
        for (JsonElement item : accounts.getAsJsonArray()) {
            JsonObject account = item.getAsJsonObject();
            if (accountNo.equals(account.get("accountNo").getAsString())
                    && accountName.equals(account.get("accountName").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private void assertDriverStatus(JsonNode response, String expectedStatus) {
        String status = response.at("/data/content/status").asText();
        Assert.assertEquals(status, expectedStatus, "Expected the driver call to move the order to " + expectedStatus);
    }

    /** The restaurant-admin API's markReadyForPickup response shape isn't confirmed, so try the known places. */
    private String statusFrom(JsonNode response) {
        for (String pointer : new String[] {"/data/content/status", "/data/status", "/status"}) {
            JsonNode node = response.at(pointer);
            if (!node.isMissingNode() && !node.isNull()) {
                return node.asText();
            }
        }
        return "status field not found, raw response: " + response;
    }

    /** The window that belongs to a phase (the driver and customer steps show the customer window). May be null. */
    private Page pageFor(String phase) {
        return switch (phase) {
            case "ADMIN", "ADMIN-API" -> adminPage;
            case "RESTAURANT" -> restaurantPage;
            case "ADMIN-PORTAL" -> suspendPage;
            default -> customerPage != null ? customerPage : (restaurantPage != null ? restaurantPage : adminPage);
        };
    }

    private String currentUrl(String phase) {
        try {
            Page window = pageFor(phase);
            return window == null ? "(no window yet)" : window.url();
        } catch (RuntimeException e) {
            return "(unknown)";
        }
    }

    private String takeScreenshot(String phase, String name) {
        try {
            Page window = pageFor(phase);
            if (window == null) {
                return "(no window to photograph)";
            }
            Path path = Paths.get("target", "test-artifacts",
                    (phase + "_" + name).replaceAll("[^A-Za-z0-9_-]", "_") + ".png");
            window.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true));
            return path.toString();
        } catch (RuntimeException e) {
            return "(not taken: " + e.getMessage() + ")";
        }
    }

    private static String shortCause(Throwable t) {
        String text = String.valueOf(t.getMessage());
        String first = text.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst()
                .orElse(t.getClass().getSimpleName());
        String waiting = text.lines().map(String::trim).filter(l -> l.contains("waiting for")).findFirst().orElse("");
        String result = t.getClass().getSimpleName() + ": " + first + (waiting.isEmpty() ? "" : " (" + waiting + ")");
        return result.length() > 400 ? result.substring(0, 400) + "..." : result;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One list of everything missing, before the long run starts. */
    private void preflight() {
        List<String> problems = new ArrayList<>();
        try {
            File key = new File(com.hopon.config.ConfigReader.get("google.credentials.path"));
            if (!key.exists()) {
                problems.add("Google key file not found: " + key.getAbsolutePath()
                        + " (google.credentials.path in admin-config.properties)");
            }
            for (String name : List.of(com.hopon.config.ConfigReader.get("file.logo"),
                    com.hopon.config.ConfigReader.get("file.cover"), com.hopon.config.ConfigReader.get("file.doc"),
                    com.hopon.config.ConfigReader.getOrDefault("file.food", "testfood.jpg"))) {
                File image = new File("src/test/resources/files", name);
                if (!image.exists()) {
                    problems.add("Upload image missing: " + image.getAbsolutePath());
                }
            }
        } catch (RuntimeException e) {
            problems.add("admin-config.properties problem: " + e.getMessage());
        }
        String token = ConfigReader.driverAuthToken();
        if (token == null || token.isBlank() || token.contains("CHANGE_ME")) {
            problems.add("driver.auth.token in TestData.properties is not set");
        }
        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder("[PREFLIGHT] " + problems.size()
                    + " problem(s) - fix these before running:");
            for (int i = 0; i < problems.size(); i++) {
                message.append("\n  ").append(i + 1).append(". ").append(problems.get(i));
            }
            throw new IllegalStateException(message.toString());
        }
    }
}
