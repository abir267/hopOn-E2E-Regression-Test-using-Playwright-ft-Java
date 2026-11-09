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

# Hop On Admin Management portal (super-admin) - used to deactivate/suspend the restaurant
# the admin-created lifecycle test builds. If these are missing or CHANGE_ME, the code falls back
# to admin-portal-automation/src/test/resources/config.properties (admin.url/email/password).
admin.portal.url=https://dev-admin.hoponbd.com/public/login
admin.portal.email=<super-admin email>
admin.portal.password=<super-admin password>
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

### The main test cases (`src/test/java/WebFunctionalTests/EndToEnd/`)

Tests 1-6 drive the whole system at once — customer web app, restaurant-admin web app and the driver app —
and are the ones to run first. Test 7 is an admin-portal-only follow-up. Each prints every step with a timestamp, plus the real MQTT pushes the customer
and restaurant web apps receive (see "Reading the terminal output" below).

| # | Test class | Flow | Command (`-Dsuite=`) |
|---|---|---|---|
| 1 | `OrderLifecycleTest` | customer orders → restaurant accepts **and** marks Ready → driver Accepted → Arrived → PickedUp → Delivered | `test-suites/orderLifecycle.xml` |
| 2 | `OrderLifecycleDriverInLoopTest` | driver accepts and arrives **while the restaurant is still Preparing**; restaurant marks Ready after; driver then picks up + delivers | `test-suites/orderLifecycleDriverInLoop.xml` |
| 3 | `OrderLifecycleChatTest` | customer orders → restaurant Preparing → driver assigned → **customer chats 4 messages** → driver `ok` → restaurant Ready for Pickup → driver `I am coming` → delivered | `test-suites/orderLifecycleChat.xml` |
| 4 | `RestaurantCancelsFromPreparingAllReasonsTest` | customer orders → restaurant accepts into Preparing → restaurant **cancels**, once per each of the 4 reasons the modal offers (Out of stock / Store is closing / Customer requested / Other) — 4 complete order→accept→cancel cycles in one run | `test-suites/restaurantCancelsFromPreparing.xml` |
| 5 | `RestaurantAndDriverSeeCustomerInstructionsTest` | customer writes item **Special Instructions** + checkout **Delivery Instructions** → confirms the restaurant sees the Special Instructions (and correctly does NOT see Delivery Instructions, by design) → confirms the driver's API response has both → completes the order | `test-suites/customerInstructions.xml` |
| 6 | `AdminCreatedRestaurantOrderLifecycleTest` | Order lifecycle on the restaurant `admin-portal-automation` just created, then **clean-up: Accepting Orders turned OFF, then the restaurant's activation turned OFF through the super-admin API** (`PATCH /services/admin/api/restaurant-activation`, `activated=false`; the HTTP status and reply are printed). See its own section below. | `test-suites/adminCreatedRestaurantOrderLifecycle.xml` |
| 7 | `AdminCreatedRestaurantSuspendTest` | Takes the restaurant `admin-portal-automation` most recently created and **suspends it from the Hop On Admin Management portal**: Restaurants → that restaurant's View page → Suspend Account → Restaurant Block modal (reason + Block Status ON) → Confirm Block, then re-checks after a reload. Every locator is structural (component tag / `formcontrolname` / icon name) — no visible text. Can run alone (on the last-created restaurant) or right after #6. | `test-suites/adminCreatedRestaurantSuspend.xml` (alone) · `test-suites/adminCreatedRestaurantOrderThenSuspend.xml` (#6 then #7) |

```bash
mvn "-Dsuite=test-suites/orderLifecycle.xml" "-Dexec.mainClass=init.TestRunner" "-Dexec.classpathScope=test" exec:java
mvn "-Dsuite=test-suites/orderLifecycleDriverInLoop.xml" "-Dexec.mainClass=init.TestRunner" "-Dexec.classpathScope=test" exec:java
mvn "-Dsuite=test-suites/orderLifecycleChat.xml" "-Dexec.mainClass=init.TestRunner" "-Dexec.classpathScope=test" exec:java
mvn "-Dsuite=test-suites/restaurantCancelsFromPreparing.xml" "-Dexec.mainClass=init.TestRunner" "-Dexec.classpathScope=test" exec:java
mvn "-Dsuite=test-suites/customerInstructions.xml" "-Dexec.mainClass=init.TestRunner" "-Dexec.classpathScope=test" exec:java
mvn clean test "-DsuiteXmlFile=test-suites/adminCreatedRestaurantOrderLifecycle.xml"
mvn clean test "-DsuiteXmlFile=test-suites/adminCreatedRestaurantOrderThenSuspend.xml"
mvn test "-DsuiteXmlFile=test-suites/adminCreatedRestaurantSuspend.xml"
```

