package com.hopon.pages;

import com.hopon.config.ConfigReader;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

public class LoginPage extends BasePage {

    private static final String EMAIL_INPUT = "input[formcontrolname='email']";
    private static final String PASSWORD_INPUT = "#password";
    private static final String SIGN_IN_BUTTON = "button[type='submit']";

    public LoginPage(Page page) {
        super(page);
    }

    public void open() {
        page.navigate(ConfigReader.get("admin.url"));
    }

    public void login(String email, String password) {
        page.fill(EMAIL_INPUT, email);
        page.fill(PASSWORD_INPUT, password);
        page.click(SIGN_IN_BUTTON);
    }

    // NEW: reusable admin login for other tests
    public void loginAsAdmin() {
        open();
        login(ConfigReader.get("admin.email"), ConfigReader.get("admin.password"));
    }

    /** Logged in = redirected away from /public/login. */
    public boolean isLoggedIn() {
        try {
            page.waitForURL(url -> !url.contains("/public/login"));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }
}