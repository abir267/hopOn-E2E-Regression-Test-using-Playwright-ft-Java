package WebFunctionalTests.CustomerJourney;

import init.BaseRunner;
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

/**
 * Full happy-path journey, CONFIRMED end-to-end against the live site
 * (2026-09-15 probe, real order #019-A29 placed and tracked): login -> set
 * delivery location -> open restaurant -> add to cart -> checkout -> place
 * order -> order tracking.
 *
 * Restaurant/item choice matters here: a restaurant's menu only allows
 * add-to-cart when the active delivery location is inside its delivery
 * radius (see HomePage.setDeliveryLocationToCurrentLocation()). PizzaClub +
 * "Lahmacun, Middle East_86" is the pairing confirmed to work with
 * BaseRunner's default geolocation (Gulshan, Dhaka - see TestData.properties
 * delivery.latitude/longitude). If that geolocation is changed, re-probe
 * which restaurant/item is in range before relying on this test.
 *
 * Reaches the restaurant via HomePage.openRestaurantById() rather than the
 * search box - CONFIRMED (network trace) the search box only matches DISH
 * names ({"searchType":"FOOD"}), not restaurant names, so searching for
 * "PizzaClub"/"Pizza Club" genuinely returns 0 results server-side. See
 * HomePage.searchFor()'s javadoc.
 */
public class SearchAndOrderTest extends BaseRunner {

    private static final String RESTAURANT_ID = "b2545dca-3795-4aa5-8075-b5dc58de06f7";
    private static final String RESTAURANT_NAME = "PizzaClub";
    private static final String MENU_ITEM_NAME = "Lahmacun, Middle East_86";

    @Test(description = "Full happy-path journey: login -> location -> add to cart -> checkout -> place order -> track order",
            retryAnalyzer = RetryAnalyzer.class)
    public void searchAddToCartAndPlaceOrder() {
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

        menuPage.addItemToCart(MENU_ITEM_NAME);
        Assert.assertFalse(menuPage.isRestaurantUnavailable(),
                "Restaurant rejected the order as unavailable - delivery location is likely outside its service radius");
        System.out.println("[STEP] Added to cart: " + MENU_ITEM_NAME);

        CartPage cartPage = new CartPage(page);
        Assert.assertFalse(cartPage.isEmpty(), "Cart should not be empty after adding an item");
        Assert.assertTrue(cartPage.containsItem(MENU_ITEM_NAME), "Expected the added item to appear in the basket");

        CheckoutPage checkoutPage = cartPage.proceedToCheckout();
        System.out.println("[STEP] Reached checkout page");
        checkoutPage.placeOrder();
        Assert.assertTrue(checkoutPage.isOrderConfirmed(), "Expected an 'Awaiting Confirmation' order after placing order");

        String orderId = checkoutPage.orderId();
        Assert.assertNotNull(orderId, "Expected a non-null order id on confirmation");
        Assert.assertFalse(orderId.isBlank(), "Expected a non-blank order id on confirmation");
        System.out.println("[STEP] Order placed successfully! Order ID #" + orderId);

        OrderTrackingPage trackingPage = checkoutPage.trackOrder();
        Assert.assertEquals(trackingPage.orderId(), orderId, "Tracking page should show the same order id just placed");
        Assert.assertTrue(trackingPage.isStepReached("Order Placed"), "Expected the 'Order Placed' step to be reached");
        System.out.println("[STEP] Order tracking page reached for #" + orderId + " -> " + page.url());
        System.out.println("[RESULT] SUCCESS: full order journey completed end-to-end.");
    }
}
