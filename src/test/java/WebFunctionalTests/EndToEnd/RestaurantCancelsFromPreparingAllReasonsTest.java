package WebFunctionalTests.EndToEnd;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Geolocation;
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
import utils.RetryAnalyzer;
import utils.TestDataGenerator;

import java.util.List;

/**
 * End-to-end: customer orders -> restaurant accepts into "Preparing" -> restaurant cancels from
 * Preparing, once per each of the four cancellation reasons the modal offers (CONFIRMED live
 * 2026-10-17 via LiveOrdersPage.cancelOrderWithReason() - see its Javadoc): "Out of stock",
 * "Store is closing", "Customer requested", "Other" (free-text). A cancelled order has no path
 * back to Preparing, so this runs the whole order->accept->cancel cycle once per reason rather
 * than reusing one order - four complete flows in one test, each printed and asserted on its own.
 */
public class RestaurantCancelsFromPreparingAllReasonsTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String RESTAURANT_NAME = "QA Burger Express [AA]";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";

    private static final List<String> CANCELLATION_REASONS = List.of(
            "Out of stock", "Store is closing", "Customer requested", "Other");

    @Test(description = "Restaurant accepts order into Preparing, then cancels it - once per each available cancellation reason",
            retryAnalyzer = RetryAnalyzer.class)
    public void restaurantCancelsFromPreparingForEveryReason() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));
            try {
                for (String reason : CANCELLATION_REASONS) {
                    runOneCancellation(browser, reason);
                }
                browser.close();
                System.out.println("[RESULT] SUCCESS: restaurant accepted into Preparing and cancelled for all "
                        + CANCELLATION_REASONS.size() + " reasons: " + CANCELLATION_REASONS);
            } catch (Throwable t) {
                System.out.println("[FAILED] restaurantCancelsFromPreparingForEveryReason: " + t.getClass().getSimpleName()
                        + " - " + t.getMessage());
                throw new RuntimeException(t);
            }
        }
    }

    private void runOneCancellation(Browser browser, String reason) {
        // Customer context is kept OPEN through accept + cancel (not closed right after ordering) so its
        // MqttConsoleCapture listener is still attached when the restaurant's status pushes arrive -
        // see "Reading the terminal output" in README for the [MQTT] <- [CUSTOMER]/[RESTAURANT] lines.
        CustomerOrder customer = placeCustomerOrder(browser);
        String orderNo = customer.orderNo();
        System.out.println("[CUSTOMER] Order placed: #" + orderNo);

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

        liveOrders.cancelOrderWithReason(reason);
        boolean panelClosed = !liveOrders.isOrderPanelOpen();
        System.out.println("[RESTAURANT] Order #" + orderNo + " cancelled (reason=\"" + reason + "\"); detail panel closed=" + panelClosed);

        // Give both pages' MQTT listeners a few seconds to actually receive the cancellation push
        // before their contexts close - the push is a live broker message, not a direct response to
        // this click, so it can lag a couple of seconds behind the HTTP call above.
        adminPage.waitForTimeout(4000);
        customer.page().waitForTimeout(4000);

        adminContext.close();
        customer.context().close();

        Assert.assertTrue(panelClosed,
                "Expected order #" + orderNo + "'s detail panel to close (Cancel Order/Mark as Ready gone) after "
                        + "confirming cancellation with reason \"" + reason + "\"");
        System.out.println("[RESULT] Reason \"" + reason + "\" -> order #" + orderNo + " cancelled from Preparing, confirmed.");
    }

    private record CustomerOrder(String orderNo, BrowserContext context, Page page) {
    }

    private CustomerOrder placeCustomerOrder(Browser browser) {
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

        return new CustomerOrder(orderNo, context, page);
    }
}
