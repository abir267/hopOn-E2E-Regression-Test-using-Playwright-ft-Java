package foodAPIs.orderModule;

import com.microsoft.playwright.Playwright;

/**
 * STUB — no confirmed API contract (Swagger/Postman) was available when this was
 * written. Endpoints below are guesses. MUST be verified against the real backend
 * (see HANDOFF.md §3, Step 3) before removing the UnsupportedOperationException calls.
 */
public class OrderAPI {

    private final Playwright playwright;
    private final String authToken;

    public OrderAPI(Playwright playwright, String authToken) {
        this.playwright = playwright;
        this.authToken = authToken;
    }

    /**
     * GET {api.base.url}/restaurants?area={area}
     * Expected: 200 with a JSON array of restaurants — CONFIRM real shape.
     */
    public String listRestaurants(String area) {
        throw new UnsupportedOperationException(
                "listRestaurants() not implemented — confirm real endpoint/payload with backend team. " +
                "See HANDOFF.md §3 Step 3.");
    }

    /**
     * POST {api.base.url}/orders
     * body: { restaurantId, items[], deliveryAddressId, paymentMethod, notes } — CONFIRM real shape.
     * Expected: 201 with { orderId, status } — CONFIRM real shape.
     */
    public String placeOrder(String restaurantId, String cartPayloadJson) {
        throw new UnsupportedOperationException(
                "placeOrder() not implemented — confirm real endpoint/payload with backend team. " +
                "See HANDOFF.md §3 Step 3.");
    }
}
