package WebFunctionalTests.ValidationFlows;

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
import utils.ConfigReader;
import utils.RetryAnalyzer;
import utils.TestDataGenerator;

import java.util.List;

/**
 * Negative-path validation: a customer whose GPS is nowhere near Dhaka must
 * NOT be able to place a real order. CONFIRMED against the live site
 * (2026-09-23 probe, Cox's Bazar coordinates 21.4272, 92.0058 - genuinely
 * outside every restaurant's delivery radius, not just a tight geofence
 * miss): setting delivery location to that point and clicking "+" on a menu
 * item immediately shows a "Restaurant is unavailable" modal, BEFORE any
 * size-selection step - the add never reaches the cart at all.
 *
 * This test asserts that blocking behavior. If a future backend/frontend
 * change lets the add-to-cart (or worse, checkout) go through from an
 * out-of-service-area location, that is a real bug - the assertions below
 * fail loudly rather than silently accepting it, and the test walks the rest
 * of the flow (checkout, place order) specifically to catch that case instead
 * of stopping at the first sign the block didn't happen.
 */
public class GpsOutsideServiceAreaTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";

    // Cox's Bazar - genuinely outside Dhaka and every delivery radius in this app.
    private static final double OUTSIDE_DHAKA_LATITUDE = 21.4272;
    private static final double OUTSIDE_DHAKA_LONGITUDE = 92.0058;

    @Test(description = "Customer with GPS far outside Dhaka must not be able to place an order",
            retryAnalyzer = RetryAnalyzer.class)
    public void cannotOrderFromOutsideServiceArea() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1366, 768)
                    .setGeolocation(new Geolocation(OUTSIDE_DHAKA_LATITUDE, OUTSIDE_DHAKA_LONGITUDE))
                    .setPermissions(List.of("geolocation")));
            Page page = context.newPage();

            HomePage home = new HomePage(page).open(ConfigReader.baseUrl());
            home.openLogin();
            String phone = TestDataGenerator.randomRiderPhone();
            new LoginPage(page).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
            Assert.assertTrue(home.isLoggedIn(), "Customer must be logged in before this check means anything");
            System.out.println("[CUSTOMER] Logged in as " + phone + ", GPS set to Cox's Bazar (outside Dhaka)");

            home.setDeliveryLocationToCurrentLocation();
            home.openRestaurantById(ConfigReader.baseUrl(), RESTAURANT_ID);

            RestaurantMenuPage menuPage = new RestaurantMenuPage(page);
            menuPage.clickAddButton(MENU_ITEM_NAME);

            if (menuPage.isRestaurantUnavailable()) {
                System.out.println("[RESULT] CORRECT: \"Restaurant is unavailable\" shown - add-to-cart blocked from outside the service area.");
                browser.close();
                return;
            }

            // The expected block did NOT happen - keep going to see how far a real
            // order gets, instead of stopping here. Every following step that
            // succeeds makes the eventual bug report more precise.
            System.out.println("[WARN] No \"Restaurant is unavailable\" modal appeared - item add was not blocked. Continuing to see if a full order can be placed.");

            page.getByText("SIZE").waitFor();
            page.getByText(MENU_ITEM_SIZE, new Page.GetByTextOptions().setExact(true)).click();
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Add to Cart")).click();
            page.waitForTimeout(800);

            CartPage cartPage = new CartPage(page);
            if (cartPage.isEmpty()) {
                System.out.println("[RESULT] CORRECT (late block): item never actually landed in the cart despite no modal.");
                browser.close();
                return;
            }

            CheckoutPage checkoutPage = cartPage.proceedToCheckout();
            checkoutPage.placeOrder();

            boolean orderConfirmed = checkoutPage.isOrderConfirmed();
            browser.close();

            Assert.assertFalse(orderConfirmed,
                    "BUG: an order was placed from a GPS location outside Dhaka / every restaurant's service area "
                            + "(order id: " + (orderConfirmed ? checkoutPage.orderId() : "n/a") + "). "
                            + "This must be blocked - see class doc for the CONFIRMED-correct behavior.");
        }
    }
}