> **`-DsuiteXmlFile` takes a path relative to the repo root** — write `test-suites/<file>.xml`, never an
> absolute path and never `test-suites/` in front of one (that gives `Suite file ...\test-suites\d:\... is not a
> valid file`). The last command (suspend alone) does not use `clean`, so it keeps the
> `admin-portal-automation/target/last-created-restaurant.properties` file the previous admin run wrote.

`RestaurantCancelsFromPreparingAllReasonsTest` also documents a real backend quirk: the cancel endpoint's response
always has `status`/`title`/`message` null on success (same as the driver-side cancel `DriverAPI` already notes) —
`LiveOrdersPage.isOrderPanelOpen()` checks the detail panel actually closing instead of trusting that field.

`RestaurantAndDriverSeeCustomerInstructionsTest` documents which backend field each note box writes to and who is
actually meant to see it: the menu item's "Special Instructions" (`itemDetails[].foodInstruction`) is restaurant-
facing, shown under the order panel's "Customer note" accordion (`LiveOrdersPage.customerNoteText()`); the
checkout page's "Delivery Instructions" (`deliveryInstruction`) is driver-facing only, by design — confirmed not
shown anywhere in the restaurant-admin UI, which is correct, not a bug. Both are confirmed present in the
driver-app API response. It also fixed a real flake: calling `LiveOrdersPage.orderUuidFor()` (which reloads the
page) before `markReadyForPickup()` was wiping the open order panel and timing out that click — the fix reorders
so the reload happens after.

Customer logins use a freshly generated number: `011` + 8 random digits (`TestDataGenerator.randomRiderPhone()`),
OTP is always `1122` (`test.user.otp` in `TestData.properties`). `riderlist.csv` is no longer used for logins.

### Available suites

| Suite file | What it does |
|---|---|
| `test-suites/orderLifecycle.xml` | **Full 3-system flow.** Customer places an order on QA Burger Express → restaurant accepts *and* marks it ready → driver runs Accepted → Arrived → PickedUp → Delivered (with photo proof). |
| `test-suites/orderLifecycleDriverInLoop.xml` | Same full flow, but realistic interleaving: driver accepts and arrives **while the restaurant is still Preparing**, restaurant marks ready *after*, then driver completes pickup + delivery. |
| `test-suites/orderLifecycleChat.xml` | **Order with customer ↔ driver chat.** Customer orders → restaurant accepts to *Preparing* → driver is assigned → customer sends 4 chat messages (typed + quick reply) in the web UI → driver answers `ok` → restaurant marks *Ready for Pickup* → driver says `I am coming` → driver completes Arrived → PickedUp → Delivered. Each driver reply is asserted to show up in the customer's chat panel. |
| `test-suites/adminCreatedRestaurantSuspend.xml` | **Suspend only.** Suspends the restaurant `admin-portal-automation` most recently created (read from `admin-portal-automation/target/last-created-restaurant.properties`) in the Hop On Admin Management portal. Needs that file to exist, i.e. an admin run must have happened first. |
| `test-suites/adminCreatedRestaurantOrderThenSuspend.xml` | **Lifecycle, then suspend.** Runs `AdminCreatedRestaurantOrderLifecycleTest` (creates the restaurant, orders, delivers, turns activation OFF) and then `AdminCreatedRestaurantSuspendTest` on that same restaurant. Runs in listed order. |
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

#### Chat lifecycle test (`OrderLifecycleChatTest`)

```bash
mvn "-Dsuite=test-suites/orderLifecycleChat.xml" "-Dexec.mainClass=init.TestRunner" "-Dexec.classpathScope=test" exec:java
```

