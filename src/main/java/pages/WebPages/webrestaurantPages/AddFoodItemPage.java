package pages.WebPages.webrestaurantPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Paths;

/**
 * "Add Food Item" panel inside Menu Management (/menu-management, opened via
 * the "Add Food Item" button - it's an in-page panel, not its own route) -
 * CONFIRMED against the live site (2026-10-24 probe, logged in as
 * qaabir@gmail.com on QA Burger Express [AA]):
 *   - Category is a PrimeNG p-select (formcontrolname="categoryId"), Food
 *     Types and Tags are p-multiselect (formcontrolname="foodType"/"tags") -
 *     none are plain &lt;select&gt;, options render as a detached overlay
 *     with li[role=option].
 *   - Required fields confirmed by their own inline error text: Food Item
 *     Name, Category, Food Types (at least one), Short Description, Price,
 *     and the item image (upload box reads "(Image Required)"). Submitting
 *     empty leaves the panel open with those errors instead of navigating
 *     anywhere, so "still showing the panel" is the signal for a blocked
 *     submit - there is no separate URL for this panel.
 *   - On success a toast reading "Food item added successfully" appears and
 *     the panel closes back to the item list.
 *   - The upload accepts PNG/JPG up to 5MB; callers pass a real photo under
 *     src/test/resources/test-images rather than a generated placeholder, so
 *     items created by automation look like real menu items in the admin UI.
 */
public class AddFoodItemPage extends AdminBasePage {

    private static final String NAME_EN = "input[formcontrolname='nameEn']";
    private static final String NAME_BN = "input[formcontrolname='nameBn']";
    private static final String ITEM_CODE = "input[formcontrolname='itemCode']";
    private static final String CATEGORY = "p-select[formcontrolname='categoryId']";
    private static final String FOOD_TYPE = "p-multiselect[formcontrolname='foodType']";
    private static final String TAGS = "p-multiselect[formcontrolname='tags']";
    private static final String DESCRIPTION_EN = "textarea[formcontrolname='descriptionEn']";
    private static final String DESCRIPTION_BN = "textarea[formcontrolname='descriptionBn']";
    private static final String PRICE = "input[formcontrolname='price']";
    private static final String IMAGE_INPUT = "input[type=file]";
    private static final String DROPDOWN_OPTION = "li[role=option]";
    private static final String SUCCESS_TOAST = "Food item added successfully";

    public AddFoodItemPage(Page page) {
        super(page);
    }

    public AddFoodItemPage enterName(String nameEn) {
        type(NAME_EN, nameEn);
        return this;
    }

    public AddFoodItemPage enterNameBn(String nameBn) {
        type(NAME_BN, nameBn);
        return this;
    }

    public AddFoodItemPage enterItemCode(String itemCode) {
        type(ITEM_CODE, itemCode);
        return this;
    }

    /** Opens the Category dropdown and picks the option matching this text (any option if null). */
    public AddFoodItemPage selectCategory(String optionText) {
        page.locator(CATEGORY + " [role=combobox]").click();
        page.waitForTimeout(400);
        pickDropdownOption(optionText);
        return this;
    }

    /** Opens the Food Types multiselect and picks the option matching this text (any option if null), then closes it. */
    public AddFoodItemPage selectFoodType(String optionText) {
        page.locator(FOOD_TYPE).click();
        page.waitForTimeout(400);
        pickDropdownOption(optionText);
        page.keyboard().press("Escape");
        return this;
    }

    public AddFoodItemPage selectTag(String optionText) {
        page.locator(TAGS).click();
        page.waitForTimeout(400);
        pickDropdownOption(optionText);
        page.keyboard().press("Escape");
        return this;
    }

    private void pickDropdownOption(String optionText) {
        Locator options = page.locator(DROPDOWN_OPTION);
        Locator target = optionText == null
                ? options.first()
                : options.filter(new Locator.FilterOptions().setHasText(optionText)).first();
        target.click();
    }

    public AddFoodItemPage enterDescription(String descriptionEn) {
        type(DESCRIPTION_EN, descriptionEn);
        return this;
    }

    public AddFoodItemPage enterDescriptionBn(String descriptionBn) {
        type(DESCRIPTION_BN, descriptionBn);
        return this;
    }

    public AddFoodItemPage enterPrice(String price) {
        type(PRICE, price);
        return this;
    }

    /** Uploads a real photo from src/test/resources/test-images (path relative to the project root). */
    public AddFoodItemPage uploadImage(String imagePath) {
        page.locator(IMAGE_INPUT).setInputFiles(Paths.get(imagePath));
        page.waitForTimeout(500);
        return this;
    }

    public boolean isPanelVisible() {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Add Food Item")).isVisible();
    }

    public boolean isFieldErrorVisible(String errorText) {
        return page.getByText(errorText).isVisible();
    }

    public boolean isSuccessToastVisible() {
        return page.getByText(SUCCESS_TOAST).isVisible();
    }

    /**
     * True while required fields are still invalid - CONFIRMED the "Save & Publish"
     * button itself is disabled (not just unvalidated) until the form is valid, so an
     * incomplete form can't be submitted by clicking it at all.
     */
    public boolean isSaveAndPublishDisabled() {
        return !page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save & Publish")).isEnabled();
    }

    /** Clicks Save & Publish and, on success, returns the resulting Menu Management list. */
    public MenuManagementPage clickSaveAndPublish() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save & Publish")).click();
        page.waitForTimeout(1500);
        return new MenuManagementPage(page);
    }
}
