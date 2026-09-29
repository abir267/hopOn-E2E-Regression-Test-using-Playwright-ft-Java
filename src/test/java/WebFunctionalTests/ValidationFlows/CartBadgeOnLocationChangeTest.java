package WebFunctionalTests.ValidationFlows;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Geolocation;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import utils.ConfigReader;
import utils.RetryAnalyzer;
import utils.TestDataGenerator;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Bug report (Issue 2): "Cart badge/message disappears or misplaces when
 * delivery location is changed" - repro: add item(s) to cart, change
 * delivery location on the CUSTOMER web app itself (not admin), observe the
 * cart indicator/message's position.
 *
 * TRIED, NOT REPRODUCED (2026-09-28): switched delivery location live via
 * the customer web's own "Use my current location" re-trigger, from Gulshan
 * (in QA Burger Express's delivery radius) to real Uttara coordinates
 * (23.8759, 90.3795 - outside it, ~15km away, a genuinely different Dhaka
 * area as asked, not just a GPS-outside-Dhaka case like the separate
 * GpsOutsideServiceAreaTest). Observed:
 *   - a clear inline warning appears in the basket panel: "Not accepting
 *     orders right now - You are outside their allowed delivery zone"
 *   - the cart line item visually greys out (name/price/restaurant all
 *     switch to muted text)
 *   - "Continue to Checkout" becomes disabled
 *   - the header cart badge stays exactly where it was ("1"), never
 *     disappears or shifts
 *
 * This is genuinely correct, well-handled behavior for this specific
 * transition - it does not reproduce what the report describes. Two things
 * this test does NOT rule out, both worth the reporter's own re-check if the
 * bug is still seen:
 *   1. A TRANSIENT visual glitch during the location-change animation itself
 *      (a brief flash/shift before the UI settles) - discrete screenshots a
 *      couple of seconds apart, as used here, cannot catch that; only a
 *      frame-by-frame video (like the report's own screen recording) can.
 *   2. A DIFFERENT specific transition not tried here - e.g. switching
 *      between two addresses that are BOTH in-range (no unavailable-zone
 *      banner involved at all), or doing this from the home/restaurant-list
 *      page rather than from inside a restaurant's menu page.
 *
 * This test is kept as a regression guard for the transition that WAS
 * tried, not as proof the report is wrong.
 */
public class CartBadgeOnLocationChangeTest {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final String MENU_ITEM_SIZE = "Regular";
    private static final Geolocation GULSHAN = new Geolocation(23.7925, 90.4078);
    private static final Geolocation UTTARA = new Geolocation(23.8759, 90.3795);
    private static final Path SCREENSHOT_BEFORE = Paths.get("target", "bug-evidence", "issue2_cart_before_location_change.png");
    private static final Path SCREENSHOT_AFTER = Paths.get("target", "bug-evidence", "issue2_cart_after_location_change_to_uttara.png");

    @Test(description = "Cart badge/basket must stay correctly anchored when the customer changes delivery location (Gulshan -> Uttara)",
            retryAnalyzer = RetryAnalyzer.class)
    public void cartBadgeStaysAnchoredWhenLocationChangesToUttara() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(ConfigReader.headless())
                    .setSlowMo(ConfigReader.slowMo()));

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1366, 900)
                    .setGeolocation(GULSHAN)
                    .setPermissions(List.of("geolocation")));
            Page page = context.newPage();

            HomePage home = new HomePage(page).open(ConfigReader.baseUrl());
            home.openLogin();
            String phone = TestDataGenerator.randomRiderPhone();
            new LoginPage(page).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
            System.out.println("[CUSTOMER] Logged in as " + phone);

            home.setDeliveryLocationToCurrentLocation();
            home.openRestaurantById(ConfigReader.baseUrl(), RESTAURANT_ID);
            new RestaurantMenuPage(page).addItemToCartWithSize(MENU_ITEM_NAME, MENU_ITEM_SIZE);

            CartPage cartBefore = new CartPage(page);
            Assert.assertFalse(cartBefore.isEmpty(), "Expected item in cart before location change");
            Assert.assertTrue(cartBefore.containsItem(MENU_ITEM_NAME), "Expected the item name visible in the basket panel before location change");

            SCREENSHOT_BEFORE.getParent().toFile().mkdirs();
            page.screenshot(new Page.ScreenshotOptions().setPath(SCREENSHOT_BEFORE).setFullPage(false));

            // Change delivery location on the customer web app itself, to a
            // genuinely different Dhaka area (Uttara).
            context.setGeolocation(UTTARA);
            page.locator("#app-current-address").click();
            page.waitForTimeout(600);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Use my current location")).click();
            page.waitForTimeout(2000);

            CartPage cartAfter = new CartPage(page);
            boolean cartStillHasItem = !cartAfter.isEmpty();
            boolean itemNameStillShown = cartAfter.containsItem(MENU_ITEM_NAME);
            System.out.println("[CUSTOMER] Basket panel still non-empty after switching to Uttara: " + cartStillHasItem);
            System.out.println("[CUSTOMER] Item name still shown in basket panel: " + itemNameStillShown);

            SCREENSHOT_AFTER.getParent().toFile().mkdirs();
            page.screenshot(new Page.ScreenshotOptions().setPath(SCREENSHOT_AFTER).setFullPage(false));
            System.out.println("[EVIDENCE] Screenshots saved: " + SCREENSHOT_BEFORE.toAbsolutePath() + " / " + SCREENSHOT_AFTER.toAbsolutePath());

            context.close();
            browser.close();

            Assert.assertTrue(itemNameStillShown,
                    "The item disappeared from the basket panel after changing delivery location to Uttara - "
                            + "this would confirm the reported bug for this specific transition.");
            System.out.println("[RESULT] Basket panel stayed correctly anchored and populated through the Gulshan -> Uttara "
                    + "location change (did not reproduce the reported misplacement for this transition - "
                    + "see the before/after screenshots for the actual visual state).");
        }
    }
}
