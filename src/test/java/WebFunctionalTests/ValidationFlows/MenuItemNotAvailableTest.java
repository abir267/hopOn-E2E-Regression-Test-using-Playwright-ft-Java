package WebFunctionalTests.ValidationFlows;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Geolocation;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.adminPages.AdminLoginPage;
import pages.WebPages.adminPages.MenuManagementPage;
import pages.WebPages.adminPages.WebRestaurantPage;
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.CheckoutPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import utils.ConfigReader;
import utils.TestDataGenerator;

import java.util.List;

/**
 * Negative-path validation: a menu item toggled "Not Available" in the admin
 * panel's Menu Management (see MenuManagementPage) must not be orderable.
 *
 * KNOWN BUG, CONFIRMED LIVE (2026-09-23, real order #01E-B04 on QA Burger
 * Express [AA], item "Crispy Burger"): toggling an item to "Not Available"
 * does NOT block ordering it - the item still SHOWS on the customer menu
 * (not hidden), the size-selection modal and "Add to Cart" both work exactly
 * as if the item were available, and a full checkout completes normally.
 * This test is expected to FAIL until that's fixed - that failure IS the bug
 * report; don't "fix" this test by loosening the assertion.
 *
 * Always restores the item to "Available" in a finally block - this flips a
 * real shared admin setting every other test in this repo assumes is on.
 */
public class MenuItemNotAvailableTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";

    @Test(description = "A menu item marked 'Not Available' must not let a customer place a real order for it")
    public void cannotOrderMenuItemMarkedNotAvailable() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            MenuManagementPage menuMgmt = openAdminMenuManagement(browser);
            try {
                menuMgmt.setAvailability(false);
                Assert.assertFalse(menuMgmt.isAvailable(), "Precondition failed: could not turn item availability off");
                System.out.println("[RESTAURANT] " + MENU_ITEM_NAME + " set to Not Available");

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
                    System.out.println("[RESULT] CORRECT: item never entered the cart while marked Not Available.");
                    custContext.close();
                    return;
                }
                System.out.println("[WARN] Item was added to cart despite being marked Not Available. Continuing to checkout.");

                CheckoutPage checkoutPage = cartPage.proceedToCheckout();
                checkoutPage.placeOrder();
                boolean confirmed = checkoutPage.isOrderConfirmed();
                String orderId = confirmed ? checkoutPage.orderId() : null;

                custContext.close();

                Assert.assertFalse(confirmed,
                        "BUG: order placed (id=" + orderId + ") for \"" + MENU_ITEM_NAME + "\" while it was marked Not Available. "
                                + "The item stayed visible and orderable on the customer menu throughout.");
            } finally {
                // Restore BEFORE closing the browser - see RestaurantNotAcceptingOrdersTest's
                // finally block for why (a real shared setting almost got stuck off
                // here from exactly this ordering mistake).
                menuMgmt.setAvailability(true);
                System.out.println("[CLEANUP] " + MENU_ITEM_NAME + " restored to Available: " + menuMgmt.isAvailable());
                browser.close();
            }
        }
    }

    private MenuManagementPage openAdminMenuManagement(Browser browser) {
        Page page = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(ConfigReader.restaurantAdminUrl())
                .setViewportSize(1440, 900)).newPage();
        WebRestaurantPage restaurant = new AdminLoginPage(page)
                .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());
        restaurant.openMenuManagement();
        MenuManagementPage menuMgmt = new MenuManagementPage(page);
        menuMgmt.searchItem(MENU_ITEM_NAME);
        return menuMgmt;
    }
}
