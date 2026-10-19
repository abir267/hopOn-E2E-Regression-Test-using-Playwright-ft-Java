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
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.CheckoutPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
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
 * End-to-end: the customer writes a per-item "Special Instructions" note (the menu item's
 * "Choose options" modal, e.g. "no mayo/olives") AND a separate checkout-page "Delivery
 * Instructions" note (e.g. "call on arrival") - then the full flow confirms whether the
 * restaurant and the driver actually SEE what they're each supposed to, printing every stage
 * to the terminal so a missing one is obvious at a glance rather than something you have to go
 * hunting for.
 *
 * CONFIRMED live (2026-10-19) which backend field each box writes to, and who is actually
 * supposed to see each one:
 *   - "Special Instructions" (item modal)   -> itemDetails[].foodInstruction
 *       -> RESTAURANT-facing: shown under the order panel's "Customer note" accordion
 *          (LiveOrdersPage.customerNoteText()). Also present in the driver-app API response.
 *   - "Delivery Instructions" (checkout page) -> top-level deliveryInstruction
 *       -> DRIVER-facing only, BY DESIGN - the restaurant does not need it and CONFIRMED it is
 *          not shown anywhere in the restaurant-admin order panel; that is the correct, expected
 *          behaviour, not a gap. Present in the driver-app API response, which is what this test
 *          treats as the real requirement for that field.
 * "[ISSUE]" is only ever printed for something that's actually wrong (a field the right party
 * was supposed to see but didn't) - see each printed line below for what was expected vs what
 * was actually seen.
 */