The customer chats through the real web UI ("Chat with Rider" appears once a driver accepts). The driver app
has no UI, so `foodAPIs/driverModule/DriverChat` publishes the driver's replies over MQTT exactly like the app
does — topic `ride/message`, payload `{"type":"IncomingRideMessage","referenceId":<order uuid>,"message":...,
"senderSource":"DRIVER","receiverSource":"RIDER",...}`, login = driver user id + driver token (CONFIRMED by
capturing what the customer web app publishes). Real output:

```
[CUSTOMER] Order placed: #026-5AC
[RESTAURANT] Order #026-5AC accepted -> Preparing (uuid=...)
[DRIVER] Order #026-5AC -> Accepted (assigned to MD MIZANUR RAHMAN)
[CUSTOMER] Opened chat with rider
[CUSTOMER] Chat sent: "Hi, how long will it take?"
[CUSTOMER] Chat sent (quick reply): "Wait for few minutes"
[CUSTOMER] Chat sent: "Please bring extra napkins"
[CUSTOMER] Chat sent: "Thank you!"
[DRIVER] Chat sent: "ok"
[CUSTOMER] Chat received from driver: "ok"
[RESTAURANT] Order #026-5AC marked Ready for Pickup (status=ReadyForPickup, pin=4672)
[DRIVER] Chat sent: "I am coming"
[CUSTOMER] Chat received from driver: "I am coming"
[DRIVER] Order #026-5AC -> Arrived at restaurant
[DRIVER] Order #026-5AC -> PickedUp, heading to customer
[DRIVER] Order #026-5AC -> Delivered (photo proof uploaded)
[RESULT] SUCCESS: order -> preparing -> driver assigned -> chat (customer x4, driver ok) -> ready for pickup -> driver 'I am coming' -> delivered.
```

With the terminal listener (see "Reading the terminal output") the `[MQTT]` pushes are interleaved too, including
`[MQTT] <- [CUSTOMER] chat from DRIVER: "ok"`. Customer login numbers are generated as `011` + 8 random digits
(OTP is always `1122`).

#### Admin-created restaurant lifecycle test (`AdminCreatedRestaurantOrderLifecycleTest`)

Run (from the repo root):

```bash
mvn test "-DsuiteXmlFile=test-suites/adminCreatedRestaurantOrderThenSuspend.xml"
```

After the order is delivered, the test cleans up the restaurant in two steps:

1. **Accepting Orders OFF.** Logs back into the restaurant's own portal, turns "Accepting Orders" OFF
   and re-reads it after a page reload to confirm it really persisted. (The customer-web "shows as closed" check
   that used to follow is commented out in the test pending a live root-cause investigation — see the comment in
   `turnOffAcceptingOrdersAndVerifyClosedOnCustomerWeb`.)

Real output of one run of `test-suites/adminCreatedRestaurantOrderThenSuspend.xml` (2026-11-09, run with
`-Dno.color=true`). Trimmed: `admin-portal-automation`'s own `STEP: ...` / Maven lines (same as running it
directly, see its own README) and most `[MQTT]` push lines. `[ADMIN-API]` lines print as plain text — no icon or
colour. The `Order placed` → `Delivered` part is the same as `OrderLifecycleChatTest`:

