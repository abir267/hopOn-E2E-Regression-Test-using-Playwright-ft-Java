package APITests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import foodAPIs.headerManager;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import utils.ConfigReader;

import java.util.Map;

/**
 * Sends malformed/null/wrong-type/empty payloads at the two CONFIRMED real food
 * driver endpoints (see {@link foodAPIs.driverModule.DriverAPI}'s class doc for how
 * they were confirmed) and checks the server degrades gracefully - a clean 4xx with a
 * JSON body - rather than crashing (5xx, a stack trace leaked into the response body,
 * a connection reset, or a non-JSON response). Same style as the team's existing
 * null-field/wrong-type/empty-array sweep for the ride API (see "api null value
 * test.pdf" / "api field value test.pdf" / "api error test.pdf").
 *
 * This deliberately does NOT go through {@link foodAPIs.driverModule.DriverAPI}: that
 * class's postAcknowledge() throws RuntimeException on any non-2xx response, which is
 * right for normal test code driving a real flow, but wrong here - a 400 is often the
 * CORRECT, graceful answer to a malformed payload, and the whole point of this class is
 * to capture and grade that response, not treat it as a failure.
 *
 * No real "order waiting for driver" is needed: these payloads test the server's input
 * handling, not a real business flow, so an invalid/placeholder orderId is fine (and
 * expected to 400/404 on its own) - the thing being checked is whether a malformed
 * FIELD (null, wrong type, missing, injected, oversized) ever turns that into a 5xx
 * crash instead of the normal clean validation error.
 */
public class FoodDriverApiCrashTest {

    private static final String ACKNOWLEDGE_PATH = "/services/food/api/food-orders/driver/acknowledge";
    private static final String UPLOAD_PATH = "/services/content/api/content/upload";
    private static final String PLACEHOLDER_ORDER_ID = "00000000-0000-0000-0000-000000000000";

    private Playwright playwright;
    private APIRequestContext request;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeClass(alwaysRun = true)
    public void setUp() {
        playwright = Playwright.create();
        request = playwright.request().newContext(
                new APIRequest.NewContextOptions().setBaseURL(ConfigReader.driverApiBaseUrl()));
    }

    // ---- acknowledge: null / missing fields ----

