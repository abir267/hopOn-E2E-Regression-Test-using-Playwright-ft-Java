package com.hopon.pages;

import com.hopon.config.ConfigReader;
import com.hopon.utils.RestaurantData;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.LoadState;

import java.util.concurrent.ThreadLocalRandom;

public class CreateRestaurantPage extends BasePage {

    private static final String HOP_ON_FOOD_BUTTON = "button:has-text('Hop on Food')";
    private static final String RESTAURANTS_LINK = "a[href='/restaurants']";

    private static final String ADD_RESTAURANT_BUTTON = "button:has-text('Add Restaurant')";
    private static final String NAME_EN_INPUT = "input[formcontrolname='restaurantNameEN']";
    private static final String OWNER_NAME_INPUT = "input[formcontrolname='ownerName']";
    private static final String EMAIL_INPUT = "input[formcontrolname='email']";
    private static final String PHONE_INPUT = "input[formcontrolname='phone']";
    private static final String PASSWORD_INPUT = "input[formcontrolname='password']";
    private static final String CONFIRM_PASSWORD_INPUT = "input[formcontrolname='confirmPassword']";
    private static final String CHOOSE_ON_MAP_BUTTON = "button:has-text('Choose on Map')";
    private static final String MY_LOCATION_BUTTON = "button[title='Use my location']";
    private static final String CONFIRM_LOCATION_BUTTON = "button:has-text('Confirm Location')";
    private static final String ADDRESS_TEXTAREA = "textarea[formcontrolname='address']";
    private static final String ZONE_DROPDOWN =
            ".p-select:not(.p-disabled):has-text('Select Zone') div[aria-label='dropdown trigger']";
    private static final String ZONE_SEARCH_INPUT = "input.p-select-filter";
    private static final String MIN_PREP_TIME_INPUT = "input[formcontrolname='minFoodPrepTime']";

    // NEW: the Store Status (Accepting Orders) toggle. This is the visible switch
    private static final String STORE_STATUS_TOGGLE = "div.w-11.h-6.rounded-full.peer";

    // Each file input is found as "the first file input after this label text"
    private static final String LOGO_INPUT = fileInputAfter("Restaurant Logo");
    private static final String COVER_INPUT = fileInputAfter("Cover Image");
    private static final String BIN_INPUT = fileInputAfter("Business License");
    private static final String TIN_INPUT = fileInputAfter("Tax Identification");
    private static final String TRADE_LICENCE_INPUT = fileInputAfter("Trade Licence");
    private static final String FOOD_SAFETY_INPUT = fileInputAfter("Food Safety Certificate");
    private static final String NID_ZONE = "text=Click to upload NID";
    private static final String NID_FRONT_INPUT = fileInputAfter("Front Side");
    private static final String NID_BACK_INPUT = fileInputAfter("Back Side");
    private static final String USE_THIS_IMAGE_BUTTON = "button:has-text('Use This Image'):visible";
    private static final String UPLOAD_BUTTON = "button:text-is('Upload')";
    private static final String CONTINUE_BUTTON = "button:has-text('Continue')";

    // Cuisine & Operational Details tab
    private static final String APPLY_MONDAY_HOURS_BUTTON = "button:has-text('Apply the Monday configuration')";
    private static final String UPDATE_CUISINES_BUTTON = "button:has-text('Update Cuisines')";

    // Banking Details tab
    private static final String BANK_DROPDOWN = "p-select[formcontrolname='bankId'] div[aria-label='dropdown trigger']";
    private static final String ACCOUNT_HOLDER_INPUT = "input[formcontrolname='accountHolderName']";
    private static final String ACCOUNT_NUMBER_INPUT = "input[formcontrolname='accountNumberIban']";
    // The create flow says "Add Banking Details", the edit screen says "Update Banking Details"
    private static final String UPDATE_BANKING_BUTTON = "button:visible:text-matches('(Add|Update) Banking Details')";

    // Order Limits & Delivery Area tab
    private static final String ORDER_LIMITS_TAB = "button:has-text('Order Limits & Delivery Area')";
    private static final String MANAGE_AREA_BUTTON = "button:visible:has-text('Manage Area')";
    // The real Submit button. It carries routerlink="/restaurants" and goes back to the list
    private static final String SUBMIT_BUTTON = "button[routerlink='/restaurants']:has-text('Submit')";