```
[01:35:30 PM] * [ADMIN] Restaurant: Automated Restaurant 109 (id=a852dc24-8bb8-4635-b1bb-c29544144aa9) | Category: Automated Food Burger 73 | Food: Cheese Automated Burger 67
[01:35:39 PM] + [RESTAURANT] Automated Restaurant 109 accepting-orders status: already ON
[01:35:51 PM] + [CUSTOMER] Logged in as 01106374473
[01:35:56 PM] + [CUSTOMER] On Restaurants tab, searched "Automated Restaurant 109"
[01:35:57 PM] + [CUSTOMER] Opened Automated Restaurant 109
[01:36:04 PM] + [CUSTOMER] Searched restaurant's own menu for "Cheese Automated Burger 67"
[01:36:10 PM] + [CUSTOMER] Added to cart: Cheese Automated Burger 67 (L)
[01:36:10 PM] + [CUSTOMER] Special Instructions (food item) written: "No onions please, extra pickles on the side"
[01:36:11 PM] + [CUSTOMER] Delivery Instructions (checkout page) written: "Please call on arrival, flat 4B - do not ring the bell"
[01:36:11 PM] + [CUSTOMER] Order placed: #02C-7B8
[01:36:19 PM] + [RESTAURANT] Order #02C-7B8 accepted -> Preparing (pin=2857), no driver assigned yet
[01:36:20 PM] + [DRIVER] Order #02C-7B8 -> Accepted (restaurant still Preparing)
[01:36:20 PM] + [DRIVER] Order #02C-7B8 -> Arrived at restaurant (still Preparing, waiting)
[01:36:20 PM] + [CUSTOMER] Opened chat with rider
[01:36:21 PM] + [CUSTOMER] Chat sent: "Hi, how long will it take?"
[01:36:22 PM] + [CUSTOMER] Chat sent (quick reply): "Wait for few minutes"
[01:36:23 PM] + [CUSTOMER] Chat sent: "Please bring extra napkins"
[01:36:24 PM] + [CUSTOMER] Chat sent: "Thank you!"
[01:36:24 PM] + [DRIVER] Chat sent: "ok"
[01:36:24 PM] + [CUSTOMER] Chat received from driver: "ok"
[01:36:26 PM] + [RESTAURANT] Order #02C-7B8 -> ReadyForPickup
[01:36:26 PM] + [DRIVER] Chat sent: "I am coming"
[01:36:26 PM] + [CUSTOMER] Chat received from driver: "I am coming"
[01:36:27 PM] + [DRIVER] Order #02C-7B8 -> PickedUp, heading to customer
[01:36:27 PM] + [DRIVER] Order #02C-7B8 -> Delivered (photo proof uploaded)
[01:36:27 PM] + [DRIVER] Special Instructions + Delivery Instructions both confirmed in the driver API response
[01:36:33 PM] + [RESTAURANT] Automated Restaurant 109 accepting-orders status: turned OFF (re-checked after page reload: confirmed OFF)
[01:36:33 PM] [ADMIN-API] DEACTIVATE restaurant Automated Restaurant 109 (id=a852dc24-8bb8-4635-b1bb-c29544144aa9)
[01:36:36 PM] [ADMIN-API] PATCH https://dev-api.hoponbd.com/services/admin/api/restaurant-activation -> HTTP 200 {"title":"Success","detail":"Operation successful"}
[01:36:36 PM] [ADMIN-API] Restaurant Automated Restaurant 109 is now DEACTIVATED (OFF)
[01:36:36 PM] [ADMIN-API] Activation OFF confirmed for Automated Restaurant 109 (id=a852dc24-8bb8-4635-b1bb-c29544144aa9): {"title":"Success","detail":"Operation successful"}
[01:36:37 PM] + [RESULT] SUCCESS: Automated Restaurant 109 (Cheese Automated Burger 67) - admin -> customer (chat, Special/Delivery Instructions) -> restaurant (Preparing -> Ready) -> driver (Accepted -> Arrived -> PickedUp -> Delivered) -> restaurant turned OFF (customer-web closed-check disabled for now).
[01:36:37 PM] + [TEST] PASSED AdminCreatedRestaurantOrderLifecycleTest.customerOrdersFromLatestAdminCreatedRestaurant (170.7s)
[01:36:37 PM] > [TEST] START  AdminCreatedRestaurantSuspendTest.suspendLatestAdminCreatedRestaurant
[01:36:37 PM] * [ADMIN] Target restaurant: Automated Restaurant 109 (id=a852dc24-8bb8-4635-b1bb-c29544144aa9)
[01:36:41 PM] * [ADMIN] Logged in, on Restaurants list
[01:36:42 PM] * [ADMIN] Opened Restaurant Overview, already suspended before: false
[01:36:44 PM] * [ADMIN] Submitted Suspend Account (Block Reason="Automated test - restaurant no longer needed", Block Status=ON)
[01:36:44 PM] * [ADMIN] Suspended after reload (Unblock action shown): true
[01:36:44 PM] + [RESULT] SUCCESS: Automated Restaurant 109 suspended via admin portal (verified after reload)
[01:36:44 PM] + [TEST] PASSED AdminCreatedRestaurantSuspendTest.suspendLatestAdminCreatedRestaurant (7.8s)
[WARNING] Tests run: 3, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 391.3 s -- in TestSuite
[INFO] BUILD SUCCESS
[INFO] Total time:  06:35 min
```

