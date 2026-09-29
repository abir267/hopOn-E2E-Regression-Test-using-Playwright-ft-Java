# Handoff: Hopon Food Portal — Playwright + Java Automation

This document is written to be pasted directly into Claude Code (or handed to
any engineer) as a full briefing to continue building this project. It covers
what exists, why it's structured this way, what's still a stub, and the exact
steps to make it real.

---

## 1. Goal

Build UI + API test automation for **`dev-food.hoponbd.com`**, a food-ordering
web portal (customers browse restaurants, order food, pay, track delivery).
Stack: **Playwright (Java bindings) + TestNG + Maven**.

## 2. Current state — read this first

The target site is a **JavaScript-rendered SPA**. A plain HTTP fetch of the
root URL returns only an empty HTML shell (`<title>Hopon Food Portal</title>`,
no DOM). This scaffold was built **without** being able to render the site in
a real browser, so:

- Every locator (`page.locator("[data-testid='...']")`) in
  `src/main/java/pages/WebPages/customerPages/*.java` is a **placeholder
  guess**, not something scraped from the live DOM.
- Every API endpoint in `src/main/java/foodAPIs/*` is an **unimplemented
  stub** (`throw new UnsupportedOperationException(...)`) — no real API
  contract (Swagger/Postman) was available.
- The overall architecture, page-object breakdown, test flow, and project
  layout are production-ready and should NOT need to be redesigned — only
  the locator values and API endpoint details need to become real.

**First and most important task for whoever picks this up:** open
`https://dev-food.hoponbd.com` in an actual browser, and either:
  - manually inspect elements in DevTools and replace locators one by one, or
  - run Playwright Codegen (see §5) to record real locators automatically.

## 3. Full project structure

```
hopon-food-automation/
├── DataSource/                       # test data as CSV (not Google Sheets)
│   ├── customers.csv                 # name, phone, area, default_payment_method
│   └── restaurants.csv               # restaurant_name, cuisine, area, sample_item, sample_item_price
├── DeviceProperties/                 # placeholder only, currently unused
│   └── README.md                     # explains why (pure desktop web automation, no device matrix yet)
├── Docker/
│   ├── Dockerfile                    # Maven build stage -> mcr.microsoft.com/playwright/java image
│   └── docker-compose.yml            # mounts ../target for reports/screenshots/traces
├── src/
│   ├── main/java/
│   │   ├── foodAPIs/                 # API-level test support (not UI)
│   │   │   ├── authModule/
│   │   │   │   └── AuthAPI.java      # STUB: requestOtp(), verifyOtpAndGetToken()
│   │   │   ├── orderModule/
│   │   │   │   └── OrderAPI.java     # STUB: listRestaurants(), placeOrder()
│   │   │   └── headerManager.java    # builds common headers (platform/device/auth token)
│   │   ├── pages/WebPages/customerPages/   # Page Object Model, one class per screen
│   │   │   ├── BasePage.java         # shared click()/type()/isVisible() helpers
│   │   │   ├── HomePage.java         # location picker, search, category chips, restaurant grid
│   │   │   ├── LoginPage.java        # phone + OTP flow
│   │   │   ├── RestaurantMenuPage.java  # menu items, add-to-cart, customization modal
│   │   │   ├── CartPage.java         # line items, promo code, grand total, checkout CTA
│   │   │   └── CheckoutPage.java     # address, payment method, order notes, place order
│   │   └── utils/
│   │       ├── ConfigReader.java     # loads TestData.properties from project root
│   │       ├── TestDataGenerator.java# e.g. randomBdPhoneNumber() -> "017XXXXXXXX"
│   │       ├── RetryAnalyzer.java    # TestNG IRetryAnalyzer, 2 retries on failure
│   │       └── GSheetUpdate.java     # STUB, intentionally unimplemented (see file comment)
│   └── test/java/
│       ├── WebFunctionalTests/
│       │   ├── AuthTests/
│       │   │   └── LoginTest.java              # valid OTP login, invalid OTP error
│       │   └── CustomerJourney/
│       │       └── SearchAndOrderTest.java     # full search -> add to cart -> checkout -> place order
│       ├── APITests/
│       │   └── AuthApiTest.java      # placeholder, @Test(enabled=false) until AuthAPI is wired up
│       └── init/
│           ├── BaseRunner.java       # TestNG @BeforeSuite/@BeforeMethod/@AfterMethod/@AfterSuite —
│           │                         #   owns Playwright/Browser/BrowserContext/Page lifecycle,
│           │                         #   captures trace+screenshot only on test failure
│           └── TestRunner.java       # programmatic suite runner: java init.TestRunner -Dsuite=...
├── test-scripts/
│   ├── startup-local.sh              # mvn test locally, optional suite-file arg
│   ├── startup-jenkins.sh            # forces headless, prints report paths, exits non-zero on failure
│   └── startup-docker.sh             # docker compose up --build in Docker/
├── test-suites/
│   ├── testng.xml                    # full suite (Authentication + OrderingJourney, parallel classes)
│   └── smoke.xml                     # fast subset: 1 login test + 1 empty-cart test
├── TestData.properties               # gitignored — real values (base URL, headless, test phone/OTP)
├── TestData.properties.example       # committed template, copy this to create TestData.properties
├── .gitignore                        # ignores TestData.properties, target/, *.class, IDE files
├── README.md                         # human-facing overview (feature map, architecture rationale)
└── pom.xml                           # Maven deps: playwright, testng, extentreports, owner;
                                       #   surefire points at test-suites/testng.xml
```

