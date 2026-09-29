package pages.WebPages.adminPages;

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
public class AdminLoginPage extends AdminBasePage {

    private static final String EMAIL_INPUT = "input[type=email]";
    private static final String PASSWORD_INPUT = "#password";

    public AdminLoginPage(Page page) {
        super(page);
        page.navigate(ConfigReader.restaurantAdminUrl());
    }

    public AdminLoginPage enterEmail(String email) {
        type(EMAIL_INPUT, email);
        return this;
    }

    public AdminLoginPage enterPassword(String password) {
        type(PASSWORD_INPUT, password);
        return this;
    }

    public WebRestaurantPage submit() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign In")).click();
        waitVisible("aside");
        return new WebRestaurantPage(page);
    }

    /** Convenience method chaining the full flow for the common "happy path" case. */
    public WebRestaurantPage loginWithEmailAndPassword(String email, String password) {
        enterEmail(email);
        enterPassword(password);
        return submit();
    }
}
