package pages.WebPages.webrestaurantPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * "Create New Discount" panel on the Discount &amp; Promotions tab (/promotion,
 * opened via the "+" icon button next to "Manage Discounts" - no text label,
 * only a lucide "plus" icon) - CONFIRMED against the live site (2026-10-24
 * probe, QA Burger Express [AA]):
 *   - Discount Type is a two-way toggle ("Percentage (%)" / "Fixed Amount"),
 *     not a select - Fixed Amount is disabled unless scope is "Specific
 *     Items", so Percentage is the only type usable without also wiring up
 *     item selection.
 *   - "Your Restaurant" is a disabled/readonly input, pre-filled - nothing to
 *     select there.
 *   - Start/End Date &amp; Time are PrimeNG p-datepicker components
 *     (formcontrolname="startDate"/"endDate"), not plain inputs - clicking
 *     the inner input opens a calendar overlay; day cells are
 *     span[data-pc-section='day'].
 *   - Scope defaults to all active menu items ("All active menu items will
 *     also receive this discount automatically"), so no item picker is
 *     required for a basic discount.
 *   - On success a toast reading "Discount Created" appears. CONFIRMED the
 *     backend rejects a new "all active items" discount whose date range
 *     overlaps an existing one for the same restaurant ("Creation Failed -
 *     Active campaign conflict found..."), so callers should pick a date
 *     range unlikely to collide with discounts earlier test runs left behind
 *     rather than a fixed day each time.
 */
public class CreateDiscountPage extends AdminBasePage {

    private static final String NAME_EN = "input[formcontrolname='nameEn']";
    private static final String DESCRIPTION_EN = "textarea[formcontrolname='descriptionEn']";
    private static final String DISCOUNT_VALUE = "input[formcontrolname='discountValue']";
    private static final String START_DATE = "p-datepicker[formcontrolname='startDate']";
    private static final String END_DATE = "p-datepicker[formcontrolname='endDate']";
    private static final String DAY_CELL = "span[data-pc-section='day']";
    private static final String NEXT_MONTH_BUTTON = ".p-datepicker-next-button";
    private static final String SUCCESS_TOAST = "Discount Created";

    public CreateDiscountPage(Page page) {
        super(page);
    }

    public CreateDiscountPage enterName(String nameEn) {
        type(NAME_EN, nameEn);
        return this;
    }

    public CreateDiscountPage enterDescription(String descriptionEn) {
        type(DESCRIPTION_EN, descriptionEn);
        return this;
    }

    public CreateDiscountPage enterDiscountValue(String percentage) {
        type(DISCOUNT_VALUE, percentage);
        return this;
    }

    /** Opens the given date picker, advances monthsAhead months, and picks dayOfMonth. */
    private void pickDate(String datepickerSelector, int monthsAhead, int dayOfMonth) {
        page.locator(datepickerSelector + " input").click();
        page.waitForTimeout(400);
        for (int i = 0; i < monthsAhead; i++) {
            page.locator(NEXT_MONTH_BUTTON).first().click();
            page.waitForTimeout(250);
        }
        page.locator(DAY_CELL).filter(new Locator.FilterOptions()
                .setHasText(java.util.regex.Pattern.compile("^" + dayOfMonth + "$"))).first().click();
        page.waitForTimeout(300);
    }

    public CreateDiscountPage selectStartDate(int monthsAhead, int dayOfMonth) {
        pickDate(START_DATE, monthsAhead, dayOfMonth);
        return this;
    }

    public CreateDiscountPage selectEndDate(int monthsAhead, int dayOfMonth) {
        pickDate(END_DATE, monthsAhead, dayOfMonth);
        return this;
    }

    public boolean isPanelVisible() {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Create New Discount")).isVisible();
    }

    public boolean isSuccessToastVisible() {
        return page.getByText(SUCCESS_TOAST).isVisible();
    }

    public RestaurantPortalPage clickCreateDiscount() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create Discount")).click();
        page.waitForTimeout(1500);
        return new RestaurantPortalPage(page);
    }
}
