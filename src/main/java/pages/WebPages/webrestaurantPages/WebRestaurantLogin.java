package pages.WebPages.webrestaurantPages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
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
        page.navigate(ConfigReader.restaurantAdminUrl());
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
