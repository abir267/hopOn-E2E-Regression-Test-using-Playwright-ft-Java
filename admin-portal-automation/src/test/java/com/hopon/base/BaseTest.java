package com.hopon.base;

import com.google.gson.JsonObject;
import com.hopon.config.ConfigReader;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Geolocation;
import org.testng.ITestResult;                                      // NEW
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import java.util.List;

public class BaseTest {

    protected Playwright playwright;
    protected Browser browser;
    protected BrowserContext context;
    protected Page page;

    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        playwright = Playwright.create();

        String browserName = ConfigReader.getOrDefault("browser", "chromium").toLowerCase();
        BrowserType type = switch (browserName) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> playwright.chromium();
        };
        boolean headless = Boolean.parseBoolean(ConfigReader.getOrDefault("headless", "false"));

        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(headless);

        double lat = Double.parseDouble(ConfigReader.getOrDefault("geo.latitude", "23.7806"));
        double lng = Double.parseDouble(ConfigReader.getOrDefault("geo.longitude", "90.4166"));

        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                .setPermissions(List.of("geolocation"))
                .setGeolocation(new Geolocation(lat, lng));

        // Maximize the window when running with a visible browser
        if (headless) {
            contextOptions.setViewportSize(1920, 1080);          // headless has no window to maximize
        } else {
            if (browserName.equals("chromium")) {
                launchOptions.setArgs(List.of("--start-maximized"));
            }
            contextOptions.setViewportSize(null);                // page fills the whole window
        }

        browser = type.launch(launchOptions);
        context = browser.newContext(contextOptions);
        context.setDefaultTimeout(30000);
        page = context.newPage();

        if (!headless && browserName.equals("chromium")) {
            maximizeChromiumWindow();
        }
    }

    // Asks Chromium itself to maximize the window, in case --start-maximized is ignored
    private void maximizeChromiumWindow() {
        try {
            CDPSession cdp = context.newCDPSession(page);
            JsonObject window = cdp.send("Browser.getWindowForTarget");

            JsonObject bounds = new JsonObject();
            bounds.addProperty("windowState", "maximized");

            JsonObject params = new JsonObject();
            params.addProperty("windowId", window.get("windowId").getAsInt());
            params.add("bounds", bounds);

            cdp.send("Browser.setWindowBounds", params);
        } catch (Exception e) {
            System.out.println("Could not maximize the window: " + e.getMessage());
        }
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown(ITestResult result) {                    // UPDATED: now knows if the test passed
        // NEW: after a passed test, keep the browser open (3 seconds by default) so you can see the final screen
        if (result.isSuccess() && page != null) {
            int delay = Integer.parseInt(ConfigReader.getOrDefault("close.delay.ms", "3000"));
            if (delay > 0) {
                page.waitForTimeout(delay);
            }
        }

        if (context != null) context.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }
}