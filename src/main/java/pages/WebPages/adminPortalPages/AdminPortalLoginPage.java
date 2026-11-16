package pages.WebPages.adminPortalPages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitUntilState;
import utils.ConfigReader;


public class AdminPortalLoginPage extends AdminPortalBasePage {

    private static final String EMAIL_INPUT = "input[formcontrolname='email']";
    private static final String PASSWORD_INPUT = "#password";
    private static final String SIGN_IN_BUTTON = "button[type='submit']";
    private static final String HOP_ON_FOOD_BUTTON = "button:has-text('Hop on Food')";

    public AdminPortalLoginPage(Page page) {
        super(page);
        // DOMCONTENTLOADED + 60s: the dev portals sometimes never fire "load" within 30s
        page.navigate(ConfigReader.adminPortalUrl(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
    }

    public RestaurantsListPage loginWithEmailAndPassword(String email, String password) {
        type(EMAIL_INPUT, email);
        type(PASSWORD_INPUT, password);
        page.click(SIGN_IN_BUTTON);
        try {
            page.waitForURL(url -> !url.contains("/public/login"));
        } catch (TimeoutError e) {
            // ONE attempt, no automatic retry (user instruction 2026-11-16: print the error and stop). Seen live: a bare
            // "TimeoutError: Timeout 30000ms exceeded". The two real causes:
            throw new IllegalStateException("Admin portal login did not leave /public/login within 30s for '" + email
                    + "'. Either the credentials are wrong (check admin.portal.email / admin.portal.password in "
                    + "TestData.properties - a copied example file still has CHANGE_ME) or the dev backend answered "
                    + "POST /services/admin/api/login with an error (look for a '[BUG][ADMIN_PORTAL] 500' line just "
                    + "above) - if so, wait a minute and run it again.", e);
        }
        page.click(HOP_ON_FOOD_BUTTON);
        return new RestaurantsListPage(page);
    }
}
