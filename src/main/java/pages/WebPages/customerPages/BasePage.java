package pages.WebPages.customerPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;

/** Shared helpers for every customer-facing page object. */
public abstract class BasePage {

    protected final Page page;

    protected BasePage(Page page) {
        this.page = page;
    }

    protected Locator locator(String selector) {
        return page.locator(selector);
    }

    protected void click(String selector) {
        page.locator(selector).click();
    }

    protected void type(String selector, String text) {
        Locator field = page.locator(selector);
        field.click();
        field.fill(text);
    }

    protected String textOf(String selector) {
        return page.locator(selector).innerText();
    }

    protected boolean isVisible(String selector) {
        return page.locator(selector).isVisible();
    }

    protected void waitVisible(String selector) {
        page.locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
    }

    protected void waitHidden(String selector) {
        page.locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.HIDDEN));
    }

    public String currentUrl() {
        return page.url();
    }

    /**
     * Clicks a locator whose own click handler may immediately remove it from the
     * DOM (an auto-closing modal, an auto-submitting form field) — this Angular
     * app does that in more than one place (OTP auto-verify on the 4th digit,
     * the location-picker modal auto-closing once a location is chosen). A plain
     * Locator.click() retries its actionability check against an element that
     * will never come back once removed, hanging for the full default timeout
     * even though the click already did what it was supposed to.
     *
     * Uses a short click timeout and swallows exactly that "gone before the
     * retry" outcome, verified by isGoneOrChanged returning true afterwards -
     * any other failure still propagates.
     */
    protected void clickAllowingSelfRemoval(Locator target, java.util.function.BooleanSupplier isGoneOrChanged) {
        try {
            target.click(new Locator.ClickOptions().setTimeout(3000));
        } catch (PlaywrightException e) {
            if (!isGoneOrChanged.getAsBoolean()) {
                throw e;
            }
        }
    }
}
