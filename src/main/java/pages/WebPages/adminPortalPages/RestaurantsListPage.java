package pages.WebPages.adminPortalPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.net.URI;

/**
 * "Restaurants" > "All Restaurants" list on the Hop On Admin Management portal.
 */
public class RestaurantsListPage extends AdminPortalBasePage {

    private static final String RESTAURANTS_LINK = "a[href='/restaurants']";
    private static final String ANY_VIEW_LINK = "a[href^='/restaurant-details']";

    public RestaurantsListPage(Page page) {
        super(page);
        page.click(RESTAURANTS_LINK);
        page.waitForURL(url -> url.contains("/restaurants"));
    }

    /**
     * Opens a restaurant's Overview page by its backend UUID (the same id admin-portal-automation
     * reads off the /restaurant/edit/<id> URL after creating it - see
     * RestaurantApi.waitForRestaurantId()).
     *
     * No visible text is used. Every row's "..." action menu holds a "View" link whose href is
     * exactly /restaurant-details?restaurantId=<id> (CONFIRMED live 2026-11-09: it is in the DOM
     * for every row even while the menu is closed). So the row is found by that href.
     *
     * Clicking that link with a normal Playwright click timed out ("element is outside of the
     * viewport", CONFIRMED 2026-11-08): the menu is a floating overlay whose scroll context does
     * not match the row. A DOM click (element.click()) skips the viewport/hit-test check and
     * still fires the Angular routerLink, so it is a real in-app navigation. If the restaurant
     * is not on the first page of the list (page size 20, newest first), falls back to going
     * straight to the same URL on the current origin.
     */
    public RestaurantOverviewPage openRestaurantOverview(String restaurantId) {
        String viewHref = "/restaurant-details?restaurantId=" + restaurantId;
        Locator viewLink = page.locator("a[href='" + viewHref + "']");
        page.locator(ANY_VIEW_LINK).first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
        if (viewLink.count() > 0) {
            viewLink.first().evaluate("e => e.click()");
            page.waitForURL(url -> url.contains(restaurantId));
        } else {
            URI current = URI.create(page.url());
            page.navigate(current.getScheme() + "://" + current.getAuthority() + viewHref);
        }
        return new RestaurantOverviewPage(page);
    }
}
