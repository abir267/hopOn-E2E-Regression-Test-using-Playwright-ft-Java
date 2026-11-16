package pages.WebPages.webrestaurantPages;

import com.microsoft.playwright.Locator;
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
     * Not a bare page.navigate(url): that waits for the full "load" event, which the dev portal sometimes never fires within
     * 30s (slow third-party/map/font resources). The login form only needs the DOM, so wait for DOMCONTENTLOADED and allow 60s.
     * ONE attempt: when it fails the error is printed and the test stops (user instruction 2026-11-16 - no automatic retries,
     * the user reruns by hand).
     */
    private static void navigateToLogin(Page page) {
        page.navigate(ConfigReader.restaurantAdminUrl(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(60000));
    }

    /**
     * Seen live, intermittently: the portal page loads but the Angular login form never renders. CONFIRMED from the stuck
     * page's own text: Chrome shows "This site can't be reached - dev-admin.hoponbd.com took too long to respond" (the login
     * redirects to that shared auth host, which drops connections in bursts of a few minutes while curl still reaches it).
     * One wait, then the real reason is printed (screenshot + the page's text) and the test stops.
     */
    private void ensureLoginFormRendered() {
        try {
            page.locator(EMAIL_INPUT).waitFor(new Locator.WaitForOptions().setTimeout(30000));
        } catch (TimeoutError notRendered) {
            String shown = describeStuckPage();
            throw new IllegalStateException("Restaurant portal login form did not render within 30s at " + page.url()
                    + ". The page showed: " + shown, notRendered);
        }
    }

    /** What the page really shows when the form is missing: a screenshot under target/ plus the visible text. Returns the text. */
    private String describeStuckPage() {
        try {
            java.nio.file.Path shot = java.nio.file.Paths.get("target", "restaurant-login-stuck.png");
            page.screenshot(new Page.ScreenshotOptions().setPath(shot));
            String text = page.locator("body").innerText().replaceAll("\\s+", " ").trim();
            String shortText = text.length() > 200 ? text.substring(0, 200) + "..." : text;
            System.out.println("[RESTAURANT] Login page stuck - screenshot " + shot.toAbsolutePath() + " | text: " + shortText);
            utils.BugReport.environment("RESTAURANT", "restaurant portal login page " + page.url(),
                    "Login form never rendered - the page showed: " + shortText);
            return shortText;
        } catch (RuntimeException e) {
            return "(could not read the page: " + e.getMessage() + ")";
        }
    }

    public WebRestaurantLogin enterEmail(String email) {
        ensureLoginFormRendered();
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
