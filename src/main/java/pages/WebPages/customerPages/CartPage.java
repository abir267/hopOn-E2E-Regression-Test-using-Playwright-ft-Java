package pages.WebPages.customerPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * "YOUR BASKET" panel — CONFIRMED against the live site (2026-09-15 probe).
 *
 * Unlike the other page objects this isn't a dedicated route: it's a side
 * panel that renders on both the dashboard ("/home") and a restaurant's menu
 * page whenever the cart is non-empty. There is no reachable "/cart" URL —
 * navigating there directly redirects back to "/home" (client-side route
 * guard, not a real page), so this panel must always be read/acted on from
 * whichever page it's currently rendered on.
 *
 * CORRECTED (2026-11-03, live DOM probe - the site now genuinely has TWO separate cart
 * surfaces, confirmed by dumping both independently):
 *   - `<hopon-food-cart>` - the in-page "YOUR BASKET" panel on the restaurant's own menu page
 *     (desktop-width only, `hidden lg:block`). This is the one with real item-level detail
 *     (name, add-ons, price, qty stepper) - CONFIRMED its empty state now reads "Your cart is
 *     empty" (changed from "Your delivery basket is empty"). isEmpty()/containsItem()/
 *     couponStatus() below read THIS one.
 *   - `<hopon-food-cart-sidebar>` - a newer "All carts" slide-over overlay (class `cs-panel
 *     cs-open` when open), restaurant-level summary only (name, price, a generic "Add" stepper)
 *     - no item name text anywhere in it, CONFIRMED by dumping its full innerText. A 2026-09-16
 *     probe found this tag permanently `display:none` (a hidden mobile-drawer duplicate back
 *     then); it's since become the real, visible, functioning checkout entry point - CONFIRMED
 *     by clicking its "Go to checkout" button and landing on a working /checkout page with the
 *     correct order summary. proceedToCheckout() below targets this one.
 */
public class CartPage extends BasePage {

    private static final String EMPTY_BASKET_TEXT = "Your cart is empty";
    private static final String SIDEBAR = "hopon-food-cart";
    // CONFIRMED live (2026-11-03): the checkout button's own visible copy changed at least twice
    // now ("PROCEED TO SECURE CHECKOUT" -> "Continue to Checkout" -> "Go to checkout") -
    // matching its icon component's name instead (an svg icon library identifier, not display
    // copy) so a future wording-only redesign doesn't break this again. Scoped inside the "All
    // carts" overlay tag (not matched page-wide) since nothing else guarantees this icon name is
    // unique elsewhere - see the class javadoc for why this is a different tag from SIDEBAR above.
    private static final String CART_OVERLAY = "hopon-food-cart-sidebar";
    private static final String CHECKOUT_BUTTON_SELECTOR = CART_OVERLAY + " button:has(lucide-icon[name='ArrowRight'])";

    public CartPage(Page page) {
        super(page);
    }

    private Locator sidebar() {
        return page.locator(SIDEBAR);
    }

    public boolean isEmpty() {
        return sidebar().getByText(EMPTY_BASKET_TEXT).count() > 0;
    }

    public boolean containsItem(String itemName) {
        return sidebar().getByText(itemName).count() > 0;
    }

    public CheckoutPage proceedToCheckout() {
        page.locator(CHECKOUT_BUTTON_SELECTOR).first().click();
        page.waitForLoadState();
        return new CheckoutPage(page);
    }

    private static final String NO_OFFERS_TEXT = "No offers available for this order";
    private static final String APPLY_COUPON_TOGGLE = "APPLY COUPON";

    /**
     * Read-only: expands the "APPLY COUPON" panel if it's collapsed, reports whether a coupon
     * is already applied or any offer is available, then leaves it alone - never types a promo
     * code or clicks an offer/Apply itself (no confirmed code/offer to apply against, and
     * guessing at that interaction risks corrupting the order total). See
     * AdminCreatedRestaurantOrderLifecycleTest for the caller.
     */
    public String couponStatus() {
        boolean alreadyExpanded = page.getByPlaceholder("ENTER PROMO CODE").isVisible()
                || sidebar().getByText(NO_OFFERS_TEXT).isVisible();
        if (!alreadyExpanded) {
            Locator toggle = page.locator(":visible").filter(new Locator.FilterOptions().setHasText(APPLY_COUPON_TOGGLE)).first();
            if (toggle.count() > 0) {
                toggle.click();
                page.waitForTimeout(500);
            }
        }
        if (sidebar().getByText(NO_OFFERS_TEXT).isVisible()) {
            return "no coupon applied (no offers available for this order)";
        }
        if (page.getByPlaceholder("ENTER PROMO CODE").isVisible()) {
            return "coupon panel open, no offer text found (promo code field empty/untouched)";
        }
        return "could not determine coupon panel state";
    }
}