    // Order Limits & Fees card (opens an "Edit Order Limits & Fees" popup)
    private static final String ORDER_FEES_EDIT_BUTTON = "button:visible:text-is('Edit')";
    private static final String MIN_ORDER_AMOUNT_INPUT = "input[formcontrolname='minOrderAmount']";
    private static final String VAT_RATE_INPUT = "input[formcontrolname='vatRatePercent']";
    // The popup's real Save button
    private static final String ORDER_FEES_SAVE_BUTTON = "button[type='submit']:visible:has-text('Save Changes')";

    // The map inside the Manage Delivery Area popup (only that map has the draw toolbar)
    private static final String DELIVERY_MAP = ".leaflet-container:has(.leaflet-draw-draw-polygon)";
    private static final String CURRENT_LOCATION_CONTROL = ".leaflet-control-locate";
    private static final String DRAW_POLYGON_BUTTON = "a.leaflet-draw-draw-polygon";
    private static final String ZOOM_OUT_CONTROL = "a.leaflet-control-zoom-out";   // NEW
    // The square corner markers Leaflet.draw puts on each clicked point
    private static final String POLYGON_VERTEX_MARKER = ".leaflet-marker-icon.leaflet-editing-icon";
    private static final String SAVE_CHANGES_BUTTON = "button:visible:has-text('Save Changes')";

    // NEW: delivery area size. How many times bigger than the first version (a triangle at 20% of the map)
    // the area should be. A polygon can't grow past the edge of the map, so the extra size comes from zooming
    // the map out: every zoom step doubles the distance. Raise this number for a bigger area.
    private static final double DELIVERY_AREA_MULTIPLIER = 10;
    private static final double FIRST_VERSION_RADIUS_FACTOR = 0.2;
    // The most that fits: corners sit at 42% of the map's shorter side from the center
    private static final double POLYGON_RADIUS_FACTOR = 0.42;

    public CreateRestaurantPage(Page page) {
        super(page);
    }

    private static String fileInputAfter(String labelText) {
        return "xpath=(//*[text()[contains(.,'" + labelText + "')]])[1]/following::input[@type='file'][1]";
    }

    public void switchToHopOnFood() {
        page.click(HOP_ON_FOOD_BUTTON);
    }

    public void openRestaurants() {
        page.click(RESTAURANTS_LINK);
        page.waitForURL(url -> url.contains("/restaurants"));
    }

    public void clickAddRestaurant() {
        click(ADD_RESTAURANT_BUTTON);
    }

    public void fillBusinessInfo(RestaurantData data) {
        clickAndFill(NAME_EN_INPUT, data.name());
        clickAndFill(OWNER_NAME_INPUT, data.owner());
        clickAndFill(EMAIL_INPUT, data.email());
        clickAndFill(PHONE_INPUT, data.phone());
        clickAndFill(PASSWORD_INPUT, data.password());
        clickAndFill(CONFIRM_PASSWORD_INPUT, data.password());
    }

    public void pickCurrentLocation() {
        click(CHOOSE_ON_MAP_BUTTON);
        page.click(MY_LOCATION_BUTTON);
        // Wait for the address lookup to finish so the Selected Location text is filled
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.click(CONFIRM_LOCATION_BUTTON);

        // Wait until the Business Address textarea is filled, which confirms the map step worked
        page.waitForFunction(
                "sel => { const el = document.querySelector(sel); return el && el.value.trim().length > 0; }",
                ADDRESS_TEXTAREA);
    }

    public void selectOperationZone(String searchText, String zoneName) {
        click(ZONE_DROPDOWN);
        page.fill(ZONE_SEARCH_INPUT, searchText);
        page.click("span[data-pc-section='optionlabel']:has-text('" + zoneName + "')");
    }

    public void setMinPrepTime(int minutes) {
        clickAndFill(MIN_PREP_TIME_INPUT, String.valueOf(minutes));
    }

    // NEW: switches Store Status (Accepting Orders) on. Does nothing if it is already on
    public void activateStoreStatus() {
        scrollToCenter(STORE_STATUS_TOGGLE);
        Locator toggle = page.locator(STORE_STATUS_TOGGLE);
        // The real checkbox sits next to the visible switch. This returns true / false, or null if it is not found
        String checkedState = "el => { const i = el.closest('label')?.querySelector('input[type=checkbox]')"
                + " || el.previousElementSibling; return i ? i.checked : null; }";

        if (Boolean.TRUE.equals(toggle.evaluate(checkedState))) {
            return;
        }
        toggle.click();
        page.waitForTimeout(300);
        if (Boolean.FALSE.equals(toggle.evaluate(checkedState))) {
            throw new IllegalStateException("Store status did not turn on");
        }
    }

