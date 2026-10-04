package WebFunctionalTests.ValidationFlows;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Geolocation;
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
import utils.TestDataGenerator;

import java.util.List;

/**
 * Negative-path validation: a restaurant toggled to "Not Accepting Orders" in
 * the admin panel (the "Restaurant Status" switch on Live Orders - see
 * LiveOrdersPage) must not accept a real order from the customer app.
 *
 * KNOWN BUG, CONFIRMED LIVE (2026-09-23, real order #01E-AA0 on QA Burger
 * Express [AA]): toggling the restaurant to "Not Accepting Orders" does NOT
 * block ordering - the customer app shows no closed/unavailable indicator at
 * all (menu, item cards, and "+" all behave exactly as if the restaurant were
 * open), and a full checkout completes normally. This test is expected to
 * FAIL until that's fixed - that failure IS the bug report; don't "fix" this
 * test by loosening the assertion.
 *
 * Always restores the restaurant to "Accepting Orders" in a finally block -
 * this flips a real shared admin setting that every other test in this repo
 * assumes is on.
 */
public class RestaurantNotAcceptingOrdersTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";

    @Test(description = "A restaurant marked 'Not Accepting Orders' must not let a customer place a real order")
    public void cannotOrderWhileRestaurantNotAcceptingOrders() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            LiveOrdersPage liveOrders = openAdminLiveOrders(browser);
            try {
                liveOrders.setAcceptingOrders(false);
                Assert.assertFalse(liveOrders.isAcceptingOrders(), "Precondition failed: could not turn restaurant status off");
                System.out.println("[RESTAURANT] Status set to: " + liveOrders.restaurantStatusLabel());

                BrowserContext custContext = browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(1366, 768)
                        .setGeolocation(new Geolocation(ConfigReader.deliveryLatitude(), ConfigReader.deliveryLongitude()))
                        .setPermissions(List.of("geolocation")));
                Page custPage = custContext.newPage();

                HomePage home = new HomePage(custPage).open(ConfigReader.baseUrl());
                home.openLogin();
                String phone = TestDataGenerator.randomRiderPhone();
                new LoginPage(custPage).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
                System.out.println("[CUSTOMER] Logged in as " + phone);

                home.setDeliveryLocationToCurrentLocation();
                home.openRestaurantById(ConfigReader.baseUrl(), RESTAURANT_ID);

                RestaurantMenuPage menuPage = new RestaurantMenuPage(custPage);
                menuPage.addItemToCartWithSize(MENU_ITEM_NAME, MENU_ITEM_SIZE);

                CartPage cartPage = new CartPage(custPage);
                if (cartPage.isEmpty()) {
                    System.out.println("[RESULT] CORRECT: item never entered the cart while restaurant is not accepting orders.");
                    custContext.close();
                    return;
                }
                System.out.println("[WARN] Item was added to cart despite restaurant not accepting orders. Continuing to checkout.");

                CheckoutPage checkoutPage = cartPage.proceedToCheckout();
                checkoutPage.placeOrder();
                boolean confirmed = checkoutPage.isOrderConfirmed();
                String orderId = confirmed ? checkoutPage.orderId() : null;

                custContext.close();

                Assert.assertFalse(confirmed,
                        "BUG: order placed (id=" + orderId + ") while restaurant status was 'Not Accepting Orders'. "
                                + "The customer app showed no closed/unavailable indicator anywhere in this flow.");
            } finally {
                // Restore BEFORE closing the browser - the admin page (and this
                // click) needs it alive. CONFIRMED the hard way: closing the
                // browser first (or even racing its async teardown) can let this
                // restore silently no-op or throw TargetClosedError, leaving a
                // real shared restaurant setting stuck off for every other test.
                liveOrders.setAcceptingOrders(true);
                System.out.println("[CLEANUP] Restaurant status restored to: " + liveOrders.restaurantStatusLabel());
                browser.close();
            }
        }
    }

    private LiveOrdersPage openAdminLiveOrders(Browser browser) {
        Page page = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(ConfigReader.restaurantAdminUrl())
                .setViewportSize(1440, 900)).newPage();
        RestaurantPortalPage restaurant = new WebRestaurantLogin(page)
                .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());
        restaurant.openLiveOrders();
        return new LiveOrdersPage(page);
    }
}
