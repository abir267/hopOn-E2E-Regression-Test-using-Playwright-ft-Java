package foodAPIs.authModule;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;

/**
 * STUB — no confirmed API contract (Swagger/Postman) was available when this was
 * written. Endpoint paths and payload/response shapes below are guesses based on
 * common phone+OTP flows and MUST be verified against the real backend (see
 * HANDOFF.md §3, Step 3) before removing the UnsupportedOperationException calls.
 */
public class AuthAPI {

    private final Playwright playwright;
    private APIRequestContext request;

    public AuthAPI(Playwright playwright) {
        this.playwright = playwright;
    }

    /**
     * POST {api.base.url}/auth/otp/request
     * body: { "phone": "017XXXXXXXX" }
     * Expected: 200 with { "requestId": "..." } or similar — CONFIRM real shape.
     */
    public String requestOtp(String phoneNumber) {
        throw new UnsupportedOperationException(
                "requestOtp() not implemented — confirm real endpoint/payload with backend team. " +
                "See HANDOFF.md §3 Step 3.");
    }

    /**
     * POST {api.base.url}/auth/otp/verify
     * body: { "phone": "017XXXXXXXX", "otp": "1122" }
     * Expected: 200 with { "token": "..." } — CONFIRM real shape.
     */
    public String verifyOtpAndGetToken(String phoneNumber, String otp) {
        throw new UnsupportedOperationException(
                "verifyOtpAndGetToken() not implemented — confirm real endpoint/payload with backend team. " +
                "See HANDOFF.md §3 Step 3.");
    }

    public void close() {
        if (request != null) {
            request.dispose();
        }
    }
}