    public void uploadLogo() {
        uploadAndConfirm(LOGO_INPUT, ConfigReader.get("file.logo"));
    }

    public void uploadCover() {
        uploadAndConfirm(COVER_INPUT, ConfigReader.get("file.cover"));
    }

    public void uploadBinDocument() {
        uploadAndConfirm(BIN_INPUT, ConfigReader.get("file.doc"));
    }

    public void uploadTinDocument() {
        uploadAndConfirm(TIN_INPUT, ConfigReader.get("file.doc"));
    }

    public void uploadTradeLicenceDocument() {
        uploadAndConfirm(TRADE_LICENCE_INPUT, ConfigReader.get("file.doc"));
    }

    public void uploadFoodSafetyDocument() {
        uploadAndConfirm(FOOD_SAFETY_INPUT, ConfigReader.get("file.doc"));
    }

    // The NID box opens a popup with a front and a back upload
    public void uploadOwnerNid() {
        String doc = ConfigReader.get("file.doc");
        click(NID_ZONE);
        uploadAndConfirm(NID_FRONT_INPUT, doc);
        uploadAndConfirm(NID_BACK_INPUT, doc);
        page.click(UPLOAD_BUTTON);
        waitUntilHidden(UPLOAD_BUTTON);
    }

    // Choose the file, wait 3 seconds, click "Use This Image", wait for the popup to close
    private void uploadAndConfirm(String inputSelector, String fileName) {
        uploadFile(inputSelector, fileName);
        page.click(USE_THIS_IMAGE_BUTTON);
        waitUntilHidden(USE_THIS_IMAGE_BUTTON);
    }

    public void clickContinue() {
        page.click(CONTINUE_BUTTON);
    }

    // Ticks the cuisine checkbox. Safe if it is already ticked
    public void selectCuisine(String cuisine) {
        String checkbox = "label:has(span:text-is('" + cuisine + "')) input[type='checkbox']";
        scrollToCenter(checkbox);
        page.locator(checkbox).check();
    }

    public void applyMondayHoursToAllDays() {
        click(APPLY_MONDAY_HOURS_BUTTON);
    }

    // Saves cuisines and hours, which moves on to the Banking Details tab
    public void clickUpdateCuisinesAndHours() {
        click(UPDATE_CUISINES_BUTTON);
    }

    public void selectBank(String bankName) {
        click(BANK_DROPDOWN);
        page.click("span[data-pc-section='optionlabel']:has-text('" + bankName + "')");
    }

    public void fillAccountDetails(String holderName, String accountNumber) {
        clickAndFill(ACCOUNT_HOLDER_INPUT, holderName);
        clickAndFill(ACCOUNT_NUMBER_INPUT, accountNumber);
    }

    public String getEnteredRestaurantName() {
        return page.inputValue(NAME_EN_INPUT);
    }

    public String getEnteredAddress() {
        return page.inputValue(ADDRESS_TEXTAREA);
    }

    // Clicks "Add Banking Details" (or "Update Banking Details")
    public void clickUpdateBankingDetails() {
        click(UPDATE_BANKING_BUTTON);
    }

