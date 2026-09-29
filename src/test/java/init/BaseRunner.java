package init;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.Geolocation;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import utils.ConfigReader;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Owns the Playwright/Browser/BrowserContext/Page lifecycle for every test class.
 *
 * One Browser per test SUITE, a fresh BrowserContext per test METHOD — deliberate:
 * cart contents, login session, and saved addresses are client-side state that must
 * not leak between tests. Trace + screenshot are captured only when a test fails,
 * to keep target/ from filling up with artifacts from passing runs.
 *
 * Geolocation is granted and pinned to delivery.latitude/longitude (CONFIRMED against
 * the live site 2026-09-15): restaurant "available" status is geo-fenced server-side —
 * a saved address outside a restaurant's delivery radius makes every item on its menu
 * return available:false and blocks add-to-cart with an "unavailable" modal, even for
 * a restaurant that's genuinely open. Tests must use HomePage.setDeliveryLocationToCurrentLocation(),
 * which relies on this browser geolocation, rather than a saved/typed address.
 *
 * playwright/browser are static and guarded by launched: TestNG gives every test CLASS
 * its own BaseRunner instance, but @BeforeSuite/@AfterSuite still only run once per
 * suite (CONFIRMED - two test classes in one suite otherwise left the second class's
 * instance fields null, throwing NPE on its first @BeforeMethod). A single shared
 * Browser also means test classes must run sequentially, not with parallel="classes" -
 * Playwright Java's sync API is not safe to call from more than one thread.
 */
public class BaseRunner {

    private static Playwright playwright;
    private static Browser browser;
    private static volatile boolean launched = false;

    protected BrowserContext context;
    protected Page page;

    @BeforeSuite(alwaysRun = true)
    public static synchronized void launchBrowser() {
        if (launched) {
            return;
        }
        playwright = Playwright.create();
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(ConfigReader.headless())
                .setSlowMo(ConfigReader.slowMo());

        BrowserType browserType = switch (ConfigReader.browser().toLowerCase()) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> playwright.chromium();
        };

        browser = browserType.launch(launchOptions);
        launched = true;
    }

    @BeforeMethod(alwaysRun = true)
    public void newContextAndPage() {
        context = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(ConfigReader.baseUrl())
                .setViewportSize(1366, 768)
                .setGeolocation(new Geolocation(ConfigReader.deliveryLatitude(), ConfigReader.deliveryLongitude()))
                .setPermissions(java.util.List.of("geolocation")));
        context.setDefaultTimeout(ConfigReader.defaultTimeout());
        context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));

        page = context.newPage();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDownContext(ITestResult result) {
        boolean failed = !result.isSuccess();

        if (failed) {
            Path artifactsDir = Paths.get("target", "test-artifacts");
            String safeName = result.getTestClass().getRealClass().getSimpleName()
                    + "_" + result.getMethod().getMethodName();

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(artifactsDir.resolve(safeName + ".png"))
                    .setFullPage(true));

            context.tracing().stop(new Tracing.StopOptions()
                    .setPath(artifactsDir.resolve(safeName + "_trace.zip")));
        } else {
            context.tracing().stop();
        }

        context.close();
    }

    @AfterSuite(alwaysRun = true)
    public static synchronized void closeBrowser() {
        if (!launched) {
            return;
        }
        browser.close();
        playwright.close();
        launched = false;
    }
}
