package pages.WebPages.adminPortalPages;

import com.microsoft.playwright.Page;
import utils.ConfigReader;

/**
 * Email + password login for the Hop On Admin Management portal
 * (dev-admin.hoponbd.com/public/login) — the internal staff backoffice, not
 * the per-restaurant "web-restaurant" portal (see webrestaurantPages for
 * that one). Same login form shape as admin-portal-automation's LoginPage
 * (formcontrolname='email' / #password / submit button), since it is the
 * same Angular app.
 *
 * After sign-in the app lands on a general dashboard with a "Hop on Food"
 * button to switch into the food-admin section (Dashboard / Menu Management
 * / Food Types / Order Management / Restaurants / Earnings sidebar) - same
 * switch admin-portal-automation's CreateRestaurantPage.switchToHopOnFood()
 * uses.
 */
public class AdminPortalLoginPage extends AdminPortalBasePage {

    private static final String EMAIL_INPUT = "input[formcontrolname='email']";
    private static final String PASSWORD_INPUT = "#password";
    private static final String SIGN_IN_BUTTON = "button[type='submit']";
    private static final String HOP_ON_FOOD_BUTTON = "button:has-text('Hop on Food')";

    public AdminPortalLoginPage(Page page) {
        super(page);
        page.navigate(ConfigReader.adminPortalUrl());
    }

    public RestaurantsListPage loginWithEmailAndPassword(String email, String password) {
        type(EMAIL_INPUT, email);
        type(PASSWORD_INPUT, password);
        page.click(SIGN_IN_BUTTON);
        page.waitForURL(url -> !url.contains("/public/login"));
        page.click(HOP_ON_FOOD_BUTTON);
        return new RestaurantsListPage(page);
    }
}
