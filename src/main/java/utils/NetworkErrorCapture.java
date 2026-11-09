package utils;

import com.microsoft.playwright.Page;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Watches a page's network traffic for backend 4xx/5xx responses and prints each one as a
 * flagged bug, e.g. {@code [BUG][ADMIN_PORTAL] 500 POST https://dev-admin.hoponbd.com/services/...}.
 *
 * These are intermittent/environmental - a login or action that normally works can
 * occasionally 400/500 on the backend - so a test retry can mask them even though they're
 * real bugs. Printing the response here means they show up in the run log instead of just
 * surfacing as a confusing UI timeout (the symptom actually hit when this was added: Suspend
 * Account's modal silently failing to submit after an intermittent login-time 500).
 *
 * Status/method/URL only, deliberately no response body. CONFIRMED live (2026-11-09): calling
 * response.text() here - inside Playwright's event-dispatch thread - re-enters the same
 * Connection to fetch the body, and that reentrant round-trip was stalling the connection and
 * causing unrelated, already-in-flight page.click() calls on the main thread to time out at
 * 30s ("waiting for locator(...)" with no other explanation). That was misread as backend
 * flakiness for several runs before the stack trace pointed at this listener. Not worth a body
 * read badly enough to risk stalling the page's own actions - skip it.
 *
 * Only reports API calls ("/services/" or "/api/" in the URL, same filter RestaurantApi uses
 * to recognise real backend calls), not static asset 404s.
 */
public final class NetworkErrorCapture {

    private static final Map<Page, String> ATTACHED = Collections.synchronizedMap(new WeakHashMap<>());

    private NetworkErrorCapture() {
    }

    public static void attach(Page page, String role) {
        if (ATTACHED.putIfAbsent(page, role) != null) {
            return;
        }
        page.onResponse(response -> {
            if (response.status() < 400 || !isApiCall(response.url())) {
                return;
            }
            try {
                System.out.println("[BUG][" + role + "] " + response.status() + " " + response.request().method()
                        + " " + response.url());
            } catch (Throwable ignored) {
                // Best-effort logging only - never let this listener break the page's own actions.
            }
        });
    }

    private static boolean isApiCall(String url) {
        return url.contains("/services/") || url.contains("/api/");
    }
}
