package WebFunctionalTests.WebPortal;

import init.AdminBaseRunner;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.WebPages.webrestaurantPages.AddFoodItemPage;
import pages.WebPages.webrestaurantPages.MenuManagementPage;
import pages.WebPages.webrestaurantPages.RestaurantPortalPage;
import pages.WebPages.webrestaurantPages.WebRestaurantLogin;
import utils.ConfigReader;

/**
 * Covers the "Add Food Item" panel in Menu Management in the several ways a
 * restaurant admin actually uses it: a full-detail submission, a
 * minimum-required-fields submission, and the validation path when required
 * fields are left blank. See {@link AddFoodItemPage}'s class doc for the
 * confirmed required fields and selectors this is built on.
 */
public class AddFoodItemTest extends AdminBaseRunner {

    private AddFoodItemPage openAddFoodItemPanel() {
        RestaurantPortalPage portal = new WebRestaurantLogin(page)
                .loginWithEmailAndPassword(ConfigReader.restaurantAdminEmail(), ConfigReader.restaurantAdminPassword());
        portal.openMenuManagement();
        return new MenuManagementPage(page).openAddFoodItem();
    }

    @Test
    public void addFoodItem_withAllFieldsAndImage_succeeds() {
        String itemName = "Margherita Pizza";

        AddFoodItemPage addItem = openAddFoodItemPanel()
                .enterName(itemName)
                .enterNameBn("মার্গারিটা পিৎজা")
                .enterItemCode("PZA-" + System.currentTimeMillis())
                .selectCategory(null)
                .selectFoodType(null)
                .selectTag(null)
                .enterDescription("Classic wood-fired pizza with tomato, mozzarella, and fresh basil.")
                .enterDescriptionBn("টমেটো, মোজারেলা ও তুলসি পাতা দিয়ে তৈরি ক্লাসিক পিৎজা।")
                .enterPrice("450")
                .uploadImage("src/test/resources/test-images/pizza.jpg");

        addItem.clickSaveAndPublish();
        System.out.println("Created food item: " + itemName);
        Assert.assertTrue(addItem.isSuccessToastVisible(),
                "Expected the 'Food item added successfully' toast after a full-detail submit");
        Assert.assertFalse(addItem.isPanelVisible(), "Add Food Item panel should close back to the list after saving");
    }

    @Test
    public void addFoodItem_withOnlyRequiredFields_succeeds() {
        String itemName = "Grilled Chicken Burger";

        AddFoodItemPage addItem = openAddFoodItemPanel()
                .enterName(itemName)
                .selectCategory(null)
                .selectFoodType(null)
                .enterDescription("Grilled chicken fillet burger with lettuce and mayo.")
                .enterPrice("280")
                .uploadImage("src/test/resources/test-images/chicken-burger.jpg");

        addItem.clickSaveAndPublish();
        System.out.println("Created food item: " + itemName);
        Assert.assertTrue(addItem.isSuccessToastVisible(),
                "Expected the 'Food item added successfully' toast after a required-fields-only submit");
    }

    @Test
    public void addFoodItem_missingRequiredFields_showsValidationErrors() {
        AddFoodItemPage addItem = openAddFoodItemPanel();

        Assert.assertTrue(addItem.isPanelVisible(), "Add Food Item panel should be open");
        Assert.assertTrue(addItem.isFieldErrorVisible("Food item name is required."), "Name error should show");
        Assert.assertTrue(addItem.isFieldErrorVisible("Category is required."), "Category error should show");
        Assert.assertTrue(addItem.isFieldErrorVisible("Please select at least one food type."), "Food type error should show");
        Assert.assertTrue(addItem.isFieldErrorVisible("Description is required."), "Description error should show");
        Assert.assertTrue(addItem.isSaveAndPublishDisabled(),
                "Save & Publish should stay disabled while required fields are blank");
    }
}
