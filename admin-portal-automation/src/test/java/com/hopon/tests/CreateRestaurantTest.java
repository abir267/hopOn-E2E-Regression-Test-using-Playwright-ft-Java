package com.hopon.tests;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hopon.base.BaseTest;
import com.hopon.pages.CreateRestaurantPage;
import com.hopon.pages.LoginPage;
import com.hopon.utils.GoogleSheetsUtil;
import com.hopon.utils.PerformanceLog;
import com.hopon.utils.RestaurantApi;
import com.hopon.utils.RestaurantData;
import com.hopon.utils.Steps;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicReference;

public class CreateRestaurantTest extends BaseTest {

    @Test
    public void createRestaurant() {
        PerformanceLog.newRun();

        CreateRestaurantPage createRestaurant = new CreateRestaurantPage(page);
        RestaurantApi restaurantApi = new RestaurantApi(page, playwright);

        Steps.run("Login as admin", () -> new LoginPage(page).loginAsAdmin());
        Steps.run("Switch to Hop on Food", createRestaurant::switchToHopOnFood);
        Steps.run("Open Restaurants list", createRestaurant::openRestaurants);
        Steps.run("Click Add Restaurant", createRestaurant::clickAddRestaurant);

        int botNumber = GoogleSheetsUtil.getInstance().nextBotNumber();
        RestaurantData data = RestaurantData.forNumber(botNumber);

        Steps.run("Fill business info", () -> createRestaurant.fillBusinessInfo(data));
        Steps.run("Pick current location on map", createRestaurant::pickCurrentLocation);
        Steps.run("Select operation zone",
                () -> createRestaurant.selectOperationZone("gulshan", "Hopon Food [Gulshan-banani]"));
        Steps.run("Set minimum prep time", () -> createRestaurant.setMinPrepTime(30));

        Steps.run("Upload logo", createRestaurant::uploadLogo);
        Steps.run("Upload cover", createRestaurant::uploadCover);
        Steps.run("Activate store status", createRestaurant::activateStoreStatus);
        Steps.run("Upload BIN document", createRestaurant::uploadBinDocument);
        Steps.run("Upload TIN document", createRestaurant::uploadTinDocument);
        Steps.run("Upload Trade Licence document", createRestaurant::uploadTradeLicenceDocument);
        Steps.run("Upload Food Safety document", createRestaurant::uploadFoodSafetyDocument);
        Steps.run("Upload Owner NID (front and back)", createRestaurant::uploadOwnerNid);

        Assert.assertEquals(createRestaurant.getEnteredRestaurantName(), data.name());
        Assert.assertFalse(createRestaurant.getEnteredAddress().isBlank(), "Business address was not filled from the map");

        Steps.run("Click Continue", createRestaurant::clickContinue);

        // The new restaurant's ID is in the page address now
        String restaurantId = restaurantApi.waitForRestaurantId();
        System.out.println("Restaurant ID: " + restaurantId);

        // The sheet row also gets the restaurant ID. It is saved even if the ID was not found,
        // so the next run still moves on to the next number.
        Steps.run("Save restaurant to the sheet", () -> GoogleSheetsUtil.getInstance().appendCredential(
                data.name(), data.email(), data.password(), restaurantId == null ? "" : restaurantId));
        Assert.assertNotNull(restaurantId, "Could not read the restaurant ID from the page address after Continue");

        Steps.run("Select cuisine", () -> createRestaurant.selectCuisine("Bangladeshi"));
        Steps.run("Apply Monday hours to all days", createRestaurant::applyMondayHoursToAllDays);
        Steps.run("Update cuisines and hours", createRestaurant::clickUpdateCuisinesAndHours);

        Steps.run("Select bank", () -> createRestaurant.selectBank("Sonali Bank PLC"));
        Steps.run("Fill account details", () -> createRestaurant.fillAccountDetails("Abir", "1234567890"));
        Steps.run("Click Add Banking Details", createRestaurant::clickUpdateBankingDetails);

        // Order Limits & Delivery Area tab
        Steps.run("Set order limits and fees", () -> createRestaurant.setupOrderLimitsAndFees("99", "15"));
        Steps.run("Open Manage Area", createRestaurant::openManageArea);
        Steps.run("Go to current location on the delivery map", createRestaurant::clickCurrentLocationOnDeliveryMap);
        Steps.run("Draw delivery area", createRestaurant::drawDeliveryPolygon);
        Steps.run("Save delivery area", createRestaurant::saveDeliveryArea);
        Steps.run("Click Submit (back to the Restaurants list)", createRestaurant::clickSubmit);   // UPDATED name

        // Verify and activate through the APIs
        AtomicReference<JsonObject> saved = new AtomicReference<>();
        Steps.run("Verify restaurant through the API", () -> saved.set(restaurantApi.verifyRestaurant(restaurantId)));
        Steps.run("Activate restaurant through the API", () -> restaurantApi.activateRestaurant(restaurantId));

        // The saved restaurant must match what the bot entered
        JsonObject restaurant = saved.get();
        Assert.assertEquals(restaurant.get("verificationStatus").getAsString(), "VERIFIED", "Verification status");
        Assert.assertEquals(restaurant.get("nameEn").getAsString(), data.name(), "Restaurant name");
        Assert.assertEquals(restaurant.get("ownerName").getAsString(), data.owner(), "Owner name");
        Assert.assertEquals(restaurant.get("email").getAsString(), data.email(), "Email");
        Assert.assertEquals(restaurant.get("phone").getAsString(), data.phone(), "Phone");
        Assert.assertEquals(restaurant.getAsJsonObject("operationZone").get("name").getAsString(),
                "Hopon Food [Gulshan-banani]", "Operation zone");
        Assert.assertEquals(restaurant.get("expectedPreparationTimeInMin").getAsInt(), 30, "Minimum prep time");
        Assert.assertTrue(hasCuisine(restaurant, "Bangladeshi"), "Cuisine Bangladeshi was not saved");
        Assert.assertTrue(hasPaymentAccount(restaurant, "1234567890", "Abir"), "Bank account was not saved");
        Assert.assertEquals(restaurant.get("minOrderAmount").getAsDouble(), 99.0, 0.001, "Minimum order amount");
        Assert.assertEquals(restaurant.get("vatRatePercent").getAsDouble(), 15.0, 0.001, "VAT rate");
        Assert.assertTrue(restaurant.has("deliveryZone") && !restaurant.get("deliveryZone").isJsonNull(),
                "Delivery area was not saved");
    }

    // Writes the timing report after the last run
    @AfterClass(alwaysRun = true)
    public void writePerformanceReport() {
        PerformanceLog.writeReport();
    }

    private static boolean hasCuisine(JsonObject restaurant, String cuisine) {
        for (JsonElement item : restaurant.getAsJsonArray("cuisines")) {
            if (cuisine.equals(item.getAsJsonObject().get("nameEn").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPaymentAccount(JsonObject restaurant, String accountNo, String accountName) {
        JsonElement accounts = restaurant.get("paymentAccounts");
        if (accounts == null || !accounts.isJsonArray()) {
            return false;
        }
        for (JsonElement item : accounts.getAsJsonArray()) {
            JsonObject account = item.getAsJsonObject();
            if (accountNo.equals(account.get("accountNo").getAsString())
                    && accountName.equals(account.get("accountName").getAsString())) {
                return true;
            }
        }
        return false;
    }
}