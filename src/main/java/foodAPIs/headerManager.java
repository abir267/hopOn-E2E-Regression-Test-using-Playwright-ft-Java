package foodAPIs;

import java.util.HashMap;
import java.util.Map;

/** Builds the common headers required by the food backend API. */
public class headerManager {

    private headerManager() {
    }

    /** Headers for anonymous/unauthenticated calls (e.g. requestOtp, listRestaurants). */
    public static Map<String, String> commonHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("X-Platform", "web");
        headers.put("X-Device-Id", "playwright-automation");
        return headers;
    }

    /** Headers for authenticated calls, adds the bearer token on top of commonHeaders(). */
    public static Map<String, String> authHeaders(String bearerToken) {
        Map<String, String> headers = commonHeaders();
        headers.put("Authorization", "Bearer " + bearerToken);
        return headers;
    }

    /**
     * Headers for driver-app API calls — CONFIRMED against a real device capture
     * (2026-09-19, HTTP Toolkit proxy on the live driver app) rather than guessed.
     * The backend distinguishes the driver app from other clients via Source-App/
     * Platform/User-Agent, not just the JWT's "au" claim.
     */
    public static Map<String, String> driverHeaders(String bearerToken, String deviceId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("Source-App", "DRIVER");
        headers.put("Platform", "android");
        headers.put("Device-Id", deviceId);
        headers.put("Accept-Language", "en");
        headers.put("Authorization", "Bearer " + bearerToken);
        return headers;
    }
}
