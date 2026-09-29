package init;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import utils.ConfigReader;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Owns the Playwright/Browser/BrowserContext/Page lifecycle for restaurant-admin
 * ("web-restaurant") test classes. Kept separate from {@link BaseRunner} rather
 * than reused, because the admin portal lives on a different host
 * (dev-foodadmin.hoponbd.com, which itself redirects to dev-admin.hoponbd.com
 * for login) and needs no delivery-geolocation grant that BaseRunner sets up
 * for the customer site.
 */
public class AdminBaseRunner {

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
                .setBaseURL(ConfigReader.restaurantAdminUrl())
                .setViewportSize(1440, 900));
        context.setDefaultTimeout(ConfigReader.defaultTimeout());
        page = context.newPage();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDownContext(ITestResult result) {
        if (!result.isSuccess()) {
            Path artifactsDir = Paths.get("target", "test-artifacts");
            String safeName = result.getTestClass().getRealClass().getSimpleName()
                    + "_" + result.getMethod().getMethodName();
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(artifactsDir.resolve(safeName + ".png"))
                    .setFullPage(true));
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
