package com.hopon.tests;

import com.hopon.base.BaseTest;
import com.hopon.pages.CreateRestaurantPage;
import com.hopon.pages.LoginPage;
import com.hopon.pages.MenuManagementPage;
import com.hopon.pages.RestaurantLoginPage;
import com.hopon.utils.GoogleSheetsUtil;
import com.hopon.utils.Steps;
import org.testng.annotations.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class CreateFoodTest extends BaseTest {

    @Test
    public void createFood() {
        CreateRestaurantPage foodPortal = new CreateRestaurantPage(page);
        RestaurantLoginPage restaurantLogin = new RestaurantLoginPage(page);
        MenuManagementPage menu = new MenuManagementPage(page);

        GoogleSheetsUtil sheet = GoogleSheetsUtil.getInstance();
        GoogleSheetsUtil.RestaurantCredential restaurant = sheet.lastCredential();   // the last restaurant in the sheet
        String categoryName = sheet.lastCategoryName(restaurant.row());              // the last category saved for it
        String foodName = "Cheese Automated Burger " + sheet.nextFoodNumber();
        System.out.println("Restaurant: " + restaurant.email() + " | Category: " + categoryName + " | Food: " + foodName);

        // Same login steps as AddCategoryTest
        Steps.run("Login as admin", () -> new LoginPage(page).loginAsAdmin());
        Steps.run("Switch to Hop on Food", foodPortal::switchToHopOnFood);
        Steps.run("Sign out", restaurantLogin::signOut);
        Steps.run("Login as the last created restaurant",
                () -> restaurantLogin.loginAs(restaurant.email(), restaurant.password()));
        Steps.run("Wait for the restaurant portal", restaurantLogin::waitForPortal);

        Steps.run("Open Menu Management", menu::openMenuManagement);
        Steps.run("Click Add Food Item", menu::clickAddFoodItem);
        Steps.run("Enter food name", () -> menu.enterFoodName(foodName));
        Steps.run("Select category", () -> menu.selectFoodCategory(categoryName));
        Steps.run("Select food type", () -> menu.selectFoodType("Burger"));
        Steps.run("Enter description", () -> menu.enterFoodDescription("Test Automation"));
        Steps.run("Enable variation", menu::enableVariation);
        Steps.run("Enter variation name", () -> menu.setVariationName("Size"));
        Steps.run("Fill portion L", () -> menu.fillPortion(0, "L", "350"));
        Steps.run("Add portion", menu::addPortion);
        Steps.run("Fill portion M", () -> menu.fillPortion(1, "M", "250"));
        Steps.run("Upload food photo", menu::uploadFoodPhoto);
        Steps.run("Save and publish", menu::saveAndPublish);

        
        saveLastCreatedRestaurantFile(restaurant, categoryName, foodName);

        // Saved only after the food was published
        Steps.run("Save food to the sheet", () -> sheet.saveFood(restaurant.row(), foodName));
    }

    private static void saveLastCreatedRestaurantFile(GoogleSheetsUtil.RestaurantCredential restaurant,
                                                        String categoryName, String foodName) {
        Properties props = new Properties();
        props.setProperty("name", restaurant.name());
        props.setProperty("email", restaurant.email());
        props.setProperty("password", restaurant.password());
        props.setProperty("restaurantId", restaurant.restaurantId());
        props.setProperty("category", categoryName);
        props.setProperty("food", foodName);
        try {
            Files.createDirectories(Path.of("target"));
            try (FileOutputStream out = new FileOutputStream("target/last-created-restaurant.properties")) {
                props.store(out, "Written by CreateFoodTest - read by utils.AdminCreatedRestaurant in the main project");
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not write target/last-created-restaurant.properties", e);
        }
    }
}