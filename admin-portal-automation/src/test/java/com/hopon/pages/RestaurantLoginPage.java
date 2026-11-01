package com.hopon.pages;

import com.microsoft.playwright.Page;

public class RestaurantLoginPage extends BasePage {

    private static final String PROFILE_BUTTON = "button:has(img[alt='User'])";
    private static final String SIGN_OUT_BUTTON = "button:visible:has-text('Sign Out')";

    // The login screen after sign out. Same form as the admin login; change these if it differs
    private static final String EMAIL_INPUT = "input[formcontrolname='email']";
    private static final String PASSWORD_INPUT = "#password";
    private static final String SIGN_IN_BUTTON = "button[type='submit']";

    public RestaurantLoginPage(Page page) {
        super(page);
    }

    // Profile icon, then Sign Out. Done once the profile icon is gone, which means the login screen is showing
    public void signOut() {
        page.click(PROFILE_BUTTON);
        page.click(SIGN_OUT_BUTTON);
        waitUntilHidden(PROFILE_BUTTON);
    }

    public void loginAs(String email, String password) {
        page.fill(EMAIL_INPUT, email);
        page.fill(PASSWORD_INPUT, password);
        page.click(SIGN_IN_BUTTON);
    }

    // Logged in once the profile icon is back
    public void waitForPortal() {
        page.locator(PROFILE_BUTTON).waitFor();
    }
}