# Hopon Food — Test Automation

UI + API test automation across **three** Hopon Food systems, built with
**Playwright (Java) + TestNG + Maven**:

| System | URL | What's automated |
|---|---|---|
| Customer web app | `dev-food.hoponbd.com` | Login, browse, add to cart, checkout, place order |
| Restaurant admin ("web-restaurant") | `dev-foodadmin.hoponbd.com` | Login, sidebar nav, Live Orders accept/ready-for-pickup |
| Driver app backend | `dev-api.hoponbd.com` (direct API, no UI) | Accept → Arrived → PickedUp → Delivered (+ photo proof) |

The three systems are exercised **together** in the end-to-end lifecycle
tests below — a real order flows customer → restaurant → driver in one run.

## 1. Prerequisites

- **Java 17+**
- **Maven 3.9+**
- Playwright's Chromium browser (installed once, see Setup below — not a
  separate manual download)
- Real login credentials for the restaurant-admin portal and a driver-app
  bearer token (see `TestData.properties` below) — these are dev-environment
  test accounts, not invented

No Docker/Node/Python required for the tests themselves (`Docker/` exists
only for an optional containerized run — see §7).

## 2. Setup (one time)

```bash
cd hopon-food-automation
cp TestData.properties.example TestData.properties
```

Edit `TestData.properties` (gitignored — never commit real values) and fill in:

```properties
# customer web app
base.url=https://dev-food.hoponbd.com

# restaurant admin portal
restaurant.admin.url=https://dev-foodadmin.hoponbd.com
restaurant.admin.email=<real email>
restaurant.admin.password=<real password>

# driver-app API (bearer token from a real logged-in driver session)
driver.api.base.url=http://dev-api.hoponbd.com
driver.auth.token=<real JWT>
driver.device.id=<any string>
```

Then build once and install the Playwright browser binary:

```bash
mvn clean install
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

Set `headless=false` in `TestData.properties` to watch the browser while a
test runs — much easier to follow than reading a stack trace after the fact.

## 3. Running a test — the command

Every test suite here runs the same way: build once, then run via
`init.TestRunner` naming the suite XML. **This is the one command to
remember**, only the `-Dsuite=...` value changes:

```bash
mvn "-Dsuite=test-suites/<suite-file>.xml" "-Dexec.mainClass=init.TestRunner" "-Dexec.classpathScope=test" exec:java
```

> **PowerShell users:** quote every `-D` argument exactly as shown above (all
> one line, no line breaks). An unquoted `=` or a stray space around the `:`
> in `exec:java` makes Maven misread it as a lifecycle phase and fail with
> `LifecyclePhaseNotFoundException`.

### Available suites

| Suite file | What it does |
|---|---|
| `test-suites/orderLifecycle.xml` | **Full 3-system flow.** Customer places an order on QA Burger Express → restaurant accepts *and* marks it ready → driver runs Accepted → Arrived → PickedUp → Delivered (with photo proof). |
| `test-suites/orderLifecycleDriverInLoop.xml` | Same full flow, but realistic interleaving: driver accepts and arrives **while the restaurant is still Preparing**, restaurant marks ready *after*, then driver completes pickup + delivery. |
| `test-suites/validationFlows.xml` | **Negative-path / bug-report suite**, 10 tests — see §4 below for the full list of what each one checks and confirms. |
| `test-suites/webRestaurant.xml` | Restaurant-admin sidebar smoke test: logs in, walks all 4 tabs (Dashboard/Live Orders/Menu Management/Discount & Promotions). Documents that Dashboard is still placeholder content. |
| `test-suites/smoke.xml` | Fast customer-only subset: 1 login test + 1 empty-cart check. |
| `test-suites/testng.xml` | Full customer-journey suite (Authentication + OrderingJourney). |

Example — run the full lifecycle test and watch it print every step:

```bash
mvn "-Dsuite=test-suites/orderLifecycle.xml" "-Dexec.mainClass=init.TestRunner" "-Dexec.classpathScope=test" exec:java
```

Expected terminal output (one real order, created live against the dev
backend every run):

```
[CUSTOMER]   Logged in as 01123xxxxxx
[CUSTOMER]   Added to cart: Crispy Burger (Regular)
[CUSTOMER]   Order placed: #01B-xxx
[RESTAURANT] Order #01B-xxx accepted and marked Ready for Pickup (uuid=..., pin=....)
[DRIVER]     Order #01B-xxx -> Accepted
[DRIVER]     Order #01B-xxx -> Arrived at restaurant
[DRIVER]     Order #01B-xxx -> PickedUp, heading to customer
[DRIVER]     Order #01B-xxx -> Delivered (photo proof uploaded)
[RESULT]     SUCCESS: full lifecycle completed - customer -> restaurant -> driver (Accepted -> Arrived -> PickedUp -> Delivered).

