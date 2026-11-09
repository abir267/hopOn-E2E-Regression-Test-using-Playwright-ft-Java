package pages.WebPages.adminPortalPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * Restaurant Overview page (Restaurants > View). All locators are structural - Angular component
 * tags, formcontrolname, input type and icon names - none depend on visible text, so they survive
 * wording/translation changes. CONFIRMED live (2026-11-09) real DOM:
 *   - page host:   <hopon-food-admin-restaurant-details>
 *   - header action button is the SAME slot for both states, told apart by its icon:
 *       not blocked -> button containing <lucide-icon name="ban">          ("Suspend Account")
 *       blocked     -> button containing <lucide-icon name="check-circle">  ("Unblock Account")
 *     (exactly one of each exists on the page, never both)
 *   - modal host:  <hopon-food-admin-restaurant-block-modal> with a single <form>:
 *       Block Reason  input[formcontrolname='blockReason']
 *       Block Status  input[type=checkbox][formcontrolname='status'], visually hidden (sr-only)
 *                     inside its own <label>; the visible switch is a sibling div in that label,
 *                     so the label is the click target (native label/control association)
 *       Confirm       form button[type='submit'] (disabled until both fields are valid -
 *                     Validators on blockReason + requiredTrue on status)
 *   - The status pill ("Active"/"Inactive"/...) is NOT a reliable block indicator: it read
 *     "Inactive" for both a blocked and an unblocked restaurant. Use isSuspended() instead.
 */
public class RestaurantOverviewPage extends AdminPortalBasePage {

    private static final String PAGE = "hopon-food-admin-restaurant-details";
    private static final String SUSPEND_BUTTON = PAGE + " button:has(lucide-icon[name='ban'])";
    private static final String UNBLOCK_BUTTON = PAGE + " button:has(lucide-icon[name='check-circle'])";

    private static final String MODAL = "hopon-food-admin-restaurant-block-modal";
    private static final String BLOCK_REASON_INPUT = MODAL + " input[formcontrolname='blockReason']";
    private static final String BLOCK_STATUS_CHECKBOX = MODAL + " input[formcontrolname='status']";
    private static final String BLOCK_STATUS_TOGGLE_LABEL = MODAL + " label:has(input[formcontrolname='status'])";
    private static final String CONFIRM_BLOCK_BUTTON = MODAL + " form button[type='submit']";

    public RestaurantOverviewPage(Page page) {
        super(page);
    }

    /** Waits for the header action button (either state) so the page is really rendered. */
    private void waitForHeaderActions() {
        page.locator(SUSPEND_BUTTON + ", " + UNBLOCK_BUTTON).first().waitFor();
    }

    /** True when the restaurant is blocked (header shows the Unblock action instead of Suspend). */
    public boolean isSuspended() {
        waitForHeaderActions();
        return page.locator(UNBLOCK_BUTTON).count() > 0;
    }

    public RestaurantOverviewPage openSuspendModal() {
        page.locator(SUSPEND_BUTTON).click();
        waitVisible(MODAL + " form");
        return this;
    }

    public RestaurantOverviewPage fillBlockReason(String reason) {
        Locator input = page.locator(BLOCK_REASON_INPUT);
        input.click();
        input.fill(reason);
        return this;
    }

    /** Required field (Validators.requiredTrue) - only clicks if not already checked. */
    public RestaurantOverviewPage setBlockStatusOn() {
        Locator checkbox = page.locator(BLOCK_STATUS_CHECKBOX);
        if (!checkbox.isChecked()) {
            page.locator(BLOCK_STATUS_TOGGLE_LABEL).click();
        }
        return this;
    }

    /** Clicks Confirm Block and waits for the modal form to go away (HIDDEN also covers "removed from DOM"). */
    public void confirmBlock() {
        page.locator(CONFIRM_BLOCK_BUTTON).click();
        page.locator(MODAL + " form").waitFor(new Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));
    }

    /** Full suspend flow: open the modal, fill the reason, set Block Status on, confirm. */
    public void suspendAccount(String reason) {
        openSuspendModal();
        fillBlockReason(reason);
        setBlockStatusOn();
        confirmBlock();
    }
}