`Tests run: 3 ... Skipped: 1` is normal here, not a problem: the first attempt of the lifecycle test failed on a
flaky admin-portal step (below), TestNG reports a retried attempt as SKIPPED, and the second attempt passed. That
first attempt is what the "Error log" block (§ "Reading the terminal output") is for — this is its real output:

```
[01:33:46 PM] ! Retrying 'customerOrdersFromLatestAdminCreatedRestaurant' (attempt 1/2)
[01:33:46 PM] ! [TEST] SKIPPED AdminCreatedRestaurantOrderLifecycleTest.customerOrdersFromLatestAdminCreatedRestaurant
[01:33:46 PM] x [FAILED] ===== ERROR LOG: AdminCreatedRestaurantOrderLifecycleTest.customerOrdersFromLatestAdminCreatedRestaurant (212.5s) =====
[01:33:46 PM] java.lang.AssertionError: admin-portal-automation e2e-testng.xml failed (restaurant/category/food not created) - see its output above expected [0] but found [1]
    at WebFunctionalTests.EndToEnd.AdminCreatedRestaurantOrderLifecycleTest.createRestaurantCategoryAndFoodViaAdminPortal(AdminCreatedRestaurantOrderLifecycleTest.java:214)
    at WebFunctionalTests.EndToEnd.AdminCreatedRestaurantOrderLifecycleTest.customerOrdersFromLatestAdminCreatedRestaurant(AdminCreatedRestaurantOrderLifecycleTest.java:74)
[01:33:46 PM] x [FAILED] ===== END ERROR LOG =====
```

The cause is in the admin subprocess's output just above that block: `FAILED AT STEP 'Click Submit (back to the
Restaurants list)' -> Timeout 30000ms exceeded` (the Submit button resolved but the click never completed). The
other three admin tests then failed at `Wait for the restaurant portal` (that restaurant was never fully
created). It is a live dev-site flake — the retry re-creates a fresh restaurant and passes.

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

Every line is prefixed with the real wall-clock time, a status icon and (when the
terminal supports it) colour. `utils/ConsoleLogListener` does this for every run
(`mvn test`, `-Dtest=...`, `init.TestRunner`) — no per-test code.

```
[03:23:11 PM] > [TEST] START  OrderLifecycleTest.customerToRestaurantToDriverAcceptance
[03:23:27 PM] ! [LOGIN] #phone did not render within 10000ms (attempt 1/3), reloading and retrying...
[03:23:35 PM] + [CUSTOMER] Logged in as 01123773500
[03:23:46 PM] + [CUSTOMER] Added to cart: Crispy Burger (Regular)
[03:23:49 PM] + [CUSTOMER] Order placed: #025-D78
[03:24:22 PM] * [MQTT] <- [CUSTOMER] 025-D78 | Preparing | "Preparing your food"
[03:24:22 PM] + [RESTAURANT] Order #025-D78 accepted and marked Ready for Pickup (uuid=04763188-..., pin=0018)
[03:24:22 PM] + [DRIVER] Order #025-D78 -> Accepted
[03:24:23 PM] + [DRIVER] Order #025-D78 -> Arrived at restaurant
[03:24:23 PM] * [MQTT] <- [CUSTOMER] 025-D78 | ReadyForPickup | "It's fresh and ready!"
[03:24:23 PM] + [DRIVER] Order #025-D78 -> PickedUp, heading to customer
[03:24:23 PM] * [MQTT] <- [RESTAURANT] 025-D78 | ReadyForPickup | "Ready" / "Waiting for driver to pickup"
[03:24:24 PM] * [MQTT] <- [CUSTOMER] 025-D78 | Delivering | "Driver On The Way"
[03:24:24 PM] * [MQTT] <- [RESTAURANT] 025-D78 | Delivering | "Driver On The Way" / "Order is moving and should arrive soon"
[03:24:50 PM] + [DRIVER] Order #025-D78 -> Delivered (photo proof uploaded)
[03:24:50 PM] * [MQTT] <- [CUSTOMER] 025-D78 | Completed | "Order Completed"
[03:24:50 PM] * [MQTT] <- [RESTAURANT] 025-D78 | Completed | "Food Delivered" / "Food delivered successfully!"
[03:24:50 PM] + [RESULT] SUCCESS: full lifecycle completed - customer -> restaurant -> driver (Accepted -> Arrived -> PickedUp -> Delivered).
[03:24:50 PM] + [TEST] PASSED OrderLifecycleTest.customerToRestaurantToDriverAcceptance (99.0s)
```

