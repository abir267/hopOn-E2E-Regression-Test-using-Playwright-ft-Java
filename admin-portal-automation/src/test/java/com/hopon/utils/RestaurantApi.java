package com.hopon.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hopon.config.ConfigReader;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.RequestOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RestaurantApi {

    private static final List<String> HEADERS_TO_REUSE = List.of(
            "authorization", "device-id", "platform", "source-app", "time-zone",
            "accept-language", "origin", "referer");

    // After Continue the portal opens .../restaurant/edit/<id>?tab=operational
    private static final Pattern EDIT_URL = Pattern.compile(
            "/restaurant/edit/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    private final Page page;
    private final Playwright playwright;
    private volatile Map<String, String> authHeaders;

    public RestaurantApi(Page page, Playwright playwright) {
        this.page = page;
        this.playwright = playwright;

        // Copies the logged-in admin's token from the portal's own API calls, so no token is stored in the code
        page.onRequest(request -> {
            Map<String, String> all = request.headers();
            if (request.url().contains("/services/") && all.containsKey("authorization")) {
                Map<String, String> picked = new HashMap<>();
                for (String name : HEADERS_TO_REUSE) {
                    if (all.containsKey(name)) {
                        picked.put(name, all.get(name));
                    }
                }
                authHeaders = picked;
            }
        });
    }

    /** Waits for the edit screen to open after Continue, then reads the restaurant ID from the address. Null if not found. */
    public String waitForRestaurantId() {
        try {
            page.waitForURL(url -> EDIT_URL.matcher(url).find(), new Page.WaitForURLOptions().setTimeout(15000));
        } catch (PlaywrightException e) {
            return null;
        }
        Matcher matcher = EDIT_URL.matcher(page.url());
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Sets the restaurant to VERIFIED and returns the saved restaurant (data.content of the reply). */
    public JsonObject verifyRestaurant(String restaurantId) {
        Map<String, Object> body = Map.of("id", restaurantId, "verificationStatus", "VERIFIED");
        JsonObject reply = patch(ConfigReader.get("api.verify.url"), body);
        return reply.getAsJsonObject("data").getAsJsonObject("content");
    }

    /** Activates the restaurant. */
    public void activateRestaurant(String restaurantId) {
        Map<String, Object> body = Map.of("restaurantId", restaurantId, "activated", true);
        patch(ConfigReader.get("api.activation.url"), body);
    }

    private JsonObject patch(String url, Map<String, Object> body) {
        if (authHeaders == null) {
            throw new IllegalStateException("No API token captured yet: the portal has not made an API call since login");
        }
        APIRequestContext api = playwright.request().newContext(
                new APIRequest.NewContextOptions().setExtraHTTPHeaders(authHeaders));
        try {
            APIResponse response = api.patch(url, RequestOptions.create()
                    .setHeader("content-type", "application/json")
                    .setData(body));
            String text = response.text();
            if (!response.ok()) {
                throw new IllegalStateException("API " + url + " returned " + response.status() + ": " + text);
            }
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            JsonElement title = json.get("title");
            if (title != null && !"Success".equalsIgnoreCase(title.getAsString())) {
                throw new IllegalStateException("API " + url + " replied: " + text);
            }
            return json;
        } finally {
            api.dispose();
        }
    }
}