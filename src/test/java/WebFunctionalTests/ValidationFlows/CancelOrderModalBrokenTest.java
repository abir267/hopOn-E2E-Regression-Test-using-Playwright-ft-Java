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
import pages.WebPages.customerPages.OrderTrackingPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import utils.ConfigReader;
import utils.RetryAnalyzer;
import utils.TestDataGenerator;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bug report (Issue 4): "Order cancellation popup is dismissible/non-static
 * on web (should match native app modal behavior)".
 *
 * CONFIRMED live (2026-09-28, order tracking page) - the actual bug is worse
 * than "dismissible": clicking "Cancel Order (Valid within 3 mins)" does
 * NOTHING VISIBLE AT ALL. No confirmation modal, popup, or toast ever
 * appears. Root cause found via the browser console, captured below:
 *
 *   ERROR Error: The "Cancel" icon has not been provided by any available
 *   icon providers.
 *       at LucideAngularComponent.ngOnChanges (...chunk-SUE2ULFI.js:27382)
 *       ...
 *       at ToastNotification_Template (...main-BJWT6IB2.js:3505)
 *
 * The cancel-confirmation is implemented as a toast notification that tries
 * to render a Lucide "Cancel" icon; that icon name isn't registered with any
 * icon provider, so the Angular template throws and the whole toast silently
 * fails to render - the user gets no feedback whatsoever, not even a
 * dismissible one.
 *
 * This test documents the CURRENT (broken) state with a screenshot and the
 * captured console error, and is written to FAIL once the fix is right -
 * flip EXPECT_MODAL_TO_APPEAR to true (and delete the current assertion)
 * once the icon-provider bug is fixed, or better, add a real dialog-role
 * assertion at that point.
 */
public class CancelOrderModalBrokenTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";
    private static final Path SCREENSHOT_PATH = Paths.get("target", "bug-evidence", "issue4_cancel_order_modal_broken.png");

    @Test(description = "Clicking 'Cancel Order' on the tracking page must show a confirmation modal/toast",
            retryAnalyzer = RetryAnalyzer.class)
    public void cancelOrderShowsNoConfirmation() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1366, 900)
                    .setGeolocation(new Geolocation(ConfigReader.deliveryLatitude(), ConfigReader.deliveryLongitude()))
                    .setPermissions(List.of("geolocation")));
            Page page = context.newPage();

            AtomicReference<String> consoleError = new AtomicReference<>();
            page.onConsoleMessage(msg -> {
                if ("error".equals(msg.type()) && msg.text().contains("icon")) {
                    consoleError.set(msg.text());
                }
            });

            HomePage home = new HomePage(page).open(ConfigReader.baseUrl());
            home.openLogin();
            String phone = TestDataGenerator.randomRiderPhone();
            new LoginPage(page).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
            System.out.println("[CUSTOMER] Logged in as " + phone);

            home.setDeliveryLocationToCurrentLocation();
            home.openRestaurantById(ConfigReader.baseUrl(), RESTAURANT_ID);
            new RestaurantMenuPage(page).addItemToCartWithSize(MENU_ITEM_NAME, MENU_ITEM_SIZE);
            CheckoutPage checkoutPage = new CartPage(page).proceedToCheckout();
            checkoutPage.placeOrder();
            String orderNo = checkoutPage.orderId();
            OrderTrackingPage trackingPage = checkoutPage.trackOrder();
            page.waitForTimeout(1200);
            System.out.println("[CUSTOMER] Order placed and tracking page reached: #" + orderNo);

            Assert.assertTrue(trackingPage.isCancelOrderAvailable(), "Expected 'Cancel Order' to be visible within the 3-minute window");

            page.getByText("Cancel Order", new Page.GetByTextOptions().setExact(false)).first().click();
            page.waitForTimeout(1500);

            boolean dialogAppeared = page.locator("[role=dialog], [role=alertdialog]").count() > 0;

            SCREENSHOT_PATH.getParent().toFile().mkdirs();
            page.screenshot(new Page.ScreenshotOptions().setPath(SCREENSHOT_PATH).setFullPage(false));
            System.out.println("[EVIDENCE] Screenshot saved to " + SCREENSHOT_PATH.toAbsolutePath());
            System.out.println("[EVIDENCE] Console error captured: " + consoleError.get());

            context.close();
            browser.close();

            Assert.assertFalse(dialogAppeared,
                    "This assertion documents the CURRENT bug (no confirmation dialog appears) - if it now "
                            + "fails because a dialog DID appear, the underlying icon-provider bug has likely "
                            + "been fixed; update this test to assert the dialog's real content instead.");
            Assert.assertNotNull(consoleError.get(),
                    "Expected to still capture the 'Cancel' icon-provider console error - if this is null, "
                            + "the root cause may have changed; re-investigate before assuming it's fixed.");
            System.out.println("[RESULT] CONFIRMED BUG: Cancel Order shows no confirmation - root cause: " + consoleError.get());
        }
    }
}