## 4. Key design decisions (don't undo these without reason)

- **Page Object Model**, one class per screen, all under `pages.WebPages.customerPages`.
  Leaves room to add `pages.WebPages.adminPages` or a future `pages.MobilePages`
  without restructuring.
- **`init.BaseRunner`**: one `Browser` per test **suite**, a fresh
  `BrowserContext` per test **method**. This isolation matters because cart
  contents, login session, and saved addresses are client-side state that
  must not leak between tests.
- **Trace + screenshot captured only on failure** (`BaseRunner.tearDownContext`)
  — keeps `target/` from filling up with artifacts from passing runs.
- **`TestData.properties` lives at repo root and is gitignored** — it holds
  per-environment values (URL, headless flag, test credentials) that differ
  machine-to-machine and must never be committed. `ConfigReader` reads it via
  `Paths.get("TestData.properties")` (relative to wherever Maven/the JVM is run
  from — i.e. the repo root), not via classpath, and throws a clear error if
  it's missing, pointing at the `.example` file.
- **`foodAPIs` is separate from `pages`** — API-level tests/helpers
  (fast, no browser) are kept independent of UI page objects, mirroring how
  you'd want to reuse `AuthAPI.verifyOtpAndGetToken()` as fast test setup for
  a UI test that needs to start already-logged-in, without duplicating the
  OTP UI flow every time.
- **CSV test data over Google Sheets integration** — simpler, no credentials
  file, fine for the current data volume. `GSheetUpdate` stub exists only in
  case a shared reporting sheet becomes an actual requirement later.

## 5. Exact steps to make this real

### Step 1 — Get real locators
```bash
# Requires Java 17+, Maven installed
mvn clean install
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install"

# Opens a real browser against the live site and records your clicks as code
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="codegen https://dev-food.hoponbd.com"
```
Walk through: set delivery location → search for food → open a restaurant →
add an item → open cart → apply promo → checkout → select address/payment →
place order → (if applicable) log in via phone/OTP. Copy the generated
locators into the matching methods in each page object file listed in §3.

### Step 2 — Confirm the login mechanism
`LoginPage.java` currently assumes phone number + OTP (common for BD apps).
Verify against the real site:
- If it's actually email/password, rewrite `LoginPage.java` accordingly
  (remove the OTP-specific locators/methods, add email/password fields).
- If it is phone+OTP, find out how OTP works in the dev environment — most
  dev/sandbox backends accept one fixed OTP (currently assumed to be `1122`,
  set in `TestData.properties` as `test.user.otp`) for any test phone number.
  Confirm the real value and update it there.

### Step 3 — Confirm and wire the API layer
Get a Swagger/OpenAPI spec or Postman collection for the backend. Then:
- Implement `AuthAPI.requestOtp()` / `AuthAPI.verifyOtpAndGetToken()` in
  `src/main/java/foodAPIs/authModule/AuthAPI.java` using Playwright's
  `APIRequestContext` (already a project dependency, no new library needed).
- Implement `OrderAPI.listRestaurants()` / `OrderAPI.placeOrder()` similarly.
- Update `headerManager.java` if the real API needs different header names.
- Enable and flesh out `src/test/java/APITests/AuthApiTest.java`
  (currently `@Test(enabled = false)`).

### Step 4 — Run it
```bash
cp TestData.properties.example TestData.properties   # then edit values
./test-scripts/startup-local.sh                      # full suite
./test-scripts/startup-local.sh test-suites/smoke.xml  # fast subset
```
Iterate: set `headless=false` in `TestData.properties` to watch the browser
while locators are still being fixed — much faster feedback than reading
headless failure stack traces.

### Step 5 — Extend coverage
- Add `OrderTrackingPage` / `OrderHistoryPage` once those screens are confirmed
  to exist (README §4 flags this as not-yet-modeled).
- Parameterize `SearchAndOrderTest` with a TestNG `@DataProvider` reading
  `DataSource/restaurants.csv`, instead of the hardcoded "Best Burger House" /
  "Classic Beef Burger" values currently in the test.
- Similarly, a `@DataProvider` over `DataSource/customers.csv` for
  multi-user login/order variants.

### Step 6 — CI
`test-scripts/startup-jenkins.sh` is ready to be called from a Jenkins
pipeline stage — it forces headless mode and exits non-zero on any failure.
`Docker/Dockerfile` + `docker-compose.yml` let the whole suite run in a
container without needing Java/Maven/browsers installed on the host —
useful as a Jenkins agent step or for guaranteeing environment parity.

## 6. What NOT to change without a reason

- Don't flatten `pages.WebPages.customerPages` back to a flat `pages` package —
  it was nested deliberately for future `adminPages`/`vendorPages` siblings.
- Don't move `TestData.properties` into `src/.../resources` or commit it —
  it's meant to stay a root-level, gitignored, per-machine file.
- Don't remove the fresh-`BrowserContext`-per-test pattern in `BaseRunner` —
  required for cart/session test isolation.

## 7. Open questions for whoever has access to the real site/backend

1. Is login phone+OTP, email+password, or social login?
2. What's the dev-environment OTP behavior (fixed code, or a test SMS API)?
3. Is there a Swagger/OpenAPI/Postman spec for the backend API?
4. Do payment methods include real gateways in dev (bKash/Nagad/card), or is
   dev limited to Cash on Delivery / a mock payment step?
5. Does the site have an order-tracking screen, and is it real-time
   (websocket/polling) or a static status page?
