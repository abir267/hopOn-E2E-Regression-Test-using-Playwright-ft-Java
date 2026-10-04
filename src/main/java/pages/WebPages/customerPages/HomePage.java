package pages.WebPages.customerPages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;

/**
 * App shell shared by the pre-login landing page ("/") and the post-login
 * dashboard ("/home") — header (location picker, search, basket, account menu)
 * plus whatever restaurant grid is currently rendered below it.
 *
 * CONFIRMED against the live site (2026-09-15 probe of https://dev-food.hoponbd.com,
 * logged in via a riderlist.csv phone + OTP 1122 — see LoginPage):
 *   - search input: no id/data-testid, matched by its placeholder text.
 *   - header login link is `a[href='/login']` (no data-testid), text "Log In".
 *   - delivery location control is `#app-current-address` (a <span> inside a
 *     clickable header control); clicking it opens a "Change Delivery Location"
 *     modal with a "Use my current location" button (browser geolocation — see
 *     BaseRunner) and a list of saved-address buttons matched by their label text.
 *   - restaurant search results render as `<hopon-food-search-result-card>`
 *     custom elements; matched by visible text (restaurant name), not an id.
 *   - IMPORTANT: a restaurant's items only add to cart if the active delivery
 *     location is within that restaurant's delivery radius — always call
 *     setDeliveryLocationToCurrentLocation() before searching/ordering (see
 *     BaseRunner's geolocation setup).
 */
public class HomePage extends BasePage {

    private static final String CURRENT_ADDRESS_LABEL = "#app-current-address";
    private static final String SEARCH_INPUT = "input[placeholder*='Search for restaurants']";
    private static final String RESULT_CARD = "hopon-food-search-result-card";

    public HomePage(Page page) {
        super(page);
    }

    public HomePage open(String baseUrl) {
        page.navigate(baseUrl);
        return this;
    }

    /**
     * Opens the "Change Delivery Location" modal and picks "Use my current location"
     * (the browser geolocation BaseRunner pins to delivery.latitude/longitude).
     * This is the only reliable way to land inside a restaurant's delivery radius —
     * see the class javadoc and BaseRunner.
     */
    public HomePage setDeliveryLocationToCurrentLocation() {
        click(CURRENT_ADDRESS_LABEL);
        // Picking a location auto-closes this modal, which can race a plain click()
        // into retrying against an already-removed button - see clickAllowingSelfRemoval.
        clickAllowingSelfRemoval(
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Use my current location")),
                () -> !page.getByText("Change Delivery Location").isVisible());
        page.waitForTimeout(1500); // reverse-geocoding + restaurant list refetch, no loading indicator to await
        return this;
    }

    /** Picks a previously saved address (e.g. "Home") from the location modal by its label. */
    public HomePage setDeliveryLocationSaved(String addressLabel) {
        click(CURRENT_ADDRESS_LABEL);
        clickAllowingSelfRemoval(
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(addressLabel)),
                () -> !page.getByText("Change Delivery Location").isVisible());
        page.waitForTimeout(1500);
        return this;
    }

    /**
     * CONFIRMED backend bug (2026-09-16 network trace): typing into this box
     * POSTs {"query": "<text>", "searchType": "FOOD"} to global-search - i.e. it
     * searches DISH names, not restaurant names. Searching a restaurant's own
     * name (e.g. "PizzaClub"/"Pizza Club") legitimately returns dataCount: 0
     * from the backend when nothing on any menu happens to be named that,
     * regardless of query text or spacing. This is not request-level flakiness
     * (confirmed via raw response body), it's the wrong searchType for this use
     * case - there is no discovered UI control to switch it to a restaurant-name
     * search. Prefer openRestaurantById() for reliably reaching a known
     * restaurant; use this method only when testing dish search itself.
     */
    public HomePage searchFor(String query) {
        type(SEARCH_INPUT, query);
        page.waitForTimeout(1500);
        return this;
    }

    /**
     * Navigates straight to a restaurant's menu page by id, bypassing the
     * search box entirely - see searchFor()'s javadoc for why: search only
     * matches dish names, not restaurant names, so it can't reliably locate a
     * restaurant by its own name. A real user can equally land here via a
     * shared link, so this is still a legitimate path through the app, not a
     * test-only shortcut.
     */
    public void openRestaurantById(String baseUrl, String restaurantId) {
        page.navigate(baseUrl + "/restaurant?id=" + restaurantId);
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    public void openRestaurantByName(String restaurantName) {
        page.locator(RESULT_CARD).filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(restaurantName))
                .first()
                .click();
        // Client-side route change: the menu page's heading/items only get their real
        // content after its restaurant-detail fetch resolves, so callers reading them
        // right away (RestaurantMenuPage.restaurantTitle()) can otherwise race a stale
        // or empty render.
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    public int searchResultCount() {
        return page.locator(RESULT_CARD).count();
    }

    public boolean isRestaurantListVisible() {
        return isVisible(RESULT_CARD);
    }

    /**
     * CONFIRMED live (2026-10-03): a manual click-through of this exact flow never
     * reproduces the login-form instability seen under automation (element churn on
     * "#phone"/"#otp-*") - confirming the live site itself is fine, and the gap is
     * purely timing: a human always leaves a beat between navigating here and
     * touching the form, which the Angular app's zoneless change detection apparently
     * needs to finish settling. A short explicit wait here, not just another
     * selector-visibility wait, gives scripted interaction that same beat.
     */
    public void openLogin() {
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Log In")).first().click();
        page.waitForTimeout(1000);
    }

    public boolean isLoggedIn() {
        return !page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Log In")).first().isVisible();
    }
}
