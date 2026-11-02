package pages.WebPages.customerPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;

/**
 * Restaurant detail/menu page ("/restaurant?id=...").
 *
 * CONFIRMED against the live site (2026-09-15 probe, PizzaClub restaurant):
 *   - restaurant name renders as the <h1> over the hero banner.
 *   - each menu item is a `div[role='button']` card containing an <h4> name and
 *     a single <button> ("+") that adds it to cart — no data-testid on either.
 *   - if the active delivery location is outside this restaurant's delivery
 *     radius, clicking "+" opens an "Restaurant is unavailable" modal instead of
 *     adding anything — see HomePage.setDeliveryLocationToCurrentLocation(),
 *     which must run before reaching this page.
 *   - no item-customization modal was observed on PizzaClub's menu; items add
 *     straight to cart on click.
 *   - CONFIRMED separately: if the account's cart already holds items from a
 *     DIFFERENT restaurant, adding an item here instead opens a "Start a New
 *     Order?" confirmation ("Your cart already contains items from
 *     {other restaurant}...") with "Yes, Start New Order" / "No, Keep Current
 *     Cart" - easy to hit under automation since test runs reuse a small pool
 *     of phone numbers across different restaurants. Both add-to-cart methods
 *     below click through it automatically when it appears.
 */
public class RestaurantMenuPage extends BasePage {

    private static final String MENU_ITEM_CARD = "div[role='button']";
    private static final String UNAVAILABLE_MODAL_TEXT = "Restaurant is unavailable";
    private static final String START_NEW_ORDER_MODAL_TEXT = "Start a New Order?";
    private static final String START_NEW_ORDER_CONFIRM_BUTTON = "Yes, Start New Order";

    public RestaurantMenuPage(Page page) {
        super(page);
    }

