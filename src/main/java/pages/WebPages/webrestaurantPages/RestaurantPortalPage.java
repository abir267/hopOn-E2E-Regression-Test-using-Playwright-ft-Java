package pages.WebPages.webrestaurantPages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Sidebar shell of the restaurant-admin ("web-restaurant") portal — CONFIRMED
 * against the live site (2026-09-19 probe, logged in as qaabir@gmail.com on
 * https://dev-foodadmin.hoponbd.com/dashboard, tenant "QA Burger Express [AA]"):
 *   - sidebar is a single "aside" element containing 4 nav links plus the
 *     restaurant-name/logo link (also pointing at /dashboard, which makes
 *     a plain text-based lookup of "Dashboard" ambiguous - scoped getByRole
 *     with exact=true avoids matching the logo).
 *   - Dashboard  -> /dashboard        (heading text is a greeting, no h1/h2
 *                   named "Dashboard" - see isDashboardPlaceholder() note)
 *   - Live Orders -> /live-orders     (h2 "Live Orders")
 *   - Menu Management -> /menu-management (h2 "Menu Management" + "Categories")
 *   - Discount & Promotions -> /promotion (h2 "Discounts & Promotions" + "Manage Discounts")
 *
 * CONFIRMED functional gap: unlike the other three tabs, Dashboard renders
 * generic ride-hailing placeholder content (metric cards labelled "Total
 * Riders" / "Completed Ride", charts titled "Weekly Ride Report" / "Weekly
 * Vehicle Comparison", even the literal word "TEST" in the subheading copy) -
 * none of it is food/restaurant data. isDashboardPlaceholder() below detects
 * that known placeholder text rather than any real dashboard widget, since
 * there is no real dashboard implementation to assert against yet.
 */
public class RestaurantPortalPage extends AdminBasePage {

    private static final String SIDEBAR = "aside";
    private static final String DASHBOARD_PLACEHOLDER_TEXT = "Weekly Ride Report";

    public RestaurantPortalPage(Page page) {
        super(page);
    }

    private Locator sidebarLink(String exactName) {
        return page.locator(SIDEBAR).getByRole(AriaRole.LINK,
                new Locator.GetByRoleOptions().setName(exactName).setExact(true));
    }

    public RestaurantPortalPage openDashboard() {
        sidebarLink("Dashboard").click();
        page.waitForURL("**/dashboard");
        return this;
    }

    public RestaurantPortalPage openLiveOrders() {
        sidebarLink("Live Orders").click();
        page.waitForURL("**/live-orders");
        return this;
    }

    public RestaurantPortalPage openMenuManagement() {
        sidebarLink("Menu Management").click();
        page.waitForURL("**/menu-management");
        return this;
    }

    public RestaurantPortalPage openDiscountAndPromotions() {
        sidebarLink("Discount & Promotions").click();
        page.waitForURL("**/promotion");
        return this;
    }

    /** Opens the "Create New Discount" panel via the "+" icon next to "Manage Discounts" (no text label). */
    public CreateDiscountPage openCreateDiscount() {
        page.locator("button:has(lucide-icon[name='plus'])").first().click();
        page.waitForTimeout(500);
        return new CreateDiscountPage(page);
    }

    public boolean isSidebarVisible() {
        return isVisible(SIDEBAR);
    }

    public boolean isHeadingVisible(String headingText) {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(headingText)).isVisible();
    }

    /** True while Dashboard still shows the generic ride-hailing placeholder (see class doc). */
    public boolean isDashboardPlaceholder() {
        return page.getByText(DASHBOARD_PLACEHOLDER_TEXT).isVisible();
    }
}
