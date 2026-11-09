package pages.WebPages.adminPortalPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/** Shared helpers for every Hop On Admin Management ("admin-portal") page object. */
public abstract class AdminPortalBasePage {

    protected final Page page;

    protected AdminPortalBasePage(Page page) {
        this.page = page;
        utils.NetworkErrorCapture.attach(page, "ADMIN_PORTAL");
    }

    protected void type(String selector, String text) {
        Locator field = page.locator(selector);
        field.click();
        field.fill(text);
    }

    protected void waitVisible(String selector) {
        page.locator(selector).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    }
}
