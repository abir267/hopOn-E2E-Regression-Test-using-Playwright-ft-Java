package pages.WebPages.customerPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;

import java.util.regex.Pattern;

/**
 * Checkout page ("/checkout") — CONFIRMED against the live site (2026-09-15
 * probe: PizzaClub -> Lahmacun, Middle East_86 -> checkout -> Place Secure
 * Order -> real order created, e.g. order #019-A29).
 *
 * IMPORTANT: this route is guarded client-side — navigating to "/checkout"
 * directly (page.navigate) redirects back to "/home" even with items in the
 * cart. It must be reached by clicking CartPage.proceedToCheckout().
 *
 * Shipping address defaults to whatever HomePage.setDeliveryLocationToCurrentLocation()
 * set, pre-selected and labelled "Current Location" — no action needed for the
 * happy path. Payment method defaults to "Cash on Delivery".
 *
 * IMPORTANT (CONFIRMED): the "Awaiting Confirmation!" text/heading and the
 * "Track My Order" link both also appear a second time in an "Active Orders
 * Status" header widget whenever the logged-in account already has another
 * order in flight (easy to hit here since test runs place many real orders on
 * riderlist.csv accounts) - an unscoped match throws a strict-mode "resolved
 * to 2 elements" error. Everything below is scoped to the real confirmation
 * modal, `<hopon-food-order-success>`, to only ever match THIS order's modal.
 */
public class CheckoutPage extends BasePage {

    // RE-CONFIRMED 2026-09-28 (environment drift, not a code bug): this
    // button's copy changed from "Place Secure Order" to "Place Order",
    // alongside other checkout-page cosmetic changes (a "Flash" delivery
    // option, tip amount buttons) seen in the same probe.
    private static final String PLACE_ORDER_BUTTON_NAME = "Place Order";
    private static final String TRACK_ORDER_LINK_NAME = "Track My Order";
    private static final String AWAITING_CONFIRMATION_TEXT = "Awaiting Confirmation";
    /**
     * CONFIRMED live (2026-11-16): the modal no longer always opens on "Awaiting Confirmation" - a fresh order can come up as
     * "Preparing your food" within a second (the customer also gets a Preparing push) with nobody having accepted it, because
     * the platform/restaurant now accepts it by itself. Both headings mean the order was placed.
     */
    private static final Pattern ORDER_PLACED_HEADING = Pattern.compile(
            AWAITING_CONFIRMATION_TEXT + "|Preparing your food", Pattern.CASE_INSENSITIVE);
    private static final String ORDER_SUCCESS_MODAL = "hopon-food-order-success";

    public CheckoutPage(Page page) {
        super(page);
    }

    private Locator confirmationModal() {
        return page.locator(ORDER_SUCCESS_MODAL);
    }

    public CheckoutPage selectPaymentMethod(String methodLabel) {
        page.getByText(methodLabel, new Page.GetByTextOptions().setExact(false)).first().click();
        return this;
    }

    public CheckoutPage selectDeliveryOption(String optionLabel) {
        page.getByText(optionLabel, new Page.GetByTextOptions().setExact(false)).first().click();
        return this;
    }

    public CheckoutPage addOrderNotes(String notes) {
        page.getByPlaceholder("For example: call on arrival, use the side entrance...").fill(notes);
        return this;
    }

    /** Clicks "Place Order" and waits for the confirmation modal: "Awaiting Confirmation" or "Preparing your food". */
    public CheckoutPage placeOrder() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(PLACE_ORDER_BUTTON_NAME)).click();
        try {
            confirmationModal().getByText(ORDER_PLACED_HEADING).first().waitFor();
        } catch (TimeoutError e) {
            // Say what the modal really showed instead of a bare 30s "waiting for locator(...)".
            String shown;
            try {
                shown = confirmationModal().first().innerText(new Locator.InnerTextOptions().setTimeout(1000)).replaceAll("\\s+", " ").trim();
            } catch (RuntimeException noModal) {
                shown = "(no <" + ORDER_SUCCESS_MODAL + "> on the page at all - the order may not have been placed; check for a "
                        + "[BUG][CUSTOMER] line above)";
            }
            throw new IllegalStateException("After \"" + PLACE_ORDER_BUTTON_NAME + "\" the confirmation modal never showed "
                    + "\"Awaiting Confirmation\" or \"Preparing your food\". It showed: " + shown, e);
        }
        return this;
    }

    public boolean isOrderConfirmed() {
        return confirmationModal().getByText(ORDER_PLACED_HEADING).first().isVisible();
    }

    /** Which heading the confirmation modal opened on: "Awaiting Confirmation" or "Preparing your food" (already accepted). */
    public String orderStatusHeading() {
        return confirmationModal().getByText(Pattern.compile(AWAITING_CONFIRMATION_TEXT, Pattern.CASE_INSENSITIVE)).count() > 0
                ? AWAITING_CONFIRMATION_TEXT : "Preparing your food";
    }

    /**
     * Reads the order number (e.g. "019-A29") off the confirmation modal's
     * "Track My Order" link — its href is "/orders/track/{id}?orderNo={orderNo}",
     * which is a steadier source than matching the badge's display text/styling.
     */
    public String orderId() {
        String href = confirmationModal()
                .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(TRACK_ORDER_LINK_NAME))
                .getAttribute("href");
        int i = href.indexOf("orderNo=");
        return i == -1 ? href : href.substring(i + "orderNo=".length());
    }

    /** Follows the "Track My Order" link out of the confirmation modal onto the tracking page. */
    public OrderTrackingPage trackOrder() {
        confirmationModal().getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(TRACK_ORDER_LINK_NAME)).click();
        page.waitForLoadState();
        return new OrderTrackingPage(page);
    }
}