public class RestaurantAndDriverSeeCustomerInstructionsTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String RESTAURANT_NAME = "QA Burger Express [AA]";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";
    private static final String FOOD_INSTRUCTIONS = "No onions please, extra pickles on the side";
    private static final String DELIVERY_INSTRUCTIONS = "Please call on arrival, flat 4B - do not ring the bell";

    @Test(description = "Customer writes item Special Instructions + checkout Delivery Instructions; "
            + "confirm what the restaurant and driver actually see for each",
            retryAnalyzer = RetryAnalyzer.class)
    public void restaurantAndDriverSeeCustomerInstructions() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));
            try {
                boolean anyIssue = false;

                // ---- 1. customer: write both instructions, place the order --------------------
                BrowserContext custContext = browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(1366, 768)
                        .setGeolocation(new Geolocation(ConfigReader.deliveryLatitude(), ConfigReader.deliveryLongitude()))
                        .setPermissions(List.of("geolocation")));
                Page custPage = custContext.newPage();

                HomePage home = new HomePage(custPage).open(ConfigReader.baseUrl());
                home.openLogin();
                String phone = TestDataGenerator.randomRiderPhone();
                new LoginPage(custPage).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
                Assert.assertTrue(home.isLoggedIn(), "Customer must be logged in before ordering");
                System.out.println("[CUSTOMER] Logged in as " + phone);

                home.setDeliveryLocationToCurrentLocation();
                home.openRestaurantById(ConfigReader.baseUrl(), RESTAURANT_ID);
                RestaurantMenuPage menuPage = new RestaurantMenuPage(custPage);
                Assert.assertTrue(menuPage.restaurantTitle().contains(RESTAURANT_NAME),
                        "Expected to land on " + RESTAURANT_NAME);

                menuPage.addItemToCartWithSize(MENU_ITEM_NAME, MENU_ITEM_SIZE, FOOD_INSTRUCTIONS);
                Assert.assertFalse(menuPage.isRestaurantUnavailable(), "Restaurant rejected order as unavailable");
                System.out.println("[CUSTOMER] Added to cart: " + MENU_ITEM_NAME + " (" + MENU_ITEM_SIZE + ")");
                System.out.println("[CUSTOMER] Special Instructions (food item) written: \"" + FOOD_INSTRUCTIONS + "\"");

                CartPage cartPage = new CartPage(custPage);
                Assert.assertTrue(cartPage.containsItem(MENU_ITEM_NAME), "Expected item in basket");

                CheckoutPage checkoutPage = cartPage.proceedToCheckout();
                checkoutPage.addOrderNotes(DELIVERY_INSTRUCTIONS);
                System.out.println("[CUSTOMER] Delivery Instructions (checkout page) written: \"" + DELIVERY_INSTRUCTIONS + "\"");
                checkoutPage.placeOrder();
                Assert.assertTrue(checkoutPage.isOrderConfirmed(), "Expected order confirmation");
                String orderNo = checkoutPage.orderId();
                Assert.assertNotNull(orderNo, "Expected a non-null order number on confirmation");
                System.out.println("[CUSTOMER] Order placed: #" + orderNo);

                // ---- 2. restaurant: accept, then check what its UI actually shows --------------
                BrowserContext adminContext = browser.newContext(new Browser.NewContextOptions()
                        .setBaseURL(ConfigReader.restaurantAdminUrl())
                        .setViewportSize(1440, 900));
                Page adminPage = adminContext.newPage();

                RestaurantPortalPage restaurant = new WebRestaurantLogin(adminPage)
                        .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());
                restaurant.openLiveOrders();
                LiveOrdersPage liveOrders = new LiveOrdersPage(adminPage);
                liveOrders.openTab("New");
                liveOrders.openOrder(orderNo);
                liveOrders.acceptAndStartPreparing();
                System.out.println("[RESTAURANT] Order #" + orderNo + " accepted -> Preparing");

                // Mark Ready for Pickup NOW, right after accept, with NOTHING else touching adminPage in
                // between - CONFIRMED live (2026-10-19): the real cause of a 30s "Mark as Ready for Pickup"
                // timeout here (reproduced consistently) was calling LiveOrdersPage.orderUuidFor() first -
                // it does a page.reload() internally (see its own Javadoc), which wipes the just-opened
                // order detail panel the Ready button lives on. Reading the UUID, the customer-note
                // accordion, and the full-page delivery-instructions text are all done AFTER this click
                // instead, matching the accept-then-ready ordering OrderLifecycleTest already relies on.
                String pin = liveOrders.markReadyForPickup().at("/data/content/pin").asText(null);
                System.out.println("[RESTAURANT] Order #" + orderNo + " marked Ready for Pickup (pin=" + pin + ")");

                String restaurantNote = liveOrders.customerNoteText();
                boolean restaurantSeesFoodInstructions = FOOD_INSTRUCTIONS.equals(restaurantNote);
                if (restaurantSeesFoodInstructions) {
                    System.out.println("[RESTAURANT] Customer note shown in UI: \"" + restaurantNote + "\" (matches)");
                } else {
                    anyIssue = true;
                    System.out.println("[ISSUE] [RESTAURANT] Customer note does NOT match / is missing. "
                            + "Expected food Special Instructions \"" + FOOD_INSTRUCTIONS + "\", UI showed: \""
                            + restaurantNote + "\"");
                }

                // By design, Delivery Instructions is driver-facing only - the restaurant does NOT need to
                // see it (confirmed with the team), so its absence here is the CORRECT/expected state, not
                // an issue. Still checked and printed so a future leak INTO this UI (or a regression where
                // it silently stops reaching the driver, checked separately below) is visible either way.
                boolean restaurantPageMentionsDeliveryInstructions =
                        adminPage.locator("body").innerText().contains(DELIVERY_INSTRUCTIONS);
                if (restaurantPageMentionsDeliveryInstructions) {
                    System.out.println("[RESTAURANT] Delivery Instructions unexpectedly visible in the restaurant UI "
                            + "(not required to be there, but noting it)");
                } else {
                    System.out.println("[RESTAURANT] Delivery Instructions correctly NOT shown in the restaurant UI "
                            + "(by design - driver-facing only)");
                }

                String restaurantUuid = liveOrders.orderUuidFor(orderNo);

                // ---- 3. driver: accept, read the same two fields straight from the API ---------
                DriverAPI driverAPI = new DriverAPI(playwright, ConfigReader.driverApiBaseUrl(),
                        ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId());

                JsonNode accepted = driverAPI.acceptOrder(restaurantUuid);
                Assert.assertEquals(accepted.at("/data/content/status").asText(), "Accepted",
                        "Expected driver call to move order to Accepted");
                System.out.println("[DRIVER] Order #" + orderNo + " -> Accepted");

                String driverFoodInstruction = accepted.at("/data/content/itemDetails/0/foodInstruction").asText("");
                String driverDeliveryInstruction = accepted.at("/data/content/deliveryInstruction").asText("");

                boolean driverSeesFoodInstructions = FOOD_INSTRUCTIONS.equals(driverFoodInstruction);
                if (driverSeesFoodInstructions) {
                    System.out.println("[DRIVER] Special Instructions received via API: \"" + driverFoodInstruction + "\" (matches)");
                } else {
                    anyIssue = true;
                    System.out.println("[ISSUE] [DRIVER] Special Instructions missing/mismatched in the driver API response. "
                            + "Expected \"" + FOOD_INSTRUCTIONS + "\", got: \"" + driverFoodInstruction + "\"");
                }

                boolean driverSeesDeliveryInstructions = DELIVERY_INSTRUCTIONS.equals(driverDeliveryInstruction);
                if (driverSeesDeliveryInstructions) {
                    System.out.println("[DRIVER] Delivery Instructions received via API: \"" + driverDeliveryInstruction + "\" (matches)");
                } else {
                    anyIssue = true;
                    System.out.println("[ISSUE] [DRIVER] Delivery Instructions missing/mismatched in the driver API response. "
                            + "Expected \"" + DELIVERY_INSTRUCTIONS + "\", got: \"" + driverDeliveryInstruction + "\"");
                }

                // ---- 4. complete the order normally --------------------------------------------
                JsonNode arrived = driverAPI.markArrived(restaurantUuid);
                Assert.assertEquals(arrived.at("/data/content/status").asText(), "Arrived");
                System.out.println("[DRIVER] Order #" + orderNo + " -> Arrived at restaurant");

                JsonNode pickedUp = driverAPI.markPickedUp(restaurantUuid, pin);
                Assert.assertEquals(pickedUp.at("/data/content/status").asText(), "PickedUp");
                System.out.println("[DRIVER] Order #" + orderNo + " -> PickedUp, heading to customer");

                JsonNode delivered = driverAPI.markDeliveredWithRandomProof(restaurantUuid);
                Assert.assertTrue(DriverAPI.isDelivered(delivered), "Expected order to be confirmed Delivered: " + delivered);
                System.out.println("[DRIVER] Order #" + orderNo + " -> Delivered (photo proof uploaded)");

                // Give both pages' MQTT listeners a few seconds to catch the final push before closing.
                MqttConsoleCapture.awaitStatus(orderNo, "Completed", 10);
                driverAPI.close();
                adminContext.close();
                custContext.close();
                browser.close();

                System.out.println("[RESULT] " + (anyIssue
                        ? "COMPLETED WITH ISSUES - see [ISSUE] lines above for exactly what was missing/mismatched."
                        : "SUCCESS - restaurant saw the Special Instructions, driver received both Special Instructions "
                                + "AND Delivery Instructions, as designed."));

                // Hard requirement: the data itself must round-trip correctly end to end to whoever is
                // actually SUPPOSED to see it - that's the real functional contract this test checks. The
                // restaurant is not supposed to see Delivery Instructions (driver-facing only, by design -
                // see class Javadoc), so that is never asserted here at all, only logged.
                Assert.assertTrue(restaurantSeesFoodInstructions,
                        "Restaurant UI stopped showing the customer's Special Instructions at all - this is required, "
                                + "since the restaurant does need to see this one.");
                Assert.assertTrue(driverSeesFoodInstructions,
                        "Driver API response lost the customer's Special Instructions (itemDetails[].foodInstruction).");
                Assert.assertTrue(driverSeesDeliveryInstructions,
                        "Driver API response lost the customer's Delivery Instructions (deliveryInstruction).");
            } catch (Throwable t) {
                System.out.println("[FAILED] restaurantAndDriverSeeCustomerInstructions: " + t.getClass().getSimpleName()
                        + " - " + t.getMessage());
                throw new RuntimeException(t);
            }
        }
    }
}
