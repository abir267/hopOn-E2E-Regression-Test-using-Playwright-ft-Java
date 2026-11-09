package WebFunctionalTests.WebPortal;

import init.AdminBaseRunner;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.webrestaurantPages.WebRestaurantLogin;
import pages.WebPages.webrestaurantPages.RestaurantPortalPage;
import utils.ConfigReader;

/**
 * Sidebar smoke test for the restaurant-admin ("web-restaurant") portal:
 * logs in, then walks all 4 sidebar tabs and asserts each one actually loads
 * its own heading. Also documents (rather than fails on) the current known
 * gap: Dashboard renders generic ride-hailing placeholder content instead of
 * real restaurant data, while Live Orders / Menu Management / Discount &amp;
 * Promotions are genuinely implemented - see RestaurantPortalPage's class doc
 * for the 2026-09-19 probe this is based on.
 */
public class WebRestaurantNavigationTest extends AdminBaseRunner {

    @Test
    public void sidebarTabsLoadExpectedContent() {
        RestaurantPortalPage restaurant = new WebRestaurantLogin(page)
                .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());

        Assert.assertTrue(restaurant.isSidebarVisible(), "Sidebar should be visible after login");

        restaurant.openDashboard();
        Assert.assertTrue(restaurant.isDashboardPlaceholder(),
                "Dashboard is known to still show placeholder ride-hailing content, not real restaurant data");

        restaurant.openLiveOrders();
        Assert.assertTrue(restaurant.isHeadingVisible("Live Orders"), "Live Orders tab should load its own heading");

        restaurant.openMenuManagement();
        Assert.assertTrue(restaurant.isHeadingVisible("Menu Management"), "Menu Management tab should load its own heading");

        restaurant.openDiscountAndPromotions();
        Assert.assertTrue(restaurant.isHeadingVisible("Discounts & Promotions"), "Discount & Promotions tab should load its own heading");
    }
}
