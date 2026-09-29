package pages.WebPages.adminPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * "Menu Management" tab (/menu-management) — CONFIRMED against the live site
 * (2026-09-23 probe, "Crispy Burger" / "Crispy King" on QA Burger Express [AA]):
 *   - "Categories & Items" sub-tab (default) has a search box, placeholder
 *     "Search items in ..." - typing an item name filters the results list
 *     down to matching cards, which is how searchAndOpen()/toggleAvailability()
 *     below scope to a single item without needing a stable per-item id.
 *   - each item card has an "Available"/"Not Available" switch - NOT a real
 *     <button> or <input>, just a styled clickable <div> (class includes
 *     "cursor-pointer") immediately before a <span> reading "Available" or
 *     "Not Available". Toggling it flips both the label and the item's
 *     visibility/orderability on the customer app ("Changes reflect instantly
 *     on the Hop On app" per the page's own subtitle).
 */
public class MenuManagementPage extends AdminBasePage {

    private static final String SEARCH_INPUT = "input[placeholder='Search items in ...']";
    private static final String AVAILABILITY_LABEL = "span:text-is('Available'), span:text-is('Not Available')";

    public MenuManagementPage(Page page) {
        super(page);
    }

    /** Filters the item list down to (ideally) a single matching card. */
    public MenuManagementPage searchItem(String itemName) {
        page.locator(SEARCH_INPUT).fill(itemName);
        page.waitForTimeout(800);
        return this;
    }

    private Locator availabilitySwitch() {
        return page.locator(AVAILABILITY_LABEL).first().locator("xpath=preceding-sibling::div[1]");
    }

    /** Reads the current item's availability label - call searchItem() first to scope to one item. */
    public boolean isAvailable() {
        return page.locator(AVAILABILITY_LABEL).first().innerText().trim().equals("Available");
    }

    /**
     * Flips the currently-shown item's availability switch - call searchItem()
     * first. Polls the label until it actually changes instead of a fixed
     * sleep - CONFIRMED this update can take longer than a single short wait
     * to settle, and reading the label too early silently re-reports the
     * pre-click value.
     */
    public MenuManagementPage toggleAvailability() {
        String before = page.locator(AVAILABILITY_LABEL).first().innerText().trim();
        availabilitySwitch().click();
        for (int i = 0; i < 15; i++) {
            page.waitForTimeout(400);
            String now = page.locator(AVAILABILITY_LABEL).first().innerText().trim();
            if (!now.equals(before)) {
                return this;
            }
        }
        throw new IllegalStateException("Availability label never changed from '" + before + "' after clicking the toggle");
    }

    /** Sets the currently-shown item's availability to exactly this, only clicking if it isn't already. */
    public MenuManagementPage setAvailability(boolean available) {
        if (isAvailable() != available) {
            toggleAvailability();
        }
        return this;
    }
}
