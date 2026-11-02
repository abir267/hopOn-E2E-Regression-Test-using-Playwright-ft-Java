package pages.WebPages.customerPages;

import com.microsoft.playwright.Locator;
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
 *   - header login link is `a[href='/login']` (no data-testid), text "Login" (CONFIRMED
 *     changed from "Log In" to "Login", no space, 2026-10-17 — the login MODAL's own submit
 *     button is still "Log In", with a space; don't conflate the two).
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
    // CONFIRMED changed from "Log In" to "Login" (no space) 2026-10-17; the login modal's own
    // submit button is a different element and is still "Log In" with a space - see LoginPage.
    private static final String HEADER_LOGIN_LINK_NAME = "Login";

    public HomePage(Page page) {
        super(page);
    }

    public HomePage open(String baseUrl) {
        page.navigate(baseUrl);
        return this;
    }

    /**
     * Opens the "Change Delivery Location" modal and picks the browser's current geolocation
     * (BaseRunner pins it to delivery.latitude/longitude). This is the only reliable way to
     * land inside a restaurant's delivery radius - see the class javadoc and BaseRunner.
     *
     * CONFIRMED live (2026-11-02, DOM probe): this is a TWO-click flow, not one -
     *   1. button[title='Use Current Location'] (icon only, no visible/accessible text -
     *      "Use my current location" never matched anything here, a stale selector from an
     *      earlier UI) triggers reverse geocoding and fills the address input.
     *   2. Once that resolves, a SEPARATE button[title='Set Location'] (green, arrow icon)
     *      appears and must be clicked to actually confirm/close the modal.
     * Every "stuck backdrop" symptom chased before this (waiting longer, removing the node,
     * disabling its pointer-events) was really just this: step 2 never ran, so the modal
     * never legitimately finished.
     */
    public HomePage setDeliveryLocationToCurrentLocation() {
        click(CURRENT_ADDRESS_LABEL);
        page.locator("button[title='Use Current Location']").click();
        // Reverse geocoding - the confirm button doesn't exist until the address resolves.
        page.locator("button[title='Set Location']").waitFor(new Locator.WaitForOptions().setTimeout(10000));
        // Picking a location auto-closes this modal, which can race a plain click()
        // into retrying against an already-removed button - see clickAllowingSelfRemoval.
        clickAllowingSelfRemoval(
                page.locator("button[title='Set Location']"),
                () -> !page.getByText("Change Delivery Location").isVisible());
        page.waitForTimeout(1500); // restaurant list refetch, no loading indicator to await
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

    /**
     * Clicks the header "Restaurants" nav link (CONFIRMED route /restaurants, live DOM probe
     * 2026-11-01). searchFor()'s #site-search input is the SAME shared header component on
     * every route, but its result cards are only confirmed to render into the "Open
     * Restaurants Around You" grid on THIS route - on the plain "/" home route a search can
     * return zero visible result cards even for a real match. Call this before searchFor()
     * when the caller needs to actually see/click a result card (see
     * AdminCreatedRestaurantOrderLifecycleTest); not needed if only the search request
     * itself matters.
     */
    public HomePage openRestaurantsTab() {
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Restaurants")).first().click();
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return this;
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
        waitForStartupRequestsToSettle();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(HEADER_LOGIN_LINK_NAME)).first().click();
        page.waitForTimeout(1000);
    }

    /**
     * CONFIRMED live (2026-10-05): with geolocation granted the page fires a burst of startup requests
     * (reverse-geocode, food-carts/detail, newAccessToken) that all come back 401 for a logged-out
     * visitor. A "Log In" click that lands in the middle of that burst is sometimes swallowed - the
     * login screen never opens and #phone never renders (reproduced ~1 in 3 loads). Waiting for the
     * network to go quiet first removes the race; the timeout is only a safety net because the app
     * can keep polling.
     */
    private void waitForStartupRequestsToSettle() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(3000));
        } catch (com.microsoft.playwright.PlaywrightException ignored) {
            // still busy after 3s - go ahead anyway
        }
    }

    public boolean isLoggedIn() {
        return !page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(HEADER_LOGIN_LINK_NAME)).first().isVisible();
    }
}