In a colour terminal the `CUSTOMER` / `RESTAURANT` / `DRIVER` tag in each `[MQTT]` line gets a
coloured background (blue / yellow / magenta). Lines are real pushes the web apps received, so
the same status can legitimately appear more than once (the backend re-sends some statuses),
and an intermediate status can be absent if the page was reconnecting when it was sent. Before
closing the browser the end-to-end tests wait up to 10s for the final `Completed` push on both
the customer and restaurant pages, so the last two `[MQTT]` lines are always captured.

| Prefix | Meaning |
|---|---|
| `[TEST] START / PASSED / FAILED / SKIPPED` | running test name (`Class.method`) and duration |
| `[CUSTOMER]` `[RESTAURANT]` `[DRIVER]` `[RESULT]` | test steps (green) |
| `[LOGIN]`, `Retrying ...` | slow login / retry notice (yellow) |
| `[FAILED]` | failure with the exception (red) |
| `[ADMIN]` | admin-side steps (red) |
| `[ADMIN-API]` | the activation PATCH, its HTTP status and reply — printed as plain text, no icon or colour |
| `[FAILED] ===== ERROR LOG ... =====` | copy-ready error block, see "Error log" below |
| `[MQTT] <- [ROLE] order, status, "title"` | real MQTT push the customer/restaurant web app received (magenta); restaurant lines also show the message |

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

**Error log.** When a test fails — and for every retried attempt, which TestNG reports as SKIPPED — the listener
prints one block with the full cause chain and stack trace (TestNG/JDK/Maven frames trimmed), indented with
spaces so the lines carry no timestamp and are easy to copy:

```
x [FAILED] ===== ERROR LOG: AdminCreatedRestaurantOrderLifecycleTest.customerOrdersFromLatestAdminCreatedRestaurant (212.5s) =====
java.lang.AssertionError: admin-portal-automation e2e-testng.xml failed (restaurant/category/food not created) - see its output above expected [0] but found [1]
    at WebFunctionalTests.EndToEnd.AdminCreatedRestaurantOrderLifecycleTest.createRestaurantCategoryAndFoodViaAdminPortal(AdminCreatedRestaurantOrderLifecycleTest.java:214)
    ...
x [FAILED] ===== END ERROR LOG =====
```

