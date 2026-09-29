package WebFunctionalTests.AuthTests;

import init.BaseRunner;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.customerPages.HomePage;
import pages.WebPages.customerPages.LoginPage;
import utils.ConfigReader;
import utils.RetryAnalyzer;
import utils.TestDataGenerator;

public class LoginTest extends BaseRunner {

    @Test(description = "Valid phone + OTP logs the user in successfully",
            retryAnalyzer = RetryAnalyzer.class)
    public void validOtpLogsUserIn() {
        HomePage homePage = new HomePage(page).open(ConfigReader.baseUrl());
        homePage.openLogin();

        LoginPage loginPage = new LoginPage(page);
        loginPage.loginWithPhoneAndOtp(TestDataGenerator.randomRiderPhone(), ConfigReader.testUserOtp());

        Assert.assertTrue(homePage.isLoggedIn(), "Expected account menu to be visible after successful login");
    }

    @Test(description = "Invalid OTP shows an error and does not log the user in",
            retryAnalyzer = RetryAnalyzer.class)
    public void invalidOtpShowsError() {
        HomePage homePage = new HomePage(page).open(ConfigReader.baseUrl());
        homePage.openLogin();

        LoginPage loginPage = new LoginPage(page);
        loginPage.enterPhoneNumber(TestDataGenerator.randomRiderPhone());
        loginPage.submitPhoneNumber();
        loginPage.enterOtp("0000");
        loginPage.submitOtp();

        Assert.assertTrue(loginPage.isErrorVisible(), "Expected a login error message for invalid OTP");
        Assert.assertFalse(homePage.isLoggedIn(), "User should not be logged in after invalid OTP");
    }
}