    @Test(description = "orderId null -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeNullOrderId() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":null,\"status\":\"Accepted\"}"));
    }

    @Test(description = "orderId field entirely missing -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeMissingOrderId() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"status\":\"Accepted\"}"));
    }

    @Test(description = "status null -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeNullStatus() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":\"" + PLACEHOLDER_ORDER_ID + "\",\"status\":null}"));
    }

    @Test(description = "status field entirely missing -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeMissingStatus() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":\"" + PLACEHOLDER_ORDER_ID + "\"}"));
    }

    @Test(description = "completely empty JSON object -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeEmptyBody() {
        assertGracefulFailure(postAcknowledgeRaw("{}"));
    }

    @Test(description = "PickedUp with pin null (pin confirmed required) -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeNullPin() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":\"" + PLACEHOLDER_ORDER_ID + "\",\"status\":\"PickedUp\",\"pin\":null}"));
    }

    // ---- acknowledge: wrong types ----

    @Test(description = "orderId as a number instead of a string -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeOrderIdWrongType() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":12345,\"status\":\"Accepted\"}"));
    }

    @Test(description = "status as an array instead of a string -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeStatusWrongType() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":\"" + PLACEHOLDER_ORDER_ID + "\",\"status\":[\"Accepted\"]}"));
    }

    @Test(description = "status as an object instead of a string -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeStatusWrongTypeObject() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":\"" + PLACEHOLDER_ORDER_ID + "\",\"status\":{\"value\":\"Accepted\"}}"));
    }

    @Test(description = "orderId as an empty array -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeOrderIdEmptyArray() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":[],\"status\":\"Accepted\"}"));
    }

    // ---- acknowledge: invalid/unexpected values ----

    @Test(description = "status with an invalid enum value (not a real status) -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeInvalidStatusEnum() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":\"" + PLACEHOLDER_ORDER_ID + "\",\"status\":\"NotARealStatus\"}"));
    }

    @Test(description = "orderId with SQL-injection-style content -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeSqlInjectionOrderId() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":\"' OR '1'='1\",\"status\":\"Accepted\"}"));
    }

    @Test(description = "orderId extremely long (10000 chars) -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeOversizedOrderId() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":\"" + "a".repeat(10000) + "\",\"status\":\"Accepted\"}"));
    }

    @Test(description = "unexpected extra field injected into the body -> expect clean 4xx/2xx, not a 5xx crash")
    public void acknowledgeUnexpectedExtraField() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":\"" + PLACEHOLDER_ORDER_ID + "\",\"status\":\"Accepted\","
                        + "\"unexpectedField\":{\"nested\":[1,2,3]}}"));
    }

    @Test(description = "malformed (syntactically invalid) JSON body -> expect clean 4xx, not a 5xx crash")
    public void acknowledgeMalformedJson() {
        assertGracefulFailure(postAcknowledgeRaw(
                "{\"orderId\":\"" + PLACEHOLDER_ORDER_ID + "\", \"status\": "));
    }

    // ---- acknowledge: auth edge cases ----

    @Test(description = "missing Authorization header entirely -> expect clean 401, not a 5xx crash")
    public void acknowledgeMissingAuth() {
        RequestOptions options = RequestOptions.create().setData(
                "{\"orderId\":\"" + PLACEHOLDER_ORDER_ID + "\",\"status\":\"Accepted\"}");
        for (Map.Entry<String, String> header : headerManager.driverHeaders(
                ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId()).entrySet()) {
            if (!header.getKey().equalsIgnoreCase("Authorization")) {
                options.setHeader(header.getKey(), header.getValue());
            }
        }
        APIResponse response = request.post(ACKNOWLEDGE_PATH, options);
        assertGracefulFailure(new Result(response.status(), response.text()));
    }

    @Test(description = "garbage (not a real JWT) Authorization token -> expect clean 401, not a 5xx crash")
    public void acknowledgeGarbageAuthToken() {
        RequestOptions options = RequestOptions.create().setData(
                "{\"orderId\":\"" + PLACEHOLDER_ORDER_ID + "\",\"status\":\"Accepted\"}");
        for (Map.Entry<String, String> header : headerManager.driverHeaders(
                "not-a-real-jwt-token", ConfigReader.driverDeviceId()).entrySet()) {
            options.setHeader(header.getKey(), header.getValue());
        }
        APIResponse response = request.post(ACKNOWLEDGE_PATH, options);
        assertGracefulFailure(new Result(response.status(), response.text()));
    }

    // ---- upload: null / missing / wrong field ----

    @Test(description = "upload with no file part at all -> expect clean 4xx, not a 5xx crash")
    public void uploadMissingFilePart() {
        RequestOptions options = RequestOptions.create()
                .setQueryParam("contentCategory", "OTHERS");
        assertGracefulFailure(postUploadRaw(options));
    }

    @Test(description = "upload with a wrong multipart field name (not \"file\") -> expect clean 4xx, not a 5xx crash")
    public void uploadWrongFieldName() {
        RequestOptions options = RequestOptions.create()
                .setQueryParam("contentCategory", "OTHERS")
                .setMultipart(FormData.create().set("photo",
                        new FilePayload("test.jpg", "image/jpeg", new byte[]{1, 2, 3})));
        assertGracefulFailure(postUploadRaw(options));
    }

    @Test(description = "upload with an empty (0-byte) file -> expect clean 4xx, not a 5xx crash")
    public void uploadEmptyFile() {
        RequestOptions options = RequestOptions.create()
                .setQueryParam("contentCategory", "OTHERS")
                .setMultipart(FormData.create().set("file",
                        new FilePayload("empty.jpg", "image/jpeg", new byte[0])));
        assertGracefulFailure(postUploadRaw(options));
    }

    @Test(description = "upload with contentCategory missing entirely -> expect clean 4xx, not a 5xx crash")
    public void uploadMissingContentCategory() {
        RequestOptions options = RequestOptions.create()
                .setMultipart(FormData.create().set("file",
                        new FilePayload("test.jpg", "image/jpeg", new byte[]{1, 2, 3})));
        assertGracefulFailure(postUploadRaw(options));
    }

    @Test(description = "upload with an invalid contentCategory value -> expect clean 4xx, not a 5xx crash")
    public void uploadInvalidContentCategory() {
        RequestOptions options = RequestOptions.create()
                .setQueryParam("contentCategory", "NotARealCategory")
                .setMultipart(FormData.create().set("file",
                        new FilePayload("test.jpg", "image/jpeg", new byte[]{1, 2, 3})));
        assertGracefulFailure(postUploadRaw(options));
    }

    // ---- helpers ----

    private Result postAcknowledgeRaw(String rawJsonBody) {
        RequestOptions options = RequestOptions.create().setData(rawJsonBody);
        for (Map.Entry<String, String> header : headerManager.driverHeaders(
                ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId()).entrySet()) {
            options.setHeader(header.getKey(), header.getValue());
        }
        APIResponse response = request.post(ACKNOWLEDGE_PATH, options);
        return new Result(response.status(), response.text());
    }

    private Result postUploadRaw(RequestOptions options) {
        for (Map.Entry<String, String> header : headerManager.driverHeaders(
                ConfigReader.driverAuthToken(), ConfigReader.driverDeviceId()).entrySet()) {
            if (!header.getKey().equalsIgnoreCase("Content-Type")) {
                options.setHeader(header.getKey(), header.getValue());
            }
        }
        APIResponse response = request.post(UPLOAD_PATH, options);
        return new Result(response.status(), response.text());
    }

    /**
     * Pass = server degraded gracefully: not a 5xx, and the body is well-formed JSON
     * (not a leaked stack trace / HTML error page / truncated response). A 2xx is also
     * accepted for scenarios where the field being tested turns out not to matter to
     * this particular call (e.g. an ignored extra field) - the thing under test is
     * "did it crash", not "was this specific input rejected".
     */
    private void assertGracefulFailure(Result result) {
        System.out.println("status=" + result.status + " body=" + result.body);
        Assert.assertTrue(result.status < 500,
                "Server returned a " + result.status + " (5xx = likely crash/unhandled "
                        + "exception) for this payload. Body: " + result.body);
        try {
            mapper.readTree(result.body);
        } catch (JsonProcessingException e) {
            Assert.fail("Server response wasn't valid JSON (status " + result.status
                    + ") - looks like a crash/stack-trace leak rather than a clean "
                    + "error response. Body: " + result.body);
        }
    }

    private static final class Result {
        final int status;
        final String body;

        Result(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (request != null) {
            request.dispose();
        }
        if (playwright != null) {
            playwright.close();
        }
    }
}
