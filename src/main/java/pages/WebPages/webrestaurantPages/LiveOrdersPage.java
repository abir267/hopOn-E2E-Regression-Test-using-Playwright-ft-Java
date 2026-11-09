package pages.WebPages.webrestaurantPages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import java.util.List;

/**
 * "Live Orders" tab (/live-orders) — CONFIRMED against the live site
 * (2026-09-19, order #01A-FA6 on QA Burger Express [AA]):
 *   - order status tabs are plain buttons "New" / "Preparing" / "Ready" / "Delivery",
 *     each with a count badge; a placed order lands in "New" first.
 *   - clicking an order card by its number opens its detail panel on the right
 *     with actions "Cancel Order" and "Accept & Start Preparing".
 *   - "Accept & Start Preparing" moves the order straight into "Preparing" -
 *     RE-CONFIRMED 2026-09-21: an earlier version of this admin UI had a
 *     second "Preparation Time" modal (10m/20m/30m/45m, "Confirm & Start")
 *     that this click had to open and confirm separately; that modal is GONE
 *     now, the accept click alone is enough. Don't wait for it to reappear.
 *   - "Mark as Ready for Pickup" works standalone even with no driver assigned
 *     yet (still shows "Assigning Rider..." after) - CONFIRMED 2026-09-21,
 *     there's no UI gate requiring a driver first.
 *   - the backend list call (GET .../food-orders/restaurant/on-going) returns
 *     each order's real UUID as "id" alongside its display "orderNo" - this is
 *     the same id the driver-app API needs (see DriverAPI), and is otherwise
 *     not shown anywhere in this UI's visible text.
 */
public class LiveOrdersPage extends AdminBasePage {

    private static final String ACCEPT_BUTTON_NAME = "Accept & Start Preparing";
    private static final String READY_BUTTON_NAME = "Mark as Ready for Pickup";
    private static final String RESTAURANT_STATUS_LABEL = "Restaurant Status";
    private static final String CANCEL_ORDER_BUTTON_NAME = "Cancel Order";
    private static final String CONFIRM_CANCELLATION_BUTTON_NAME = "Confirm Cancellation";
    private static final String OTHER_REASON_DETAILS_PLACEHOLDER = "Provide more details...";
    private final ObjectMapper mapper = new ObjectMapper();

    public LiveOrdersPage(Page page) {
        super(page);
    }

    /**
     * The New/Preparing/Ready/Delivery tabs are plain text, not exposed with
     * an accessible BUTTON role - getByText is what actually matched them
     * during the confirming probe, getByRole(BUTTON) did not.
     */
    public LiveOrdersPage openTab(String tabName) {
        page.getByText(tabName, new Page.GetByTextOptions().setExact(false)).first().click();
        page.waitForTimeout(800);
        return this;
    }

    /** Opens an order's detail panel by clicking its card, matched by display order number (e.g. "01A-FA6"). */
    public LiveOrdersPage openOrder(String orderNo) {
        page.getByText(orderNo, new Page.GetByTextOptions().setExact(false)).first().click();
        page.waitForTimeout(500);
        return this;
    }

