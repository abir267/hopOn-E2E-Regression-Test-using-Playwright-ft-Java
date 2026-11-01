package com.hopon.tests;

import com.hopon.base.BaseTest;
import com.hopon.pages.CreateRestaurantPage;
import com.hopon.pages.LoginPage;
import com.hopon.pages.MenuManagementPage;
import com.hopon.pages.RestaurantLoginPage;
import com.hopon.utils.CategoryData;
import com.hopon.utils.GoogleSheetsUtil;
import com.hopon.utils.Steps;
import org.testng.annotations.Test;

public class AddCategoryTest extends BaseTest {

    @Test
    public void addCategory() {
        CreateRestaurantPage foodPortal = new CreateRestaurantPage(page);
        RestaurantLoginPage restaurantLogin = new RestaurantLoginPage(page);
        MenuManagementPage menu = new MenuManagementPage(page);

        GoogleSheetsUtil sheet = GoogleSheetsUtil.getInstance();
        GoogleSheetsUtil.RestaurantCredential restaurant = sheet.lastCredential();   // the last restaurant in the sheet
        CategoryData category = CategoryData.forNumber(sheet.nextCategoryNumber());
        System.out.println("Restaurant: " + restaurant.email() + " | Category: " + category.nameEn());

        // Same login steps as RestaurantLoginTest
        Steps.run("Login as admin", () -> new LoginPage(page).loginAsAdmin());
        Steps.run("Switch to Hop on Food", foodPortal::switchToHopOnFood);
        Steps.run("Sign out", restaurantLogin::signOut);
        Steps.run("Login as the last created restaurant",
                () -> restaurantLogin.loginAs(restaurant.email(), restaurant.password()));
        Steps.run("Wait for the restaurant portal", restaurantLogin::waitForPortal);

        Steps.run("Open Menu Management", menu::openMenuManagement);
        Steps.run("Click + on Categories", menu::openAddCategory);
        Steps.run("Fill category details", () -> menu.fillCategory(category));
        Steps.run("Create category", () -> menu.createCategory(category.nameEn()));

        // Saved only after the category was created
        Steps.run("Save category to the sheet", () -> sheet.saveCategory(restaurant.row(), category.nameEn()));
    }
}