package APITests;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Playwright;
import foodAPIs.driverModule.DriverAPI;
import utils.ConfigReader;

/**
 * Standalone runnable script (not a TestNG test) that calls the real driver
 * order-status API directly — no browser, no UI. Confirmed endpoint (see
 * DriverAPI class doc): POST .../food-orders/driver/acknowledge.
 *
 * Usage:
 *   java -cp target/classes:target/test-classes:<deps> APITests.DriverCalling <orderId> [status]
 *
 * <orderId> must be a real order currently waiting for driver acceptance
 * (status defaults to "Accepted" if omitted) - calling this against an order
 * that has already moved past that state will get a real error response back
 * from the server (that's expected, not a bug in this script).
 */
public class DriverCalling {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: DriverCalling <orderId> [status]");
            System.exit(1);
        }
        String orderId = args[0];
        String status = args.length > 1 ? args[1] : "Accepted";

        try (Playwright playwright = Playwright.create()) {
            DriverAPI driverAPI = new DriverAPI(
                    playwright,
                    ConfigReader.driverApiBaseUrl(),
                    ConfigReader.driverAuthToken(),
                    ConfigReader.driverDeviceId());

            System.out.println("Calling acknowledge(orderId=" + orderId + ", status=" + status + ") ...");
            JsonNode response = driverAPI.acknowledge(orderId, status);
            System.out.println("Response:");
            System.out.println(response.toPrettyString());

            driverAPI.close();
        }
    }
}