    public String restaurantTitle() {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setLevel(1)).first().innerText().trim();
    }

    private Locator menuItemCardByName(String itemName) {
        return page.locator(MENU_ITEM_CARD)
                .filter(new Locator.FilterOptions().setHas(page.locator("h4", new Page.LocatorOptions().setHasText(itemName))));
    }

    /**
     * CONFIRMED 2026-09-28 (environment drift, not a code bug): this menu's
     * category tabs only render the ACTIVE tab's items in the DOM at all (not
     * just hidden via CSS) - adding a new category ("Student meal") that
     * became the default-selected tab silently made every item in the other
     * category ("Burgerbb1", including "Crispy Burger") disappear from every
     * test that assumed the default tab.
     *
     * CONFIRMED there is no separate "All items across categories" tab here -
     * the exact-text "All" button that exists on this page is the reviews
     * section's star-rating filter chip further down, NOT a menu category (a
     * first probe wrongly assumed it was and clicking it did nothing for
     * visibility). So this tries each category tab name in turn - starting
     * with "Burgerbb1" (Crispy Burger's real category, per the admin panel)
     * - rather than a single generic click.
     */
    private static final String[] KNOWN_CATEGORY_TAB_NAMES = {"Burgerbb1", "Student meal"};

    private void ensureItemVisible(String itemName) {
        if (menuItemCardByName(itemName).count() > 0) {
            return;
        }
        for (String category : KNOWN_CATEGORY_TAB_NAMES) {
            try {
                page.getByText(category, new Page.GetByTextOptions().setExact(true)).first()
                        .click(new Locator.ClickOptions().setTimeout(3000));
                page.waitForTimeout(600);
                if (menuItemCardByName(itemName).count() > 0) {
                    return;
                }
            } catch (PlaywrightException noSuchTab) {
                // This category tab doesn't exist on this restaurant's menu - try the next one.
            }
        }
    }

    /**
     * Clicks through the "Start a New Order?" cart-switch confirmation if it
     * appears (see class doc) - a short wait, not a hard requirement, since
     * most adds never trigger it. Always replaces the existing cart; there is
     * no test case yet that wants "No, Keep Current Cart" instead.
     */
    private void confirmStartNewOrderIfPrompted() {
        try {
            page.getByText(START_NEW_ORDER_MODAL_TEXT).waitFor(new Locator.WaitForOptions().setTimeout(3000));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(START_NEW_ORDER_CONFIRM_BUTTON)).click();
        } catch (PlaywrightException notPrompted) {
            // Modal never appeared - nothing to confirm, the item just added normally.
        }
    }

    public RestaurantMenuPage addItemToCart(String itemName) {
        // CONFIRMED (network trace): the "+" click POSTs to a "food-cart-items"
        // endpoint. Waiting for THAT response instead of any cart-sidebar DOM
        // state is deliberate: the sidebar's re-render after adding an item is
        // flaky under automation timing - observed both a "Loading cart..."
        // interstitial of variable length and, separately, an item's row getting
        // stuck invisible (looks like an Angular enter-animation race), while
        // manual/slower-paced runs never show either problem. The network
        // response is the one signal that's actually authoritative and stable.
        ensureItemVisible(itemName);
        page.waitForResponse(
                response -> response.url().contains("food-cart-items") && "POST".equals(response.request().method()),
                () -> {
                    menuItemCardByName(itemName).first().locator("button").click();
                    confirmStartNewOrderIfPrompted();
                });
        // Give the UI a brief moment to finish re-rendering off that response
        // before a caller reads CartPage.
        page.waitForTimeout(500);
        return this;
    }

    /**
     * Some items (CONFIRMED on QA Burger Express [AA]'s "Crispy Burger", unlike
     * PizzaClub) open a "Choose options" modal on "+" instead of adding straight
     * to cart - a REQUIRED "SIZE" radio group (e.g. Regular/Medium/Small, each a
     * different price) plus a quantity stepper and its own "Add to Cart" button.
     * Use this overload for those items instead of the modal-less addItemToCart().
     */
    public RestaurantMenuPage addItemToCartWithSize(String itemName, String sizeLabel) {
        return addItemToCartWithSize(itemName, sizeLabel, null);
    }

    /**
     * Same as {@link #addItemToCartWithSize(String, String)}, additionally filling the modal's
     * per-item "Special Instructions" textarea (placeholder "e.g. no mayo/olives") when
     * {@code specialInstructions} is non-null - CONFIRMED live (2026-10-19): this text is stored
     * server-side as itemDetails[].foodInstruction, and is the ONLY thing shown under the
     * restaurant-admin order panel's "Customer note" accordion (LiveOrdersPage.customerNoteText())
     * - the checkout page's separate CheckoutPage.addOrderNotes() ("Delivery Instructions") is a
     * different field (deliveryInstruction) that is NOT surfaced anywhere in that admin UI at all,
     * confirmed by diffing the full panel text with and without it - see
     * RestaurantAndDriverSeeCustomerInstructionsTest for how both are cross-checked against what
     * the restaurant and driver actually receive.
     */
    public RestaurantMenuPage addItemToCartWithSize(String itemName, String sizeLabel, String specialInstructions) {
        ensureItemVisible(itemName);
        menuItemCardByName(itemName).first().locator("button").click();
        page.getByText("SIZE").waitFor();
        page.getByText(sizeLabel, new Page.GetByTextOptions().setExact(true)).click();
        if (specialInstructions != null) {
            page.getByPlaceholder("e.g. no mayo/olives").fill(specialInstructions);
        }
        // CONFIRMED (network trace, phone reused across test runs from the small
        // riderlist.csv pool): if this exact item+size is ALREADY in the account's
        // cart from an earlier run, "Add to Cart" fires a PUT to "food-carts"
        // (quantity update) instead of a POST to "food-cart-items" (new line) -
        // waiting on the POST alone hangs forever for a repeat add, so both are
        // accepted here.
        page.waitForResponse(
                response -> ("POST".equals(response.request().method()) && response.url().contains("food-cart-items"))
                        || ("PUT".equals(response.request().method()) && response.url().contains("food-carts")),
                () -> {
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add to Cart")).click();
                    confirmStartNewOrderIfPrompted();
                });
        page.waitForTimeout(500);
        return this;
    }

    /**
     * General "Choose options" modal handler for items with MULTIPLE required option
     * groups plus a quantity stepper - CONFIRMED live (2026-10-27, QA Burger Express
     * [AA]'s "Crispy Burger" on the "Burgerbb1" tab): the modal can render more than
     * one required group (there, "SIZE" and a second custom-named group) stacked
     * above the same "Special Instructions" textarea, "Quantity" stepper
     * (+/- buttons with aria-label "Increase quantity"/"Decrease quantity", starting
     * at 1) and "Add to Cart" button seen in {@link #addItemToCartWithSize}. Each
     * option is still picked by its own exact label text (CONFIRMED unique per
     * modal regardless of which group it belongs to), so one generic method covers
     * any number of required groups without needing to know their group headings.
     *
     * CONFIRMED live (2026-10-27, same probe): a required group can already have an
     * option pre-selected (its indicator is a plain div styled with the
     * "border-emerald-500"/"bg-emerald-500" classes when selected - there is no
     * aria-checked, this is a custom toggle, not a real radio input). Clicking an
     * option that is ALREADY selected toggles it back OFF instead of being a no-op,
     * which leaves that required group unsatisfied and permanently disables both the
     * quantity stepper and "Add to Cart" - reproduced live, it just hangs until
     * Playwright's click retry times out. So each option here is only clicked if its
     * indicator isn't already filled in.
     *
     * @param optionLabels exact label text of ONE option per required group, e.g. ["Small", "dsdsd"]
     * @param quantity     final quantity to reach by clicking "+" from the modal's default of 1 (1 = leave as-is)
     * @param specialInstructions fills the per-item instructions textarea when non-null, same field addItemToCartWithSize() uses
     */
    public RestaurantMenuPage addItemToCartWithOptions(String itemName, java.util.List<String> optionLabels, int quantity, String specialInstructions) {
        ensureItemVisible(itemName);
        menuItemCardByName(itemName).first().locator("button").click();
        page.getByText("Quantity", new Page.GetByTextOptions().setExact(true)).waitFor();

        for (String optionLabel : optionLabels) {
            Locator label = page.getByText(optionLabel, new Page.GetByTextOptions().setExact(true));
            Locator indicator = label.locator("xpath=preceding-sibling::div[1]");
            String indicatorClass = indicator.count() > 0 ? indicator.first().getAttribute("class") : null;
            boolean alreadySelected = indicatorClass != null && indicatorClass.contains("border-emerald-500");
            if (!alreadySelected) {
                label.click();
            }
        }

        if (quantity > 1) {
            Locator increaseButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Increase quantity"));
            for (int i = 1; i < quantity; i++) {
                increaseButton.click();
            }
        }

        if (specialInstructions != null) {
            page.getByPlaceholder("e.g. no mayo/olives").fill(specialInstructions);
        }

        // Same dual-wait as addItemToCartWithSize(): a repeat add of the same item+options
        // updates the existing cart line (PUT food-carts) instead of creating a new one
        // (POST food-cart-items).
        page.waitForResponse(
                response -> ("POST".equals(response.request().method()) && response.url().contains("food-cart-items"))
                        || ("PUT".equals(response.request().method()) && response.url().contains("food-carts")),
                () -> {
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add to Cart")).click();
                    confirmStartNewOrderIfPrompted();
                });
        page.waitForTimeout(500);
        return this;
    }

    /**
     * Raw "+" click with no assumption about what happens next - unlike
     * addItemToCart()/addItemToCartWithSize(), which each wait for a specific
     * outcome (a cart network response, a SIZE modal). Use this when the
     * outcome itself is what's under test (e.g. confirming an out-of-area
     * location blocks the add with "Restaurant is unavailable" instead of
     * opening the size picker).
     */
    public RestaurantMenuPage clickAddButton(String itemName) {
        ensureItemVisible(itemName);
        menuItemCardByName(itemName).first().locator("button").click();
        page.waitForTimeout(800);
        return this;
    }

    /**
     * Clicks a menu category tab by its exact visible name. Unlike ensureItemVisible()'s
     * KNOWN_CATEGORY_TAB_NAMES (hardcoded for one fixed restaurant), this is for restaurants
     * whose category name is only known at runtime - e.g. one admin-portal-automation just
     * created (see AdminCreatedRestaurantOrderLifecycleTest). Swallows a missing tab: a
     * brand-new restaurant's one-and-only category may already be the active/default tab.
     *
     * CONFIRMED live (2026-11-02): a brand-new restaurant's item list can still be mid
     * enter-transition after this - Playwright's own actionability check passes (no layout
     * shift to detect) but the list's wrapper div still intercepts the "+" click underneath
     * for a while. 600ms wasn't enough; give it longer.
     */
    public RestaurantMenuPage openCategoryTab(String categoryName) {
        try {
            page.getByText(categoryName, new Page.GetByTextOptions().setExact(true)).first()
                    .click(new Locator.ClickOptions().setTimeout(5000));
            page.waitForTimeout(2500);
        } catch (PlaywrightException notFound) {
            // Already on the only/active tab - nothing to switch.
        }
        return this;
    }

    public boolean isRestaurantUnavailable() {
        return page.getByText(UNAVAILABLE_MODAL_TEXT).isVisible();
    }

    public void dismissUnavailableModal() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Got it")).click();
    }
}
