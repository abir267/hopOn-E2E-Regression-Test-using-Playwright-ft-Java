package foodAPIs.driverModule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import foodAPIs.headerManager;

import java.util.Map;

/**
 * Driver-app order-status API — CONFIRMED against a real device capture
 * (2026-09-19, HTTP Toolkit proxy on the live driver app, order 01A-DB0):
 *
 *   POST {driverApiBaseUrl}/services/food/api/food-orders/driver/acknowledge
 *   body: { "orderId": "<uuid>", "status": "Accepted" }
 *   headers: see headerManager.driverHeaders()
 *   200 response: { "title", "detail", "data": { "content": { ...order, "status": "Accepted", "nextStatus": "Arrived", ... } } }
 *
 * The SAME endpoint is reused for every driver-side status step, just with a
 * different "status" value each time - a real capture showed the driver app
 * calling it three times back to back for one order, CONFIRMED via response
 * body each time (not just the request):
 *   "Accepted"  -> response nextStatus "Arrived"
 *   "Arrived"   -> response nextStatus "PickedUp"  (title "Preparing Order")
 *   "PickedUp"  -> response nextStatus "Delivered" (title "Move Fast")
 *
 * "Delivered" itself IS now CONFIRMED via a real device capture too - it needs
 * a "photoProof" URL in the body (400 "Photo proof required" without one),
 * obtained from a SEPARATE upload call first:
 *   POST {driverApiBaseUrl}/services/content/api/content/upload?contentCategory=OTHERS
 *   multipart field name "file" (CONFIRMED - other names 400 "part not present")
 *   200 response: { "title", "detail", "data": { "content": { "url": "https://dev-content.hoponbd.com/..." } } }
 * That "url" is then sent as-is in the Delivered acknowledge call:
 *   { "orderId": "<uuid>", "photoProof": "<url>", "status": "Delivered" }
 *
 * Cancellation IS ALSO CONFIRMED (2026-09-26, order 020-3A0), via the same
 * endpoint with status "Cancelled" (double L - CONFIRMED the single-L
 * "Canceled" and other guesses like "Rejected"/"Declined" 400 with a Jackson
 * enum-deserialization error, i.e. aren't valid enum values at all):
 *   - called by a driver NOT currently assigned to the order (e.g. before
 *     that driver has ever called Accepted) -> 400 "Invalid user".
 *   - called after Accepted, or after Arrived, by the assigned driver -> 200,
 *     same "status"/"title"/"message" all-null quirk as Delivered, but here
 *     the reliable success signal is "driverInfo" (and "driverName") flipping
 *     to null - the order reverts to unassigned or "Preparing", ready for
 *     another driver to accept it.
 *   - never actually tried once a driver has gone as far as PickedUp -
 *     unconfirmed past that point.
 */
public class DriverAPI {

    private static final String ACKNOWLEDGE_PATH = "/services/food/api/food-orders/driver/acknowledge";
    private static final String UPLOAD_PATH = "/services/content/api/content/upload";

    private final Playwright playwright;
    private final String baseUrl;
    private final String bearerToken;
    private final String deviceId;
    private final ObjectMapper mapper = new ObjectMapper();
    private APIRequestContext request;

    public DriverAPI(Playwright playwright, String baseUrl, String bearerToken, String deviceId) {
        this.playwright = playwright;
        this.baseUrl = baseUrl;
        this.bearerToken = bearerToken;
        this.deviceId = deviceId;
    }

    private APIRequestContext request() {
        if (request == null) {
            request = playwright.request().newContext(
                    new APIRequest.NewContextOptions().setBaseURL(baseUrl));
        }
        return request;
    }

    /** Generic status acknowledgement — see class doc for confirmed statuses. */
    public JsonNode acknowledge(String orderId, String status) {
        return acknowledge(orderId, status, null);
    }

    /**
     * Generic status acknowledgement with an optional delivery PIN - CONFIRMED
     * REQUIRED for "PickedUp" (400 "Invalid Pin" without it, even once the
     * order is ready). The restaurant-admin's order detail panel shows this
     * same PIN under "Delivery Logistics" - see LiveOrdersPage.pickupPin().
     */
    public JsonNode acknowledge(String orderId, String status, String pin) {
        String body = pin == null
                ? String.format("{\"orderId\":\"%s\",\"status\":\"%s\"}", orderId, status)
                : String.format("{\"orderId\":\"%s\",\"status\":\"%s\",\"pin\":\"%s\"}", orderId, status, pin);
        return postAcknowledge(orderId, status, body);
    }

