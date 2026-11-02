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
    // CONFIRMED live (2026-11-02): a DIFFERENT element from SEARCH_INPUT above - that one lives in
    // the shared header and is dish-only (see searchFor()'s javadoc); this one is the "/restaurants"
    // listing page's OWN search box and genuinely filters its "Open Restaurants Around You" grid by
    // restaurant name (confirmed: typing a restaurant's exact name there narrows it to "Showing 1
    // places"). Its cards are a different custom element too - NEARBY_RESTAURANT_CARD, not RESULT_CARD.
    private static final String RESTAURANTS_PAGE_SEARCH_INPUT = "#search-input";
    private static final String NEARBY_RESTAURANT_CARD = "hopon-food-nearby-restaurant-card";
    // CONFIRMED live (2026-11-02): switched from matching the link's visible text ("Login") to its
    // href - that text already broke this once before (was "Log In" with a space until
    // 2026-10-17, a pure copy change with no route change), exactly the class of fragility a
    // structural attribute avoids. ":visible" picks the real header link over the hidden
    // mobile-menu duplicate confirmed sharing the same href (both exist in the DOM at once).
    // The login MODAL's own submit button is a different element, still "Log In" with a space -
    // see LoginPage, that one has no href to match against so stays text-based.
    private static final String HEADER_LOGIN_LINK_SELECTOR = "a[href='/login']:visible";

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
     * Clicks the header "Restaurants" nav link - its own listing page with a dedicated search
     * box, see searchRestaurantsByName()/openRestaurantFromSearchResults() below. Call this
     * first when searching by restaurant name (see AdminCreatedRestaurantOrderLifecycleTest).
     *
     * CONFIRMED live (2026-11-02): matched by its href (a[href='/restaurants']), not its visible
     * text - the route is a stable implementation detail, the label text ("Restaurants") is
     * cosmetic copy that a redesign could rename without changing the route at all. Same class of
     * bug this whole test chased twice already (the location button's "Use my current location"
     * text going stale, the restaurant-name search assumption) - prefer a structural attribute
     * over display text wherever the frontend actually exposes one.
     */
    public HomePage openRestaurantsTab() {
        page.locator("a[href='/restaurants']").first().click();
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return this;
    }

    /**
     * CONFIRMED live (2026-11-02): types into the "/restaurants" listing page's own search box
     * (#search-input), which genuinely filters its restaurant grid by name - unlike searchFor()'s
     * header box, which only matches dish names (see its javadoc). Call openRestaurantsTab() first.
     */
    public HomePage searchRestaurantsByName(String restaurantName) {
        Locator input = page.locator(RESTAURANTS_PAGE_SEARCH_INPUT);
        input.click();
        input.fill(restaurantName);
        page.waitForTimeout(1500);
        return this;
    }

    /**
     * Clicks the matching card out of the "/restaurants" grid (see searchRestaurantsByName()) by
     * its exact name - CONFIRMED live: lands on that restaurant's own menu page.
     */
    public void openRestaurantFromSearchResults(String restaurantName) {
        page.locator(NEARBY_RESTAURANT_CARD).filter(new Locator.FilterOptions().setHasText(restaurantName))
                .first().click();
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
        waitForStartupRequestsToSettle();
        page.locator(HEADER_LOGIN_LINK_SELECTOR).first().click();
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
        return !page.locator(HEADER_LOGIN_LINK_SELECTOR).first().isVisible();
    }
}
