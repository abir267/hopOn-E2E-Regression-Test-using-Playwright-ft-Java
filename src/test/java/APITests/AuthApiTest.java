package APITests;

import com.microsoft.playwright.Playwright;
import foodAPIs.authModule.AuthAPI;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import utils.ConfigReader;

/**
 * Disabled until AuthAPI is wired up against a confirmed API contract
 * (see HANDOFF.md §5 Step 3). Flip enabled=true once AuthAPI.requestOtp()
 * and verifyOtpAndGetToken() no longer throw UnsupportedOperationException.
 */
public class AuthApiTest {

    private Playwright playwright;
    private AuthAPI authAPI;

    @BeforeClass(alwaysRun = true)
    public void setUp() {
        playwright = Playwright.create();
        authAPI = new AuthAPI(playwright);
    }

    @Test(enabled = false, description = "Request OTP for a valid test phone number succeeds")
    public void requestOtpSucceeds() {
        String requestId = authAPI.requestOtp(ConfigReader.testUserPhone());
        Assert.assertNotNull(requestId, "Expected a non-null requestId from requestOtp()");
    }

    @Test(enabled = false, description = "Verifying the fixed dev OTP returns a usable auth token")
    public void verifyOtpReturnsToken() {
        String token = authAPI.verifyOtpAndGetToken(ConfigReader.testUserPhone(), ConfigReader.testUserOtp());
        Assert.assertNotNull(token, "Expected a non-null auth token from verifyOtpAndGetToken()");
        Assert.assertFalse(token.isBlank(), "Auth token should not be blank");
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (authAPI != null) {
            authAPI.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }
}
