package pages.WebPages.webrestaurantPages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitUntilState;
import utils.ConfigReader;

/**
 * Email + password login for the restaurant-admin ("web-restaurant") portal —
 * CONFIRMED against the live site (2026-09-19 probe of
 * https://dev-foodadmin.hoponbd.com): that URL redirects to
 * https://dev-admin.hoponbd.com/public/login (shared admin auth host across
 * Hopon's admin properties, not the food-specific domain).
 *   - email input has no name/id, only type="email" (placeholder "hello@hopon.com")
 *   - password input is "#password"
 *   - submit button is a <button type="submit"> with visible text "Sign In"
 * On success the app redirects to /dashboard on the food-admin host.
 */
public class WebRestaurantLogin extends AdminBasePage {

    private static final String EMAIL_INPUT = "input[type=email]";
    private static final String PASSWORD_INPUT = "#password";

    public WebRestaurantLogin(Page page) {
        super(page);
        navigateToLogin(page);
    }

    /**
     * Not a bare page.navigate(url): that waits for the full "load" event, which the dev portal
     * sometimes never fires within 30s (slow third-party/map/font resources) - seen live as
     * "TimeoutError: navigating to https://dev-foodadmin.hoponbd.com/, waiting until load".
     * The login form only needs the DOM (enterEmail() auto-waits for the input), so wait for
     * DOMCONTENTLOADED, allow 60s, and retry once on a timeout.
     */
    private static void navigateToLogin(Page page) {
        String url = ConfigReader.restaurantAdminUrl();
        Page.NavigateOptions options = new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(60000);
        try {
            page.navigate(url, options);
        } catch (TimeoutError firstAttempt) {
            System.out.println("[RESTAURANT] Portal login page timed out after 60s - retrying once: " + url);
            page.navigate(url, options);
        }
    }

    public WebRestaurantLogin enterEmail(String email) {
        type(EMAIL_INPUT, email);
        return this;
    }

    public WebRestaurantLogin enterPassword(String password) {
        type(PASSWORD_INPUT, password);
        return this;
    }

    public RestaurantPortalPage submit() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign In")).click();
        waitVisible("aside");
        return new RestaurantPortalPage(page);
    }

    /** Convenience method chaining the full flow for the common "happy path" case. */
    public RestaurantPortalPage loginWithEmailAndPassword(String email, String password) {
        enterEmail(email);
        enterPassword(password);
        return submit();
    }
}
