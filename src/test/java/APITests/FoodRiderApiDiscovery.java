package APITests;

import init.BaseRunner;
import org.testng.annotations.Test;
import pages.WebPages.customerPages.CartPage;
import pages.WebPages.customerPages.CheckoutPage;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import pages.WebPages.customerPages.RestaurantMenuPage;
import utils.ConfigReader;
import utils.TestDataGenerator;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;

/**
 * ONE-OFF discovery run, not a regression test: drives the real customer web order
 * flow (same steps as SearchAndOrderTest - login, set location, open restaurant, add
 * to cart, checkout, place a REAL order, track it) while logging every XHR/fetch
 * request+response to target/food-rider-api-capture.log. Purpose: confirm the real
 * customer-facing food API contract (endpoint paths, payload/response shapes), the
 * same way foodAPIs.driverModule.DriverAPI's endpoints were confirmed from a device
 * capture - foodAPIs.orderModule.OrderAPI and .authModule.AuthAPI are currently just
 * guessed stubs because no such capture existed for the customer side yet.
 *
 * Places one real order, same side effect SearchAndOrderTest already has routinely.
 */
public class FoodRiderApiDiscovery extends BaseRunner {

    @Test(description = "Capture real customer food API traffic during a full order flow")
    public void captureOrderFlowApiTraffic() throws IOException {
        java.nio.file.Files.createDirectories(Paths.get("target"));
        PrintWriter log = new PrintWriter(new FileWriter("target/food-rider-api-capture.log"));

        page.onRequest(req -> {
            String type = req.resourceType();
            if (!type.equals("xhr") && !type.equals("fetch")) {
                return;
            }
            log.println(">>> " + req.method() + " " + req.url());
            String postData = req.postData();
            if (postData != null && !postData.isBlank()) {
                log.println("    body: " + postData);
            }
            req.allHeaders().forEach((k, v) -> {
                if (k.equalsIgnoreCase("authorization") || k.equalsIgnoreCase("cookie")) {
                    log.println("    header: " + k + ": <redacted>");
                }
            });
            log.flush();
        });

        page.onResponse(res -> {
            String type = res.request().resourceType();
            if (!type.equals("xhr") && !type.equals("fetch")) {
                return;
            }
            log.println("<<< " + res.status() + " " + res.url());
            try {
                String body = res.text();
                if (body.length() > 2000) {
                    body = body.substring(0, 2000) + "...(truncated)";
                }
                log.println("    resp: " + body);
            } catch (Exception e) {
                log.println("    resp: <unreadable: " + e.getMessage() + ">");
            }
            log.flush();
        });

        HomePage homePage = new HomePage(page).open(ConfigReader.baseUrl());
        homePage.openLogin();
        String phone = TestDataGenerator.randomRiderPhone();
        new LoginPage(page).loginWithPhoneAndOtp(phone, ConfigReader.testUserOtp());
        log.println("### logged in as " + phone);

        homePage.setDeliveryLocationToCurrentLocation();
        log.println("### delivery location set");

        String restaurantId = "b2545dca-3795-4aa5-8075-b5dc58de06f7";
        homePage.openRestaurantById(ConfigReader.baseUrl(), restaurantId);
        RestaurantMenuPage menuPage = new RestaurantMenuPage(page);
        log.println("### opened restaurant: " + menuPage.restaurantTitle());

        menuPage.addItemToCart("Lahmacun, Middle East_86");
        log.println("### added item to cart");

        CartPage cartPage = new CartPage(page);
        CheckoutPage checkoutPage = cartPage.proceedToCheckout();
        log.println("### reached checkout");

        checkoutPage.placeOrder();
        String orderId = checkoutPage.orderId();
        log.println("### order placed: #" + orderId);

        checkoutPage.trackOrder();
        log.println("### tracking page: " + page.url());

        log.println("### DONE");
        log.close();
        System.out.println("Capture written to target/food-rider-api-capture.log");
    }
}
