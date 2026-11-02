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
 * IMPORTANT (CONFIRMED via a full ancestor-chain dump, 2026-09-16): the
 * `<hopon-food-cart-sidebar>` tag is NOT the desktop basket panel a user sees
 * and clicks - it's a second, permanently `display:none` copy nested inside
 * `<hopon-food-header>` (a mobile-drawer variant of the same cart, mirroring
 * the same underlying data). The real, visible basket panel on the restaurant
 * page is a different element entirely. Both copies render identical text
 * content, which is why count()-based presence checks below (isEmpty(),
 * containsItem()) happen to read correctly regardless of which copy they hit -
 * but proceedToCheckout() needs a real click, which only works against the
 * genuinely visible one, so it filters by ":visible" instead of trusting any
 * specific tag.
 */
public class CartPage extends BasePage {

    private static final String EMPTY_BASKET_TEXT = "Your delivery basket is empty";
    private static final String SIDEBAR = "hopon-food-cart-sidebar";
    // RE-CONFIRMED 2026-09-28 (environment drift, not a code bug): this button's
    // copy changed from "PROCEED TO SECURE CHECKOUT" to "Continue to Checkout".
    private static final String PROCEED_TO_CHECKOUT_TEXT = "Continue to Checkout";

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
        page.locator(":visible").filter(new Locator.FilterOptions().setHasText(PROCEED_TO_CHECKOUT_TEXT))
                .last()
                .click();
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
