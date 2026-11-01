package com.hopon.tests;

import com.hopon.base.BaseTest;
import com.hopon.pages.CreateRestaurantPage;
import com.hopon.pages.LoginPage;
import com.hopon.pages.RestaurantLoginPage;
import com.hopon.utils.GoogleSheetsUtil;
import com.hopon.utils.Steps;
import org.testng.annotations.Test;

public class RestaurantLoginTest extends BaseTest {

    @Test
    public void loginWithLastCreatedRestaurant() {
        CreateRestaurantPage foodPortal = new CreateRestaurantPage(page);
        RestaurantLoginPage restaurantLogin = new RestaurantLoginPage(page);

        // The last restaurant saved in the Credentials tab
        GoogleSheetsUtil.RestaurantCredential restaurant = GoogleSheetsUtil.getInstance().lastCredential();
        System.out.println("Logging in as: " + restaurant.email());

        Steps.run("Login as admin", () -> new LoginPage(page).loginAsAdmin());
        Steps.run("Switch to Hop on Food", foodPortal::switchToHopOnFood);
        Steps.run("Sign out", restaurantLogin::signOut);
        Steps.run("Login as the last created restaurant",
                () -> restaurantLogin.loginAs(restaurant.email(), restaurant.password()));
        Steps.run("Wait for the restaurant portal", restaurantLogin::waitForPortal);
    }
}