    /**
     * Accepts the currently-open "New" order, moving it straight to "Preparing"
     * - see class doc, no confirmation modal to handle anymore. Waits on the
     * actual status-update network response rather than a blind click+sleep,
     * same reasoning as markReadyForPickup() below.
     */
    public LiveOrdersPage acceptAndStartPreparing() {
        page.waitForResponse(
                r -> r.url().contains("food-orders")
                        && ("POST".equals(r.request().method()) || "PUT".equals(r.request().method())),
                () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(ACCEPT_BUTTON_NAME)).click());
        page.waitForTimeout(500);
        return this;
    }

    /**
     * Marks the currently-open "Preparing" order as ready for pickup - this is
     * a server-side gate on the driver side too: the driver-app "PickedUp"
     * acknowledge call (see DriverAPI) returns 400 "Invalid Status" until the
     * RESTAURANT has done this, even after the driver already did "Accepted"
     * and "Arrived" - CONFIRMED by hitting that exact 400 before adding this.
     *
     * Waits on the actual status-update network response instead of a blind
     * click+sleep: the "Auto-refresh Active" polling on this page can re-render
     * the order list/detail while a caller is off doing driver-API calls in
     * between, and a click that lands on a stale/replaced button never fires
     * the update at all - a fixed timeout hid that (CONFIRMED: this raced and
     * PickedUp still 400'd "Invalid Status" even after this click "succeeded").
     * Returns the parsed response so callers can log the real confirmed status
     * instead of assuming the click worked.
     */
    public JsonNode markReadyForPickup() {
        var response = page.waitForResponse(
                r -> r.url().contains("food-orders")
                        && ("POST".equals(r.request().method()) || "PUT".equals(r.request().method())),
                () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(READY_BUTTON_NAME)).click());
        try {
            JsonNode json = mapper.readTree(response.text());
            if (!response.ok()) {
                throw new IllegalStateException("markReadyForPickup failed: " + response.status()
                        + " " + response.statusText() + " - " + json);
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Non-JSON response from markReadyForPickup(): " + response.text(), e);
        }
    }

    /**
     * Reads the delivery PIN off the currently-open order's "Delivery Logistics"
     * panel (shown there as "Pin : 6796") - CONFIRMED the driver-app "PickedUp"
     * acknowledge call (see DriverAPI) 400s with "Invalid Pin" without this,
     * even after the restaurant has marked the order ready.
     */
    public String pickupPin() {
        // The "Pin :" label and its number render as separate sibling elements
        // (CONFIRMED: the label alone has no digits) - read the shared parent's
        // full text instead of just the label.
        String text = page.getByText("Pin", new Page.GetByTextOptions().setExact(false)).first()
                .locator("xpath=..").innerText();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("No digits found in pickup PIN text: '" + text + "'");
        }
        return m.group();
    }

    /**
     * Extends the currently-open "Preparing" order's prep time via the "Need
     * More Time?" modal - CONFIRMED 2026-09-26:
     *   - duration is a set of "+5 Min"/"+10 Min"/"+15 Min"/"+20 Min" buttons
     *     (not exposed with an accessible BUTTON role either - getByText,
     *     same as the tab buttons above) plus a custom-minutes field; +10 Min
     *     is pre-selected by default.
     *   - "Reason for delay" is a REQUIRED plain native &lt;select&gt; (options
     *     "Kitchen is busy" / "Waiting for ingredients" / "Equipment issue" /
     *     "Other") - "Confirm Extension" silently no-ops (modal stays open,
     *     no request fires) if this is left on its placeholder.
     *   - confirming fires a PATCH to .../food-orders/restaurant (no order id
     *     in the path - it's in the body), which still matches the "contains
     *     food-orders" wait used elsewhere in this class once PATCH is
     *     included as an accepted method.
     *   - CONFIRMED effect on the customer side: the delivery time window
     *     shown on OrderTrackingPage.deliveryTimeWindow() shifts by roughly
     *     the extension amount, with no separate "delayed"/"more time"
     *     banner - the shifted window is the only visible signal.
     */
    public LiveOrdersPage extendPreparationTime(String durationButtonText, String reason) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Need More Time")).click();
        page.getByText("Need More Time?").waitFor();
        page.getByText(durationButtonText, new Page.GetByTextOptions().setExact(false)).click();
        page.locator("select").selectOption(reason);
        page.waitForResponse(
                r -> r.url().contains("food-orders")
                        && List.of("POST", "PUT", "PATCH").contains(r.request().method()),
                () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Confirm Extension")).click());
        page.waitForTimeout(500);
        return this;
    }

    /**
     * The "Restaurant Status" / "Accepting Orders" block in the Live Orders
     * header, and its toggle - CONFIRMED 2026-09-23: the toggle itself is a
     * styled clickable div (not a real button/input), the immediate next
     * sibling of the text block's own container. The OFF-state label text was
     * not pre-confirmed by probe - isAcceptingOrders()/statusLabel() read
     * whatever text is actually there rather than assuming a specific string,
     * so callers should compare against the ON label ("Accepting Orders")
     * they already know, not guess the OFF one.
     */
    private Locator restaurantStatusToggle() {
        return page.getByText(RESTAURANT_STATUS_LABEL, new Page.GetByTextOptions().setExact(false))
                .locator("xpath=../following-sibling::div[1]");
    }

    public String restaurantStatusLabel() {
        return page.getByText(RESTAURANT_STATUS_LABEL, new Page.GetByTextOptions().setExact(false))
                .locator("xpath=following-sibling::div[1]").innerText().trim();
    }

    public boolean isAcceptingOrders() {
        return "Accepting Orders".equals(restaurantStatusLabel());
    }

    public LiveOrdersPage toggleRestaurantStatus() {
        restaurantStatusToggle().click();
        page.waitForTimeout(800);
        return this;
    }

    /** Sets Accepting Orders to exactly this, only clicking if it isn't already there. */
    public LiveOrdersPage setAcceptingOrders(boolean accepting) {
        if (isAcceptingOrders() != accepting) {
            toggleRestaurantStatus();
        }
        return this;
    }

    /**
     * Cancels the currently-open "Preparing" order, picking one of the modal's cancellation reasons -
     * CONFIRMED live (2026-10-17): "Cancel Order" on a Preparing order opens a reason picker with four
     * radio-style labels ("Out of stock", "Store is closing", "Customer requested", "Other") plus
     * "Keep Order"/"Confirm Cancellation". "Other" additionally reveals a free-text
     * "Provide more details..." textarea - CONFIRMED NOT required (Confirm Cancellation stayed enabled
     * with it left blank), but this fills it anyway so the real "Other" path is exercised the same way
     * a human tester would use it. On confirm the order drops out of every status tab entirely (there is
     * no "Cancelled" tab in this UI) - CONFIRMED by the detail panel closing back to an empty selection.
     * Waits on the actual status-update network response, same reasoning as markReadyForPickup() - but
     * unlike that call, CONFIRMED live (2026-10-17) this endpoint's response always has "status"/"title"/
     * "message" null on a successful cancel, the same all-null quirk DriverAPI's class doc already notes
     * for the driver-side cancel. The reliable signal here is response.ok() (200) PLUS the order actually
     * leaving the detail panel - isOrderPanelOpen() below confirms that second part.
     */
    public JsonNode cancelOrderWithReason(String reasonLabel) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(CANCEL_ORDER_BUTTON_NAME)).click();
        page.getByText(reasonLabel, new Page.GetByTextOptions().setExact(true)).click();
        if ("Other".equals(reasonLabel)) {
            page.getByPlaceholder(OTHER_REASON_DETAILS_PLACEHOLDER).fill("Automated test cancellation - " + reasonLabel);
        }
        var response = page.waitForResponse(
                r -> r.url().contains("food-orders")
                        && List.of("POST", "PUT", "PATCH").contains(r.request().method()),
                () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(CONFIRM_CANCELLATION_BUTTON_NAME)).click());
        page.waitForTimeout(500);
        try {
            JsonNode json = mapper.readTree(response.text());
            if (!response.ok()) {
                throw new IllegalStateException("cancelOrderWithReason(" + reasonLabel + ") failed: " + response.status()
                        + " " + response.statusText() + " - " + json);
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Non-JSON response from cancelOrderWithReason(): " + response.text(), e);
        }
    }

    /**
     * Expands the currently-open order's "Customer note" accordion (collapsed by default, under
     * Order Items) and returns its text - CONFIRMED live (2026-10-19) this shows the per-item
     * "Special Instructions" text (itemDetails[].foodInstruction, see
     * RestaurantMenuPage.addItemToCartWithSize()'s 3-arg overload), NOT the customer's separate
     * checkout-page "Delivery Instructions" (deliveryInstruction) - that field is confirmed NOT
     * displayed anywhere in this panel. Returns "" if the accordion isn't present (no note on
     * this order) or is present but empty once expanded.
     */
    public String customerNoteText() {
        Locator toggle = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Customer note"));
        if (toggle.count() == 0) {
            return "";
        }
        toggle.first().click();
        page.waitForTimeout(400);
        // CONFIRMED live (2026-10-19): the expanded content is the toggle button's next sibling,
        // a `div.bg-amber-50` holding a "Customer Note" heading and a quoted `<p>` with the text -
        // strip the heading line and the surrounding quotes rather than trusting exact DOM depth.
        Locator panel = toggle.first().locator("xpath=following-sibling::div[contains(@class,'bg-amber-50')]");
        if (panel.count() == 0) {
            return "";
        }
        Locator notePara = panel.first().locator("p");
        if (notePara.count() == 0) {
            return "";
        }
        String last = notePara.first().innerText().trim();
        if (last.length() >= 2 && last.startsWith("\"") && last.endsWith("\"")) {
            last = last.substring(1, last.length() - 1);
        }
        return last;
    }

    /** True while an order's detail panel (Cancel Order / Need More Time / Mark as Ready) is still open. */
    public boolean isOrderPanelOpen() {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(CANCEL_ORDER_BUTTON_NAME)).isVisible();
    }

    /**
     * Looks up an order's real backend UUID by its display order number, by
     * reloading the Live Orders list and reading the id out of the on-going
     * orders response (see class doc) - there is no visible-text source for it.
     */
    public String orderUuidFor(String orderNo) {
        var response = page.waitForResponse(
                r -> r.url().contains("food-orders") && hasBodyContaining(r, orderNo),
                page::reload);
        try {
            JsonNode root = mapper.readTree(response.text());
            JsonNode content = root.at("/data/content");
            if (!content.isArray()) {
                content = root.at("/data");
            }
            for (JsonNode order : content) {
                if (orderNo.equals(order.path("orderNo").asText())) {
                    return order.path("id").asText();
                }
            }
            throw new IllegalStateException("Order " + orderNo + " not found in on-going orders response: " + response.text());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Non-JSON on-going orders response", e);
        }
    }

    // CONFIRMED live (2026-11-09): some food-orders responses (CORS preflight, requests the
    // reload aborts) have no body Chrome keeps, and text() on them throws "No resource with
    // given identifier found" - which escaped the predicate and failed the whole test.
    private static boolean hasBodyContaining(com.microsoft.playwright.Response r, String text) {
        if (r.status() != 200 || !"GET".equals(r.request().method())) {
            return false;
        }
        try {
            return r.text().contains(text);
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return false;
        }
    }
}