    private JsonNode postAcknowledge(String orderId, String status, String body) {
        RequestOptions options = RequestOptions.create().setData(body);
        for (Map.Entry<String, String> header : headerManager.driverHeaders(bearerToken, deviceId).entrySet()) {
            options.setHeader(header.getKey(), header.getValue());
        }

        APIResponse response = request().post(ACKNOWLEDGE_PATH, options);

        try {
            JsonNode json = mapper.readTree(response.text());
            if (!response.ok()) {
                throw new RuntimeException("acknowledge(" + orderId + ", " + status + ") failed: "
                        + response.status() + " " + response.statusText() + " - " + json);
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Non-JSON response from acknowledge(): " + response.text(), e);
        }
    }

    /**
     * Uploads a photo (any bytes - a real proof photo isn't required by the
     * backend, just a file) as delivery proof and returns its hosted URL, for
     * use as the "photoProof" argument to markDelivered() below.
     */
    public String uploadPhotoProof(byte[] imageBytes, String filename, String mimeType) {
        RequestOptions options = RequestOptions.create()
                .setQueryParam("contentCategory", "OTHERS")
                .setMultipart(FormData.create().set("file", new FilePayload(filename, mimeType, imageBytes)));
        for (Map.Entry<String, String> header : headerManager.driverHeaders(bearerToken, deviceId).entrySet()) {
            if (!header.getKey().equalsIgnoreCase("Content-Type")) {
                options.setHeader(header.getKey(), header.getValue());
            }
        }

        APIResponse response = request().post(UPLOAD_PATH, options);
        try {
            JsonNode json = mapper.readTree(response.text());
            if (!response.ok()) {
                throw new RuntimeException("uploadPhotoProof() failed: " + response.status()
                        + " " + response.statusText() + " - " + json);
            }
            String url = json.at("/data/content/url").asText();
            if (url.isEmpty()) {
                throw new IllegalStateException("Upload succeeded but no /data/content/url in response: " + json);
            }
            return url;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Non-JSON response from uploadPhotoProof(): " + response.text(), e);
        }
    }

    /** Driver accepts an order that's waiting for pickup assignment. */
    public JsonNode acceptOrder(String orderId) {
        return acknowledge(orderId, "Accepted");
    }

    /** Driver has arrived at the restaurant to pick up an already-accepted order. */
    public JsonNode markArrived(String orderId) {
        return acknowledge(orderId, "Arrived");
    }

    /** Driver has picked up the food and is heading to the customer. Requires the order's delivery PIN. */
    public JsonNode markPickedUp(String orderId, String pin) {
        return acknowledge(orderId, "PickedUp", pin);
    }

    /** Driver has delivered the order - requires a photoProof URL from uploadPhotoProof(). */
    public JsonNode markDelivered(String orderId, String photoProofUrl) {
        String body = String.format("{\"orderId\":\"%s\",\"photoProof\":\"%s\",\"status\":\"Delivered\"}",
                orderId, photoProofUrl);
        return postAcknowledge(orderId, "Delivered", body);
    }

    /**
     * Convenience for test automation: uploads a random one of a few small
     * hardcoded placeholder images (see PlaceholderProofPhotos) as proof, then
     * marks the order Delivered - the backend only requires SOME uploaded
     * image, not a genuine delivery photo, so a real photo isn't needed here.
     */
    public JsonNode markDeliveredWithRandomProof(String orderId) {
        PlaceholderProofPhotos.Photo photo = PlaceholderProofPhotos.random();
        String photoProofUrl = uploadPhotoProof(photo.bytes(), photo.filename(), photo.mimeType());
        return markDelivered(orderId, photoProofUrl);
    }

    /** Driver cancels an order they are currently assigned to (Accepted or Arrived) - see class doc. */
    public JsonNode cancelOrder(String orderId) {
        return acknowledge(orderId, "Cancelled");
    }

    public void close() {
        if (request != null) {
            request.dispose();
        }
    }

    /** True once cancelOrder() has actually cleared the driver assignment - see class doc. */
    public static boolean isCancelled(JsonNode acknowledgeResponse) {
        return acknowledgeResponse.at("/data/content/driverInfo").isNull();
    }

    /**
     * CONFIRMED backend quirk (2026-09-19, order 01B-455): unlike every other
     * acknowledge() call, a successful "Delivered" response has its top-level
     * "status"/"title"/"message"/"nextStatus" fields all null - the only
     * signal it actually worked is a "Completed" entry in "statusProgresses"
     * whose progress is no longer "PENDING". Use this instead of reading
     * "/data/content/status" (which markPickedUp() etc. can rely on fine).
     */
    public static boolean isDelivered(JsonNode acknowledgeResponse) {
        for (JsonNode step : acknowledgeResponse.at("/data/content/statusProgresses")) {
            if ("Completed".equals(step.path("status").asText())
                    && !"PENDING".equals(step.path("progress").asText())) {
                return true;
            }
        }
        return false;
    }
}
