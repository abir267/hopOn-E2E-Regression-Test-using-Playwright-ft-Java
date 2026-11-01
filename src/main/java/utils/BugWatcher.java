package utils;

import com.microsoft.playwright.Page;

/**
 * Attaches to a Page and prints "[BUG] ..." lines the moment something goes wrong on the
 * live site during a test — independent of whether the test's own assertions happen to
 * catch it. Covers what a script's assertions typically never check:
 *   - browser console errors (JS exceptions logged via console.error)
 *   - uncaught exceptions in the page's own JS
 *   - failed XHR/fetch calls (4xx/5xx) the app made while the test ran
 *
 * 401 responses are suppressed: HomePage's javadoc documents a startup burst of
 * reverse-geocode/food-carts/newAccessToken calls that legitimately 401 for a
 * logged-out visitor on every page load — reporting those would just be noise that
 * trains readers to ignore real failures.
 *
 * Call once per Page, right after creation (BaseRunner/AdminBaseRunner do this for
 * every test automatically — no per-test wiring needed).
 */
public final class BugWatcher {

    private BugWatcher() {
    }

    public static void attach(Page page) {
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                System.out.println("[BUG] console error: " + msg.text());
            }
        });

        page.onPageError(error -> System.out.println("[BUG] uncaught exception: " + error));

        page.onResponse(response -> {
            int status = response.status();
            if (status < 400 || status == 401) {
                return;
            }
            String resourceType = response.request().resourceType();
            if (!"xhr".equals(resourceType) && !"fetch".equals(resourceType)) {
                return;
            }
            System.out.println("[BUG] HTTP " + status + " " + response.request().method() + " " + response.url());
        });
    }
}
