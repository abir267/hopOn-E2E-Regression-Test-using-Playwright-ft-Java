package pages.WebPages.customerPages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Order tracking page ("/orders/track/{orderId}?orderNo={orderNo}") — CONFIRMED
 * against the live site (2026-09-15 probe, order #019-A29): status stepper
 * (Order Placed -> Food Cooking -> Ready For Pickup -> On The Way -> Delivered),
 * a live map, order summary with the paid total, and a "Cancel Order" action
 * that's only valid for the first few minutes after placing.
 *
 * Reached via CheckoutPage.trackOrder() — there is no independent page object
 * method to navigate here directly outside of that link, since the order id
 * in the URL is only known after a real order is placed.
 */
public class OrderTrackingPage extends BasePage {

    private static final String CANCEL_ORDER_TEXT = "Cancel Order";
    private static final String DOWNLOAD_INVOICE_BUTTON_NAME = "Download Official Invoice";

    public OrderTrackingPage(Page page) {
        super(page);
    }

    /** Reads the "Order ID #019-A29" heading and returns just the order number. */
    public String orderId() {
        String heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Order ID")).innerText();
        return heading.replace("Order ID", "").replace("#", "").trim();
    }

    public String orderStatusLabel() {
        return page.locator("span.bg-slate-200, span.bg-emerald-100").first().innerText().trim();
    }

    /**
     * CONFIRMED both the status badge near the top and the step's own label in
     * the stepper legitimately show the same exact text (e.g. "Order Placed"
     * appears twice once that step is reached) - .first() is intentional, not
     * a workaround for noise, since either match confirms the same true state.
     */
    public boolean isStepReached(String stepLabel) {
        return page.getByText(stepLabel, new Page.GetByTextOptions().setExact(true)).first().isVisible();
    }

    public boolean isCancelOrderAvailable() {
        return page.getByText(CANCEL_ORDER_TEXT, new Page.GetByTextOptions().setExact(false)).isVisible();
    }

    public boolean isDownloadInvoiceAvailable() {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(DOWNLOAD_INVOICE_BUTTON_NAME)).isVisible();
    }

    /**
     * Reads the displayed delivery time window (e.g. "02:20 PM - 02:29 PM") -
     * CONFIRMED 2026-09-26: this is what actually shifts when the restaurant
     * extends its preparation time (LiveOrdersPage.extendPreparationTime()),
     * with no separate "delay"/"more time" banner shown to the customer -
     * the updated window is the only visible signal. A regex text locator
     * (not a plain substring like "PM") avoids matching unrelated text
     * elsewhere on the page that happens to contain "PM".
     */
    public String deliveryTimeWindow() {
        return page.locator("text=/\\d{1,2}:\\d{2}\\s?(AM|PM)\\s*-\\s*\\d{1,2}:\\d{2}\\s?(AM|PM)/")
                .first().innerText().trim();
    }

    // ---- Rider chat (CONFIRMED live 2026-10-05): the "Chat with Rider" button only appears once a
    // driver has accepted the order. The panel has quick-reply buttons ("Coming", "Wait for few
    // minutes", "I am at pickup point", "I am coming..."), a "Type a message..." box and an
    // icon-only send button right after it. Messages go out over MQTT (topic ride/message) and are
    // stored by GET /services/ride/api/message/{orderUuid}.

    private com.microsoft.playwright.Locator chatInput() {
        return page.getByPlaceholder("Type a message...");
    }

    public OrderTrackingPage openRiderChat() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Chat with Rider")).click();
        chatInput().waitFor();
        return this;
    }

    public OrderTrackingPage sendChatMessage(String text) {
        chatInput().fill(text);
        chatInput().locator("xpath=following::button[1]").click();
        return this;
    }

    public OrderTrackingPage sendQuickReply(String label) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label).setExact(true)).click();
        return this;
    }

    /** Waits up to timeoutSeconds for a chat bubble with exactly this text to show in the open chat panel. */
    public boolean chatShows(String text, int timeoutSeconds) {
        try {
            page.getByText(text, new Page.GetByTextOptions().setExact(true)).last()
                    .waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(timeoutSeconds * 1000.0));
            return true;
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return false;
        }
    }
}