(A real example from a retried run is shown in full in §3's "Admin-created restaurant lifecycle test".)
Copy everything between the two markers. The same text is appended to `target/error-log.txt`
(`mvn clean` removes it; it otherwise keeps growing across runs).

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

**Login race (fixed 2026-10-05):** with geolocation granted, the customer home page fires a burst of startup
requests that return `401` for a logged-out visitor; a "Log In" click landing in that burst was sometimes swallowed
(`#phone` never rendered) and the OTP boxes could be re-rendered mid-typing (`#otp-2` timeouts). `HomePage.openLogin()`
now waits (max 3s) for the network to settle first, and `LoginPage.enterOtp()` restarts from the first digit if the
boxes change. 16 of 16 probe logins passed with no retries afterwards.

Under heavy same-session usage the dev backend's OTP step can
occasionally time out (rate-limiting, or just a slow render of the OTP
boxes) — CONFIRMED the login mechanics themselves are fine (a fresh manual
run completes in well under half the default timeout), so this is transient,
not a real bug. Every `@Test` in `EndToEnd` / `CustomerJourney` /
`ValidationFlows` uses `utils.RetryAnalyzer` (4 retries, a fresh random
phone each attempt), so this self-heals without you re-running anything — a
`Retrying '...'` line in the output is normal, not a failure.

**Slow dev portals (fixed 2026-11-09):** `dev-foodadmin.hoponbd.com` sometimes never fires the page `load` event
within 30s, which failed the restaurant-portal login with `TimeoutError ... navigating to
"https://dev-foodadmin.hoponbd.com/", waiting until "load"`. `WebRestaurantLogin` and
`utils/RestaurantActivationApi` now wait for `DOMCONTENTLOADED` with a 60s timeout, and `WebRestaurantLogin`
retries once. Separately, the dev backend sometimes answers `POST /services/admin/api/login` with a `500`
(shown as `[BUG][ADMIN_PORTAL] 500 ...`): that is the server, not the credentials — rerun after a minute.

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

This repo holds TWO independent Maven projects side by side, not one combined build: this
one (customer web + restaurant-admin + driver API automation) and `admin-portal-automation/`
(superadmin restaurant/category/food creation, its own `pom.xml`, own README). Each is run
with its own `mvn` commands. `AdminCreatedRestaurantOrderLifecycleTest` is the only thing in
this project that touches the other one - it shells the other project's suite out as a
separate process and reads back the local file it writes when done (see
`utils.AdminCreatedRestaurant`, §3's "Admin-created restaurant lifecycle test"). Open
`hopon-food-automation.code-workspace` (repo root) in VS Code to see both projects side by
side without merging their folders.

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
│   │   │   ├── DriverChat.java            # CONFIRMED - driver chat reply via MQTT publish (topic ride/message)
│   │   │   └── PlaceholderProofPhotos.java# tiny embedded images for automated "photo proof"
│   │   └── headerManager.java             # shared header builders (anon/auth/driver)
│   ├── pages/WebPages/
│   │   ├── customerPages/                 # Page Object Model - customer web app (CONFIRMED)
│   │   ├── adminPortalPages/              # Page Object Model - Hop On Admin Management portal: AdminPortalLoginPage,
│   │   │                                  #   RestaurantsListPage (open a restaurant's View page by its href), RestaurantOverviewPage
│   │   │                                  #   (Suspend/Unblock + Restaurant Block modal) - all locators structural, no visible text
│   │   └── webrestaurantPages/                    # Page Object Model - restaurant admin (CONFIRMED)
│   │       ├── AdminBasePage.java
│   │       ├── WebRestaurantLogin.java
│   │       ├── RestaurantPortalPage.java     # sidebar nav
│   │       ├── LiveOrdersPage.java        # New/Preparing/Ready tabs, accept, ready, extend prep, cancel, pickup PIN
│   │       └── MenuManagementPage.java    # item availability (Available/Not Available) toggle
│   └── utils/                             # ConfigReader, TestDataGenerator, RetryAnalyzer, ConsoleLogListener, MqttConsoleCapture, GSheetUpdate (STUB), MqttListener (CONFIRMED), AdminCreatedRestaurant (reads admin-portal-automation's local handoff file), RestaurantActivationApi (super-admin activate/deactivate PATCH)
├── src/test/java/
│   ├── WebFunctionalTests/
│   │   ├── AuthTests/                     # customer login
│   │   ├── CustomerJourney/               # customer search -> order
│   │   ├── WebPortal/                     # restaurant-admin sidebar smoke test
│   │   ├── EndToEnd/                      # the 7 main tests: OrderLifecycle, OrderLifecycleDriverInLoop, OrderLifecycleChat, RestaurantCancelsFromPreparingAllReasons, RestaurantAndDriverSeeCustomerInstructions, AdminCreatedRestaurantOrderLifecycle, AdminCreatedRestaurantSuspend (see §3)
│   │   └── ValidationFlows/               # negative-path / bug-report suite, 10 tests (see §4)
│   ├── APITests/
│   │   ├── AuthApiTest.java               # disabled until AuthAPI is wired up
│   │   └── DriverCalling.java             # standalone script: call DriverAPI directly, no TestNG
│   └── init/                              # BaseRunner / AdminBaseRunner (Playwright lifecycle), TestRunner
├── test-scripts/                          # startup-local.sh, startup-jenkins.sh, startup-docker.sh
├── test-suites/                           # testng, smoke, webRestaurant, orderLifecycle(+DriverInLoop+Chat), adminCreatedRestaurantOrderLifecycle, adminCreatedRestaurantSuspend, adminCreatedRestaurantOrderThenSuspend, validationFlows
├── TestData.properties(.example)          # all base URLs, creds, driver token - gitignored
├── admin-portal-automation/                # SEPARATE Maven project (own pom.xml) - creates a restaurant+category+food
│                                            # via the superadmin portal (still logs to its own shared Google Sheet, see
│                                            # its own README.md); AdminCreatedRestaurantOrderLifecycleTest shells out to
│                                            # it and reads back the local target/last-created-restaurant.properties file
│                                            # CreateFoodTest.java also writes, instead of that sheet
└── pom.xml
```

