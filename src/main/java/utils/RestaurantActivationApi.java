package utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitUntilState;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;


public class RestaurantActivationApi {

    private static final String ACTIVATION_URL =
            "https://dev-api.hoponbd.com/services/admin/api/restaurant-activation";
    private static final List<String> HEADERS_TO_REUSE = List.of(
            "authorization", "device-id", "platform", "source-app", "time-zone",
            "accept-language", "origin", "referer");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestaurantActivationApi() {
    }

    /** Logs into the admin portal, PATCHes activated=false, prints the reply, returns it. */
    public static JsonNode deactivate(Playwright playwright, Browser browser, String restaurantId, String restaurantName) {
        return setActivated(playwright, browser, restaurantId, restaurantName, false);
    }

    public static JsonNode setActivated(Playwright playwright, Browser browser, String restaurantId,
                                        String restaurantName, boolean activated) {
        String action = activated ? "ACTIVATE" : "DEACTIVATE";
        System.out.println("[ADMIN-API] " + action + " restaurant " + restaurantName + " (id=" + restaurantId + ")");

        // admin.portal.* with the admin-portal-automation fallback (see ConfigReader.adminPortalValue)
        String url = ConfigReader.adminPortalUrl();
        String email = ConfigReader.adminPortalEmail();
        String password = ConfigReader.adminPortalPassword();

        BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 900));
        try {
            Page page = context.newPage();
            AtomicReference<Map<String, String>> captured = new AtomicReference<>();
            page.onRequest(request -> {
                Map<String, String> all = request.headers();
                if (request.url().contains("/services/") && all.containsKey("authorization")) {
                    Map<String, String> picked = new HashMap<>();
                    for (String name : HEADERS_TO_REUSE) {
                        if (all.containsKey(name)) {
                            picked.put(name, all.get(name));
                        }
                    }
                    captured.set(picked);
                }
            });

            // DOMCONTENTLOADED + 60s: the dev portals sometimes never fire "load" within 30s
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
            page.fill("input[formcontrolname='email']", email);
            page.fill("#password", password);
            page.click("button[type='submit']");
            page.waitForURL(u -> !u.contains("/public/login"));

            // The portal fires its own /services/ calls right after login - wait for one to grab the token.
            for (int i = 0; i < 30 && captured.get() == null; i++) {
                page.waitForTimeout(500);
            }
            if (captured.get() == null) {
                throw new IllegalStateException("Admin portal logged in but no authorized /services/ call was seen - no token to reuse");
            }

            APIRequestContext api = playwright.request().newContext(
                    new APIRequest.NewContextOptions().setExtraHTTPHeaders(captured.get()));
            try {
                APIResponse response = api.patch(ACTIVATION_URL, RequestOptions.create()
                        .setHeader("content-type", "application/json")
                        .setData(Map.of("restaurantId", restaurantId, "activated", activated)));
                String text = response.text();
                System.out.println("[ADMIN-API] PATCH " + ACTIVATION_URL + " -> HTTP " + response.status() + " " + text);
                if (!response.ok()) {
                    throw new IllegalStateException("restaurant-activation returned " + response.status() + ": " + text);
                }
                JsonNode json = MAPPER.readTree(text);
                String title = json.path("title").asText("");
                if (!title.isEmpty() && !"Success".equalsIgnoreCase(title)) {
                    throw new IllegalStateException("restaurant-activation replied: " + text);
                }
                System.out.println("[ADMIN-API] Restaurant " + restaurantName + " is now "
                        + (activated ? "ACTIVATED" : "DEACTIVATED (OFF)"));
                return json;
            } catch (IOException e) {
                throw new RuntimeException("Could not parse restaurant-activation reply", e);
            } finally {
                api.dispose();
            }
        } finally {
            context.close();
        }
    }

}