===============================================
HoponOrderLifecycleSuite
Total tests run: 1, Passes: 1, Failures: 0, Skips: 0
===============================================
```

If you see a `Retrying '...' (attempt N/4)` line, that's expected — see the
flakiness note in §7, not a real failure as long as it ends in `Passes: 1`.

### Alternative ways to run (same tests, different entrypoints)

Whole customer suite via plain `mvn test` (uses the surefire-configured
`test-suites/testng.xml`):
```bash
mvn test
```

Via helper scripts (same commands, with property-file checks and headless
override baked in):
```bash
./test-scripts/startup-local.sh                        # full suite
./test-scripts/startup-local.sh test-suites/smoke.xml   # smoke subset
./test-scripts/startup-jenkins.sh                       # CI mode, forces headless
```

### Reading the terminal output

Every line is prefixed with the real wall-clock time, aorder · status · "title"icon and (when the
terminal supports it) colour. `utils/ConsoleLogListener` does this for every run
(`mvn test`, `-Dtest=...`, `init.TestRunner`) — no per-test code.

```
[08:32:27 AM] > [TEST] START  OrderLifecycleTest.customerToRestaurantToDriverAcceptance
[08:32:45 AM] + [CUSTOMER] Logged in as 01123503576
[08:32:55 AM] + [CUSTOMER] Order placed: #025-15C
[08:33:23 AM] * [MQTT] <- [CUSTOMER] 025-15C | Preparing | "Preparing your food"
[08:33:24 AM] * [MQTT] <- [RESTAURANT] 025-15C | Preparing | "Preparing Order" / "Customer is waiting!"
[08:33:30 AM] + [DRIVER] Order #025-15C -> Delivered (photo proof uploaded)
[08:33:31 AM] + [RESULT] SUCCESS: full lifecycle completed ...
[08:33:31 AM] + [TEST] PASSED OrderLifecycleTest.customerToRestaurantToDriverAcceptance (64.0s)
```

| Prefix | Meaning |
|---|---|
| `[TEST] START / PASSED / FAILED / SKIPPED` | running test name (`Class.method`) and duration |
| `[CUSTOMER]` `[RESTAURANT]` `[DRIVER]` `[RESULT]` | test steps (green) |
| `[LOGIN]`, `Retrying ...` | slow login / retry notice (yellow) |
| `[FAILED]` | failure with the exception (red) |
| `[MQTT] <- [ROLE] order - status - "title"` | real MQTT push the customer/restaurant web app received (magenta); restaurant lines also show the message |

**MQTT lines** come from `utils/MqttConsoleCapture`, hooked into the page-object base
classes: it decodes the PUBLISH packets on each page's MQTT WebSocket, so what prints
is the payload the app really received. The driver is API-only, so it has no MQTT lines.
In the end-to-end tests the customer and restaurant pages stay open until the run ends so
they can receive pushes.

Symbols fall back to ASCII (`>` `+` `x` `!` `*`, `<-`, `|`) when the console is not UTF-8;
with UTF-8 (`chcp 65001`) you get `▶ ✔ ✖ ⚠ ›`, `←`, `·`. Options (add to any command):

| Flag | Effect |
|---|---|
| `-Dmqtt.full=true` | also print the full pretty JSON under each MQTT line |
| `-Dno.color=true` | plain text, no ANSI colour (timestamps stay) |
| `-Dno.unicode=true` | force ASCII symbols |

## 4. Validation flows — the bug-report suite

`test-suites/validationFlows.xml` (`WebFunctionalTests/ValidationFlows/`) is a
separate suite of 10 negative-path tests, each built by reproducing a real
reported issue live against the dev backend first, then locking the actual
observed behavior into an assertion. Some assert **correct** behavior (a
regression guard); a few assert the **current confirmed bug** on purpose —
those are *supposed* to show as failures until the underlying product bug is
fixed. Screenshot evidence for every one of them is saved to
`target/bug-evidence/` on every run (see §6).

```bash
mvn "-Dsuite=test-suites/validationFlows.xml" "-Dexec.mainClass=init.TestRunner" "-Dexec.classpathScope=test" exec:java
```

| Test | Checks | Expected result |
|---|---|---|
| `GpsOutsideServiceAreaTest` | Customer with GPS far outside Dhaka (Cox's Bazar) tries to order | ✅ Pass — correctly blocked with "Restaurant is unavailable" |
| `RestaurantNotAcceptingOrdersTest` | Restaurant toggled "Not Accepting Orders" *before* the item is added, then checkout attempted | ❌ Fails on purpose — **confirmed bug**: order still places, zero warning shown |
| `MenuItemNotAvailableTest` | Menu item toggled "Not Available" *before* being added, then checkout attempted | ❌ Fails on purpose — **confirmed bug**: order still places, zero warning shown |
| `NoValidationMessageOnCheckoutTest` | Same as above but item added **first**, restaurant goes offline **after** (exact reported repro order) | ❌ Fails on purpose — same confirmed bug, different ordering |
| `RestaurantCancelsOrderTest` | Restaurant (admin) cancels an order while the customer's tracking page is open and never reloaded | ✅ Pass — cancellation reaches the customer live (~8s, polling), with a clear banner; also documents a real minor bug (stale "Cancel Order" button stays visible on an already-cancelled order) |
| `CancelOrderModalBrokenTest` | Customer clicks their own "Cancel Order" button on the tracking page | ❌ Fails on purpose — **confirmed bug**: nothing happens at all; root cause captured from the browser console (a missing Lucide "Cancel" icon crashes the confirmation toast) |
| `CartBadgeOnLocationChangeTest` | Customer changes delivery location (Gulshan → real Uttara coordinates) with an item already in cart | ✅ Pass — basket stays correctly anchored/populated, a clear "outside delivery zone" warning appears, checkout disables; did **not** reproduce the reported badge-misplacement for this transition |
| `RestaurantExtendPrepTimeTest` | Restaurant clicks "Need More Time" (+20 min) on a Preparing order | ✅ Pass — customer's delivery-time window updates accordingly (no separate "delayed" banner, just the shifted ETA) |
| `DriverCancelBeforeAcceptTest` | Driver (not yet assigned) tries to cancel an order | ✅ Pass — correctly rejected, 400 "Invalid user" |
| `DriverCancelAfterAcceptTest` / `DriverCancelAfterArrivedTest` | Driver cancels after Accepted / after Arrived | ✅ Pass — both correctly allowed, driver unassigned, order returns to the pool |

Four of these tests (`RestaurantNotAcceptingOrdersTest`, `MenuItemNotAvailableTest`,
`NoValidationMessageOnCheckoutTest`, `CancelOrderModalBrokenTest`) are
**designed to fail** — that failure *is* the bug report. Don't "fix" them by
loosening the assertion; fix the product bug, then update the assertion to
match the corrected behavior.

## 5. What's real vs. still a stub

Not everything in this repo has been confirmed against the live backend the
same way. Know which is which before relying on it:

**Confirmed against real traffic / real UI (safe to build on):**
- Customer login (phone+OTP), search→cart→checkout→place order (`pages/WebPages/customerPages/*`)
- Restaurant-admin login, sidebar nav, Live Orders accept/ready-for-pickup flow, pickup PIN (`pages/WebPages/webrestaurantPages/*`)
- `DriverAPI` — every status transition (Accepted/Arrived/PickedUp/Delivered), the photo-proof upload endpoint, and the delivery PIN requirement were each confirmed via a real device network capture or a real API call, documented inline in `DriverAPI`'s class-level Javadoc

**Still stubs — guessed, unverified, throw `UnsupportedOperationException`:**
- `foodAPIs/authModule/AuthAPI.java`, `foodAPIs/orderModule/OrderAPI.java` — no confirmed backend contract yet (see `HANDOFF.md`)
- `utils/GSheetUpdate.java` — intentionally unimplemented, kept only in case a shared reporting sheet becomes a real requirement

**`utils/MqttListener.java` — CONFIRMED (2026-09-30):** real-time order-status push
listener for web (customer + restaurant-admin), decompiled straight from
dev-food.hoponbd.com's own `mqtt-message.service.ts` via DevTools Sources (TS source
maps were available). Broker `wss://dev-mqtt.hoponbd.com/mqtt`, MQTT v5 over WebSocket
(HiveMQ client, not Paho — Paho's v3 module doesn't speak v5/WebSocket). Auth is fully
session-derived: `MqttListener.connectFromWebSession(page)` reads the topic/username/
token straight off the Playwright page's cookies (`_ssl_mt`/`_ssl_mu`/`_ssl_taf`) and
localStorage (`_ssl_mcid`) after login — same as the real app, no static config to fill
in. One gap: only the customer-side topic prefix (`rider/{userId}`) is confirmed;
restaurant-admin's topic prefix hasn't been captured yet — grab its `_ssl_mt` cookie the
same way before relying on it there.

**Known UI gap (not a bug in the tests):** the restaurant-admin Dashboard tab
renders generic ride-hailing placeholder content, not real restaurant data —
`WebRestaurantNavigationTest` documents this rather than treating it as a
failure.

## 6. Reports & artifacts

- Surefire XML/HTML: `target/surefire-reports`
- Extent HTML report: `target/ExtentReport/index.html`
- Failure-only screenshots + Playwright traces: `target/test-artifacts`
- **Bug-evidence screenshots** (every `ValidationFlows` test, every run):
  `target/bug-evidence/issueN_*.png` — proof screenshots for each reported
  issue, overwritten fresh each run so they always reflect the current state

Open a trace with:
```bash
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="show-trace target/test-artifacts/<name>_trace.zip"
```

## 7. Known flakiness (and why it self-heals)

`DataSource/riderlist.csv` only has 350 test phone numbers, reused across
every run. Under heavy same-session usage the dev backend's OTP step can
occasionally time out (rate-limiting, or just a slow render of the OTP
boxes) — CONFIRMED the login mechanics themselves are fine (a fresh manual
run completes in well under half the default timeout), so this is transient,
not a real bug. Every `@Test` in `EndToEnd` / `CustomerJourney` /
`ValidationFlows` uses `utils.RetryAnalyzer` (4 retries, a fresh random
phone each attempt), so this self-heals without you re-running anything — a
`Retrying '...'` line in the output is normal, not a failure.

## 8. Recording real locators (Playwright Codegen)

For any screen not yet covered:
```bash
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="codegen https://dev-food.hoponbd.com"
```
Copy the generated locators into the matching page-object methods.

## 9. Running via Docker (optional, no local Java/Maven/browsers needed)

```bash
./test-scripts/startup-docker.sh
# or directly:
cd Docker && docker compose up --build
```

## 10. Project layout

```
hopon-food-automation/
├── DataSource/                            # customers.csv, restaurants.csv, riderlist.csv
├── DeviceProperties/                      # placeholder, unused (desktop web only)
├── Docker/                                # Dockerfile + docker-compose.yml
├── src/main/java/
│   ├── foodAPIs/
│   │   ├── authModule/AuthAPI.java        # STUB - no confirmed contract yet
│   │   ├── orderModule/OrderAPI.java      # STUB - no confirmed contract yet
│   │   ├── driverModule/
│   │   │   ├── DriverAPI.java             # CONFIRMED - full driver order-status lifecycle + photo proof
│   │   │   └── PlaceholderProofPhotos.java# tiny embedded images for automated "photo proof"
│   │   └── headerManager.java             # shared header builders (anon/auth/driver)
│   ├── pages/WebPages/
│   │   ├── customerPages/                 # Page Object Model - customer web app (CONFIRMED)
│   │   └── webrestaurantPages/                    # Page Object Model - restaurant admin (CONFIRMED)
│   │       ├── AdminBasePage.java
│   │       ├── WebRestaurantLogin.java
│   │       ├── RestaurantPortalPage.java     # sidebar nav
│   │       ├── LiveOrdersPage.java        # New/Preparing/Ready tabs, accept, ready, extend prep, cancel, pickup PIN
│   │       └── MenuManagementPage.java    # item availability (Available/Not Available) toggle
│   └── utils/                             # ConfigReader, TestDataGenerator, RetryAnalyzer, ConsoleLogListener, MqttConsoleCapture, GSheetUpdate (STUB), MqttListener (CONFIRMED)
├── src/test/java/
│   ├── WebFunctionalTests/
│   │   ├── AuthTests/                     # customer login
│   │   ├── CustomerJourney/               # customer search -> order
│   │   ├── AdminPortal/                   # restaurant-admin sidebar smoke test
│   │   ├── EndToEnd/                      # full 3-system lifecycle tests (see §3)
│   │   └── ValidationFlows/               # negative-path / bug-report suite, 10 tests (see §4)
│   ├── APITests/
│   │   ├── AuthApiTest.java               # disabled until AuthAPI is wired up
│   │   └── DriverCalling.java             # standalone script: call DriverAPI directly, no TestNG
│   └── init/                              # BaseRunner / AdminBaseRunner (Playwright lifecycle), TestRunner
├── test-scripts/                          # startup-local.sh, startup-jenkins.sh, startup-docker.sh
├── test-suites/                           # testng, smoke, webRestaurant, orderLifecycle(+DriverInLoop), validationFlows
├── TestData.properties(.example)          # all base URLs, creds, driver token - gitignored
└── pom.xml
```

