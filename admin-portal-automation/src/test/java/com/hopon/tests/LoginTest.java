package com.hopon.tests;

import com.hopon.base.BaseTest;
import com.hopon.config.ConfigReader;
import com.hopon.pages.LoginPage;
import com.hopon.utils.SheetScenario;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LoginTest extends BaseTest {

    @Test
    @SheetScenario(feature = "Login", scenario = "Login with valid admin credentials")
    public void loginWithValidCredentials() {
        LoginPage loginPage = new LoginPage(page);
        loginPage.open();
        loginPage.login(ConfigReader.get("admin.email"), ConfigReader.get("admin.password"));
        Assert.assertTrue(loginPage.isLoggedIn(), "Still on the login page after signing in");
    }
}
