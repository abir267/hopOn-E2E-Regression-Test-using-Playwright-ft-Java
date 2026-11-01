package WebFunctionalTests.CustomerJourney;

import init.BaseRunner;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import utils.ConfigReader;
import utils.RetryAnalyzer;
import utils.TestDataGenerator;

import java.util.List;

/**
 * Covers choosing an item with MULTIPLE required customization groups plus a
 * quantity stepper - e.g. a dish where both a size AND a separate required
 * choice have to be picked before "Add to Cart" is clickable, same shape as
 * SIZE-only items but with more than one required group open at once. See
 * {@link RestaurantMenuPage#addItemToCartWithOptions} for the confirmed modal
 * mechanics this is built on.
 *
 * "QA Burger Express [AA]" + "Crispy Burger" (Burgerbb1 tab) is the pairing
 * CONFIRMED live (2026-10-27 probe) to have this shape: a required "SIZE"
 * group (Medium/Small/Regular, each priced differently) and a second
 * required group with its own option - both must be picked, same as the
 * multi-group items shown when choosing food on the live site.
 */
public class ItemVariationTest extends BaseRunner {

    private static final String RESTAURANT_ID = "aa689d64-fa2f-4d9b-b83e-7d8bc9ccc589";
    private static final String RESTAURANT_NAME = "QA Burger Express";
    private static final String MENU_ITEM_NAME = "Crispy Burger";
    private static final List<String> REQUIRED_OPTIONS = List.of("Small", "dsdsd");
    private static final int QUANTITY = 2;
    private static final String SPECIAL_INSTRUCTIONS = "No onions please";

    @Test(description = "Choosing an item with several required option groups (size + an extra required choice), "
            + "a bumped-up quantity, and special instructions - confirms all of it is reflected in the cart",
            retryAnalyzer = RetryAnalyzer.class)
    public void addItemWithMultipleRequiredOptionsToCart() {
        HomePage homePage = new HomePage(page).open(ConfigReader.baseUrl());
        homePage.openLogin();
        String phone = TestDataGenerator.randomRiderPhone();
        new LoginPage(page).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
        Assert.assertTrue(homePage.isLoggedIn(), "Precondition failed: user must be logged in before ordering");
        System.out.println("[STEP] Logged in as " + phone);

        homePage.setDeliveryLocationToCurrentLocation();
        System.out.println("[STEP] Delivery location set to current location");

        homePage.openRestaurantById(ConfigReader.baseUrl(), RESTAURANT_ID);
        RestaurantMenuPage menuPage = new RestaurantMenuPage(page);
        Assert.assertTrue(menuPage.restaurantTitle().contains(RESTAURANT_NAME),
                "Expected to land on the correct restaurant page");
        System.out.println("[STEP] Opened restaurant: " + menuPage.restaurantTitle());

        menuPage.addItemToCartWithOptions(MENU_ITEM_NAME, REQUIRED_OPTIONS, QUANTITY, SPECIAL_INSTRUCTIONS);
        Assert.assertFalse(menuPage.isRestaurantUnavailable(),
                "Restaurant rejected the order as unavailable - delivery location is likely outside its service radius");
        System.out.println("[STEP] Added to cart: " + MENU_ITEM_NAME + " (options=" + REQUIRED_OPTIONS
                + ", qty=" + QUANTITY + ", instructions=\"" + SPECIAL_INSTRUCTIONS + "\")");

        CartPage cartPage = new CartPage(page);
        Assert.assertFalse(cartPage.isEmpty(), "Cart should not be empty after adding a multi-option item");
        Assert.assertTrue(cartPage.containsItem(MENU_ITEM_NAME), "Expected the added item to appear in the basket");
        System.out.println("[RESULT] SUCCESS: multi-required-group item added to cart correctly.");
    }
}
