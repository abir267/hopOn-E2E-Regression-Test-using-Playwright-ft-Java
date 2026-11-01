package com.hopon.pages;

import com.hopon.config.ConfigReader;
import com.hopon.utils.CategoryData;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;

public class MenuManagementPage extends BasePage {

    private static final String MENU_MANAGEMENT_LINK = "a[href='/menu-management']";

    // ---------- Category ----------
    // The "+" next to Categories: the first visible button that holds a plus icon
    private static final String ADD_CATEGORY_BUTTON = "button:visible:has(svg.lucide-plus) >> nth=0";
    private static final String NAME_EN_INPUT = "input[formcontrolname='nameEn']";
    private static final String NAME_BN_INPUT = "input[formcontrolname='nameBn']";
    private static final String DESCRIPTION_TEXTAREA = "textarea[formcontrolname='descriptionEn']";
    private static final String CREATE_CATEGORY_BUTTON = "button[type='submit']:has-text('Create Category')";

    // ---------- Food ----------
    private static final String ADD_FOOD_BUTTON = "button:visible:has-text('Add Food Item')";
    // The food name and the portion names both use formcontrolname=nameEn, so they are told apart by placeholder
    private static final String FOOD_NAME_INPUT = "input[formcontrolname='nameEn'][placeholder='e.g. Classic Cheese Burger']";
    private static final String FOOD_CATEGORY_DROPDOWN = "span[role='combobox'][aria-label='Select a Category']";
    private static final String FOOD_CATEGORY_SEARCH_INPUT = "input.p-select-filter";
    private static final String FOOD_TYPE_DROPDOWN = "p-multiselect[formcontrolname='foodType']";
    private static final String FOOD_TYPE_SEARCH_INPUT = "input.p-multiselect-filter";
    private static final String FOOD_DESCRIPTION_TEXTAREA = "textarea[formcontrolname='descriptionEn']";

    // The variation toggle is the switch that is still OFF (grey). "Available to Order" is already ON (green)
    private static final String VARIATION_TOGGLE = "div.w-9.h-5.rounded-full.cursor-pointer.bg-muted:visible";
    private static final String VARIATION_NAME_INPUT = "input[formcontrolname='variationName']";
    private static final String PORTION_NAME_INPUT = "input[formcontrolname='nameEn'][placeholder='e.g. Regular']";
    private static final String PORTION_PRICE_INPUT = "input[formcontrolname='price']";
    private static final String ADD_PORTION_BUTTON = "button:has-text('Add Portion')";

    private static final String FOOD_PHOTO_INPUT = "input[type='file']";
    private static final String USE_THIS_IMAGE_BUTTON = "button:has-text('Use This Image'):visible";
    private static final String SAVE_PUBLISH_BUTTON = "button:has-text('Save & Publish')";

    public MenuManagementPage(Page page) {
        super(page);
    }

    public void openMenuManagement() {
        page.click(MENU_MANAGEMENT_LINK);
        page.waitForURL(url -> url.contains("/menu-management"));
    }

    // =====================  Category  =====================

    // Clicks the + on Categories and waits for the Add New Category popup
    public void openAddCategory() {
        click(ADD_CATEGORY_BUTTON);
        page.locator(NAME_EN_INPUT).waitFor();
    }

    public void fillCategory(CategoryData category) {
        clickAndFill(NAME_EN_INPUT, category.nameEn());
        clickAndFill(NAME_BN_INPUT, category.nameBn());
        clickAndFill(DESCRIPTION_TEXTAREA, category.description());
    }

    // Clicks Create Category, waits for the popup to close, then for the new category to show in the list
    public void createCategory(String categoryName) {
        click(CREATE_CATEGORY_BUTTON);
        waitUntilHidden(NAME_EN_INPUT);
        page.getByText(categoryName).first().waitFor();
    }

    // =====================  Food  =====================

    // There can be two "Add Food Item" buttons (header and empty state), so take the first
    public void clickAddFoodItem() {
        page.locator(ADD_FOOD_BUTTON).first().click();
    }

    public void enterFoodName(String name) {
        clickAndFill(FOOD_NAME_INPUT, name);
    }

    public void selectFoodCategory(String categoryName) {
        click(FOOD_CATEGORY_DROPDOWN);
        Locator search = page.locator(FOOD_CATEGORY_SEARCH_INPUT);
        try {
            search.waitFor(new Locator.WaitForOptions().setTimeout(2000));
            search.fill(categoryName);
        } catch (PlaywrightException e) {
            // this dropdown has no search box, pick straight from the list
        }
        page.locator("li[role='option'] span[data-pc-section='optionlabel']:text-is('" + categoryName + "')")
                .first().click();
    }

    // Searches the type list and ticks the top match
    public void selectFoodType(String type) {
        click(FOOD_TYPE_DROPDOWN);
        page.fill(FOOD_TYPE_SEARCH_INPUT, type);
        page.waitForTimeout(500); // let the list filter
        page.locator(".p-multiselect-overlay li.p-multiselect-option:has(span:text-is('" + type + "'))")
                .first().click();
        page.keyboard().press("Escape"); // close the list so it does not cover the form
    }

    public void enterFoodDescription(String description) {
        clickAndFill(FOOD_DESCRIPTION_TEXTAREA, description);
    }

    public void enableVariation() {
        page.locator(VARIATION_TOGGLE).first().click();
    }

    public void setVariationName(String name) {
        clickAndFill(VARIATION_NAME_INPUT, name);
    }

    // index 0 = first portion row, 1 = second, and so on
    public void fillPortion(int index, String optionName, String price) {
        Locator nameInput = page.locator(PORTION_NAME_INPUT).nth(index);
        nameInput.click();
        nameInput.fill(optionName);
        Locator priceInput = page.locator(PORTION_PRICE_INPUT).nth(index);
        priceInput.click();
        priceInput.fill(price);
    }

    public void addPortion() {
        click(ADD_PORTION_BUTTON);
    }

    // Uploads the photo. If the crop popup appears, confirms it with "Use This Image"
    public void uploadFoodPhoto() {
        uploadFile(FOOD_PHOTO_INPUT, ConfigReader.get("file.food"));
        try {
            page.locator(USE_THIS_IMAGE_BUTTON).first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(5000));
            page.locator(USE_THIS_IMAGE_BUTTON).first().click();
            waitUntilHidden(USE_THIS_IMAGE_BUTTON);
        } catch (PlaywrightException e) {
            // no crop popup for this upload
        }
    }

    public void saveAndPublish() {
        click(SAVE_PUBLISH_BUTTON);
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }
}