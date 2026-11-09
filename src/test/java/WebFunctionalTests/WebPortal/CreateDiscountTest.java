package WebFunctionalTests.WebPortal;

import init.AdminBaseRunner;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.webrestaurantPages.CreateDiscountPage;
import pages.WebPages.webrestaurantPages.RestaurantPortalPage;
import pages.WebPages.webrestaurantPages.WebRestaurantLogin;
import utils.ConfigReader;

/**
 * Single happy-path coverage for creating a discount from the Discount &amp;
 * Promotions tab: percentage discount, default "all active menu items"
 * scope, no item picker involved. See {@link CreateDiscountPage}'s class doc
 * for the confirmed selectors this is built on.
 */
public class CreateDiscountTest extends AdminBaseRunner {

    @Test
    public void createDiscount_withRequiredFields_succeeds() {
        String discountName = "Automation Discount " + System.currentTimeMillis();

        RestaurantPortalPage portal = new WebRestaurantLogin(page)
                .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());
        portal.openDiscountAndPromotions();

        CreateDiscountPage createDiscount = portal.openCreateDiscount()
                .enterName(discountName)
                .enterDescription("Created by automation for the single-happy-path discount test.")
                .enterDiscountValue("15");

        // Far enough ahead, and varied per run, to avoid colliding with discounts
        // earlier automation runs left active over the same date range (CONFIRMED
        // the backend rejects overlapping "all active items" discounts).
        int monthsAhead = 3;
        int startDay = 2 + (int) ((System.currentTimeMillis() / 60000) % 20);
        createDiscount.selectStartDate(monthsAhead, startDay);
        createDiscount.selectEndDate(monthsAhead, startDay + 1);

        createDiscount.clickCreateDiscount();

        Assert.assertTrue(createDiscount.isSuccessToastVisible(),
                "Expected the 'Discount Created' toast after submitting a valid discount");
    }
}
