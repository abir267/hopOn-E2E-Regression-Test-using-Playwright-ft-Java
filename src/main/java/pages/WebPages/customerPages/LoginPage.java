package pages.WebPages.customerPages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Phone + OTP login flow — CONFIRMED against the live site (2026-09-15 probe of
 * https://dev-food.hoponbd.com/login, logged in for real via a riderlist.csv
 * phone number, e.g. order #019-A29 placed end-to-end):
 *   - phone step: single "#phone" input (11-digit local number, no +880 prefix),
 *     submit button is a <button> with visible text "Log In" (no id/data-testid).
 *   - OTP step: FOUR separate single-digit inputs "#otp-0".."#otp-3" (not one
 *     combined field), submit button text "Verify OTP".
 *   - every phone number in DataSource/riderlist.csv logs in with the fixed dev
 *     OTP "1122" (see TestDataGenerator.randomRiderPhone()); 01700000000 is NOT
 *     one of these and is separately 24h-rate-limited from probing - don't use it.
 *   - IMPORTANT: filling the 4th OTP digit auto-submits client-side (an Angular
 *     (input) handler fires the verify call itself) and navigates to "/home" -
 *     clicking "Verify OTP" is normally a no-op by the time it happens. Calling
 *     it anyway isn't harmless, though: Locator.click() retries against a button
 *     that has already been removed from the DOM by that navigation and can hang
 *     for the full default timeout waiting for it to reappear, which it never
 *     does. submitOtp() below uses a short timeout and swallows that specific
 *     "gone before we got there" outcome so a successful auto-submit doesn't look
 *     like a failure.
 *   - Invalid/expired OTP shows inline text "Invalid or expired OTP" directly
 *     under the OTP boxes, plus a toast with the same text.
 *   - Browsing (search etc.) redirects unauthenticated users to
 *     /login?reason=auth-required — this app requires login before any
 *     restaurant/menu/cart screen can be reached.
 */
public class LoginPage extends BasePage {

    private static final String PHONE_INPUT = "#phone";
    private static final String OTP_BOX_ID_PREFIX = "#otp-";
    private static final int OTP_LENGTH = 4;

    private static final String LOGIN_ERROR_MESSAGE = "p.text-red-500";

    public LoginPage(Page page) {
        super(page);
    }

    public LoginPage enterPhoneNumber(String phoneNumber) {
        type(PHONE_INPUT, phoneNumber);
        return this;
    }

    public LoginPage submitPhoneNumber() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log In")).click();
        waitVisible(OTP_BOX_ID_PREFIX + "0");
        return this;
    }

    public LoginPage enterOtp(String otp) {
        if (otp.length() != OTP_LENGTH) {
            throw new IllegalArgumentException("Expected a " + OTP_LENGTH + "-digit OTP, got: " + otp);
        }
        for (int i = 0; i < OTP_LENGTH; i++) {
            page.locator(OTP_BOX_ID_PREFIX + i).fill(String.valueOf(otp.charAt(i)));
        }
        return this;
    }

    public void submitOtp() {
        clickAllowingSelfRemoval(
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Verify OTP")),
                () -> !isVisible(OTP_BOX_ID_PREFIX + "0"));
    }

    /** Convenience method chaining the full flow for the common "happy path" case. */
    public void loginWithPhoneAndOtp(String phoneNumber, String otp) {
        enterPhoneNumber(phoneNumber);
        submitPhoneNumber();
        enterOtp(otp);
        submitOtp();
    }

    public void resendOtp() {
        page.getByText("Resend").click();
    }

    public boolean isErrorVisible() {
        return isVisible(LOGIN_ERROR_MESSAGE);
    }

    public String errorMessage() {
        return textOf(LOGIN_ERROR_MESSAGE).trim();
    }
}
