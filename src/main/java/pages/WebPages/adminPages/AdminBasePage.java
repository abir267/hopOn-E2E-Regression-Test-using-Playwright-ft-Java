package pages.WebPages.adminPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/** Shared helpers for every restaurant-admin (web-restaurant) page object. */
public abstract class AdminBasePage {

    protected final Page page;

    protected AdminBasePage(Page page) {
        this.page = page;
    }

    protected Locator locator(String selector) {
        return page.locator(selector);
    }

    protected void type(String selector, String text) {
        Locator field = page.locator(selector);
        field.click();
        field.fill(text);
    }

    protected boolean isVisible(String selector) {
        return page.locator(selector).isVisible();
    }

    protected void waitVisible(String selector) {
        page.locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
    }

    public String currentUrl() {
        return page.url();
    }
}