    // Order Limits & Fees card. Edit, fill the two fields, save.
    // If the page did not move to the Order Limits tab by itself, click the tab first.
    public void setupOrderLimitsAndFees(String minOrderAmount, String vatRatePercent) {
        try {
            page.locator(ORDER_FEES_EDIT_BUTTON).first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(5000));
        } catch (PlaywrightException e) {
            click(ORDER_LIMITS_TAB);
        }
        click(ORDER_FEES_EDIT_BUTTON);
        clickAndFill(MIN_ORDER_AMOUNT_INPUT, minOrderAmount);
        clickAndFill(VAT_RATE_INPUT, vatRatePercent);
        click(ORDER_FEES_SAVE_BUTTON);
        // The inputs disappear once the popup is saved and closes
        waitUntilHidden(VAT_RATE_INPUT);
    }

    // Opens the Manage Delivery Area popup.
    // If the page did not move to the Order Limits tab by itself, click the tab first.
    public void openManageArea() {
        try {
            page.locator(MANAGE_AREA_BUTTON).first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(5000));
        } catch (PlaywrightException e) {
            click(ORDER_LIMITS_TAB);
        }
        click(MANAGE_AREA_BUTTON);
        // The popup is ready once its map shows the current location control
        page.locator(CURRENT_LOCATION_CONTROL).last().waitFor();
    }

    // Centers the popup map on the current location
    public void clickCurrentLocationOnDeliveryMap() {
        page.locator(CURRENT_LOCATION_CONTROL).last().click();
        page.waitForTimeout(3000); // let the map move and the tiles load
    }

    // Draws a random triangle around the center of the map. The map is zoomed out first so the area is much bigger.
    // Click point 1, point 2, point 3, then the first point's marker again to close the polygon.
    public void drawDeliveryPolygon() {
        Locator map = page.locator(DELIVERY_MAP).last();
        zoomOutDeliveryMap(map);                                  // NEW

        click(DRAW_POLYGON_BUTTON);

        BoundingBox box = map.boundingBox();
        double centerX = box.x + box.width / 2;
        double centerY = box.y + box.height / 2;

        // UPDATED: the polygon uses the most of the map that fits. Its real size on the ground comes from the zoom-out
        double radiusLimit = Math.min(box.width, box.height) * POLYGON_RADIUS_FACTOR;

        // Three corners spread around the center, each with a random angle and distance,
        // so every run draws a different polygon that still surrounds the current location
        double[] baseAngles = {-90, 30, 150};
        double[][] points = new double[3][2];
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 3; i++) {
            double angle = Math.toRadians(baseAngles[i] + random.nextDouble(-15, 15));
            double radius = radiusLimit * random.nextDouble(0.8, 1.0);
            points[i][0] = centerX + radius * Math.cos(angle);
            points[i][1] = centerY + radius * Math.sin(angle);
        }

        clickMapPoint(points[0][0], points[0][1]); // 1st point
        clickMapPoint(points[1][0], points[1][1]); // 2nd point
        clickMapPoint(points[2][0], points[2][1]); // 3rd point

        // 1st point again: click its real marker so Leaflet closes the polygon
        Locator firstMarker = map.locator(POLYGON_VERTEX_MARKER).first();
        if (firstMarker.count() > 0) {
            BoundingBox markerBox = firstMarker.boundingBox();
            double markerX = markerBox.x + markerBox.width / 2;
            double markerY = markerBox.y + markerBox.height / 2;
            page.mouse().move(markerX, markerY, new Mouse.MoveOptions().setSteps(8));
            page.waitForTimeout(500);
            page.mouse().click(markerX, markerY);
        } else {
            clickMapPoint(points[0][0], points[0][1]); // fallback: same coordinates as the 1st click
        }
        page.waitForTimeout(1000); // let the polygon finish closing
    }

    // NEW: zooms the popup map out so the same-looking polygon covers a much bigger area on the ground
    private void zoomOutDeliveryMap(Locator map) {
        // Size on screen compared with the first version, then how many doublings are still needed
        double sizeFromScreen = POLYGON_RADIUS_FACTOR / FIRST_VERSION_RADIUS_FACTOR;
        int steps = (int) Math.round(Math.log(DELIVERY_AREA_MULTIPLIER / sizeFromScreen) / Math.log(2));
        if (steps <= 0) {
            return;
        }

        Locator zoomOut = map.locator(ZOOM_OUT_CONTROL).first();
        if (zoomOut.count() == 0) {
            System.out.println("The delivery map has no zoom-out control, so the zoom step is skipped");
            return;
        }
        System.out.println("Delivery map: zooming out " + steps + " step(s)");
        for (int i = 0; i < steps; i++) {
            if ("true".equals(zoomOut.getAttribute("aria-disabled"))) {
                break;                                            // the map is already as far out as it goes
            }
            zoomOut.click();
            page.waitForTimeout(800);                             // let the zoom animation finish
        }
        page.waitForTimeout(1000);                                // let the tiles load
    }

    // Move first, pause, then click. The pauses stop two clicks being read as a double click
    private void clickMapPoint(double x, double y) {
        page.mouse().move(x, y);
        page.waitForTimeout(300);
        page.mouse().click(x, y);
        page.waitForTimeout(600);
    }

    // Saves the delivery area and waits for the popup to close
    public void saveDeliveryArea() {
        click(SAVE_CHANGES_BUTTON);
        waitUntilHidden(SAVE_CHANGES_BUTTON);
    }

    // The last step of creating a restaurant. Submit goes back to the Restaurants list.
    // It stays disabled until the limits and the delivery area are saved, and the click waits for it to enable
    public void clickSubmit() {
        click(SUBMIT_BUTTON);
        // The edit screen is /restaurant/edit/<id>, the list is /restaurants
        page.waitForURL(url -> url.contains("/restaurants"));
    }
}