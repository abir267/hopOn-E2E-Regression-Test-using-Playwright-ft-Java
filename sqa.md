# SQA Portfolio — Hopon Food Test Automation

**Project:** Hopon Food Delivery Platform  
**Role:** Software Quality Assurance Engineer  
**Tech Stack:** Java 17, Playwright, TestNG, Maven, MQTT (HiveMQ), REST APIs, Appium/ADB  
**Platforms Covered:** Customer Web App, Restaurant Admin Portal, Driver App (API + Mobile), Rider Mobile App (Android)  
**Environment:** Dev (dev-food.hoponbd.com, dev-foodadmin.hoponbd.com, dev-api.hoponbd.com)

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture & Framework Design](#2-architecture--framework-design)
3. [Test Coverage Summary](#3-test-coverage-summary)
4. [End-to-End Test Scenarios](#4-end-to-end-test-scenarios)
5. [Validation & Negative-Path Tests](#5-validation--negative-path-tests)
6. [Admin Portal Tests](#6-admin-portal-tests)
7. [Customer Journey Tests](#7-customer-journey-tests)
8. [Mobile App Testing (Android)](#8-mobile-app-testing-android)
9. [API Testing](#9-api-testing)
10. [Bug Tracking & Verification](#10-bug-tracking--verification)
11. [Real-Time MQTT Monitoring](#11-real-time-mqtt-monitoring)
12. [Infrastructure & Utilities](#12-infrastructure--utilities)
13. [Reporting & Evidence](#13-reporting--evidence)
14. [Key Achievements](#14-key-achievements)
15. [Test Metrics](#15-test-metrics)

---

## 1. Project Overview

Built a comprehensive, cross-system test automation framework for **Hopon Food** — a food delivery platform with three interconnected systems:

| System | URL | Role in Tests |
|--------|-----|---------------|
| **Customer Web App** | `dev-food.hoponbd.com` | Login, search, browse menu, add to cart, checkout, place order, track order, chat with driver |
| **Restaurant Admin Portal** | `dev-foodadmin.hoponbd.com` | Login, accept orders, mark ready, extend prep time, cancel orders, manage menu items, create discounts |
| **Driver App Backend** | `dev-api.hoponbd.com` | Accept order, arrive at restaurant, pick up, deliver with photo proof, chat with customer |

All three systems are exercised **together** in end-to-end lifecycle tests — a real order flows from customer to restaurant to driver in a single automated run against the live dev backend.

---

## 2. Architecture & Framework Design

### Page Object Model (POM)

```
src/main/java/
├── pages/WebPages/
│   ├── customerPages/         # 7 page objects
│   │   ├── BasePage.java      # shared waits, navigation
│   │   ├── LoginPage.java     # phone + OTP login
│   │   ├── HomePage.java      # search, restaurant selection
│   │   ├── RestaurantMenuPage.java  # menu browsing, item options, add to cart
│   │   ├── CartPage.java      # cart management
│   │   ├── CheckoutPage.java  # delivery details, place order
│   │   └── OrderTrackingPage.java   # live order tracking, chat
│   └── webrestaurantPages/    # 6 page objects
│       ├── AdminBasePage.java
│       ├── WebRestaurantLogin.java
│       ├── RestaurantPortalPage.java  # sidebar navigation
│       ├── LiveOrdersPage.java        # accept, ready, extend, cancel, PIN
│       ├── MenuManagementPage.java    # item availability toggle
│       ├── AddFoodItemPage.java       # add new menu items
│       └── CreateDiscountPage.java    # discount creation
├── foodAPIs/
│   ├── driverModule/
│   │   ├── DriverAPI.java             # full driver order lifecycle via REST
│   │   ├── DriverChat.java            # driver chat via MQTT publish
│   │   └── PlaceholderProofPhotos.java
│   └── headerManager.java            # shared HTTP header builders
└── utils/
    ├── ConfigReader.java       # externalized config (TestData.properties)
    ├── TestDataGenerator.java  # random phone numbers, test data
    ├── RetryAnalyzer.java      # auto-retry on transient failures (4 retries)
    ├── ConsoleLogListener.java # timestamped, color-coded terminal output
    ├── MqttListener.java       # real-time MQTT order-status listener
    ├── MqttConsoleCapture.java # decode & print MQTT packets from WebSocket
    └── BugWatcher.java         # live console error/XHR failure detection
```

### Key Design Decisions

- **No mocks** — all tests run against live dev backend, creating real orders
- **Cross-system orchestration** — end-to-end tests manage multiple Playwright browser contexts (customer + restaurant) and API calls (driver) simultaneously
- **Session-derived auth** — MQTT listener reads broker credentials from Playwright page cookies/localStorage, exactly like the real app
- **Auto-retry** — `RetryAnalyzer` handles transient OTP/login flakiness with fresh random phone numbers each attempt
- **Live bug detection** — `BugWatcher` hooks into every page automatically, catching JS errors, uncaught exceptions, and failed XHR calls independent of test assertions

---

## 3. Test Coverage Summary

| Category | Test Count | Status |
|----------|-----------|--------|
| **End-to-End Lifecycle** | 5 | All passing |
| **Validation / Negative-Path** | 10 | 6 pass, 4 intentionally fail (confirmed bugs) |
| **Admin Portal** | 3 | All passing |
| **Customer Journey** | 3 | All passing |
| **Auth / Login** | 1 | Passing |
| **Mobile App (Android)** | 9 | Mixed (device-dependent) |
| **API Tests** | 2 | Functional |
| **Total** | **33** | — |

### Test Suite Configuration (10 XML suites)

| Suite | Scope |
|-------|-------|
| `orderLifecycle.xml` | Full 3-system lifecycle |
| `orderLifecycleDriverInLoop.xml` | Realistic interleaved timing |
| `orderLifecycleChat.xml` | Order with customer-driver chat |
| `restaurantCancelsFromPreparing.xml` | Restaurant cancel (4 reasons) |
| `customerInstructions.xml` | Special + delivery instructions E2E |
| `validationFlows.xml` | 10 negative-path / bug-report tests |
| `webRestaurant.xml` | Admin portal navigation smoke |
| `testng.xml` | Full customer journey |
| `smoke.xml` | Fast login + empty-cart check |
| `mobile-testng.xml` | Android mobile tests |

---

## 4. End-to-End Test Scenarios

### 4.1 Full Order Lifecycle (`OrderLifecycleTest`)
Customer places order on QA Burger Express → restaurant admin accepts into "Preparing" → marks "Ready for Pickup" → driver API: Accepted → Arrived → PickedUp → Delivered (with photo proof upload). Captures real MQTT status pushes at every transition.

### 4.2 Driver-in-Loop Timing (`OrderLifecycleDriverInLoopTest`)
Realistic interleaving: driver accepts and arrives **while restaurant is still Preparing**. Restaurant marks ready after driver arrival. Driver then completes pickup and delivery. Tests the real-world timing where systems don't proceed in strict sequence.

### 4.3 Customer-Driver Chat (`OrderLifecycleChatTest`)
Full chat lifecycle: customer sends 4 messages (typed + quick reply) through web UI → driver replies via MQTT (mimicking real app's `ride/message` topic) → customer receives and displays driver's reply. Each message verified end-to-end.

### 4.4 Restaurant Cancellation — All 4 Reasons (`RestaurantCancelsFromPreparingAllReasonsTest`)
Four complete order→accept→cancel cycles in one run, covering every cancellation reason the modal offers:
- Out of stock
- Store is closing
- Customer requested
- Other

Documents confirmed backend quirk: cancel response `status`/`title`/`message` fields are always null on success.

### 4.5 Customer Instructions Visibility (`RestaurantAndDriverSeeCustomerInstructionsTest`)
Verifies data routing: customer writes **Special Instructions** (item-level) + **Delivery Instructions** (checkout-level). Confirms:
- Restaurant sees Special Instructions under "Customer note" accordion
- Restaurant does NOT see Delivery Instructions (correct by design, not a bug)
- Driver API response contains BOTH fields

---

## 5. Validation & Negative-Path Tests

10 tests built by reproducing real reported issues live, then locking observed behavior into assertions. Screenshot evidence saved to `target/bug-evidence/` on every run.

| # | Test | What It Checks | Result |
|---|------|----------------|--------|
| 1 | `GpsOutsideServiceAreaTest` | Customer in Cox's Bazar (outside Dhaka) tries to order | PASS — correctly blocked |
| 2 | `RestaurantNotAcceptingOrdersTest` | Restaurant toggled offline before item added | FAIL (intentional) — **confirmed bug**: order places with zero warning |
| 3 | `MenuItemNotAvailableTest` | Menu item toggled unavailable before being added | FAIL (intentional) — **confirmed bug**: order places with zero warning |
| 4 | `NoValidationMessageOnCheckoutTest` | Item added first, restaurant goes offline after | FAIL (intentional) — same bug, different ordering |
| 5 | `RestaurantCancelsOrderTest` | Restaurant cancels while customer tracking page is open | PASS — cancellation reaches customer live via polling (~8s) |
| 6 | `CancelOrderModalBrokenTest` | Customer clicks "Cancel Order" on tracking page | FAIL (intentional) — **confirmed bug**: missing Lucide icon crashes confirmation toast |
| 7 | `CartBadgeOnLocationChangeTest` | Change delivery location with item in cart | PASS — basket persists, zone warning appears correctly |
| 8 | `RestaurantExtendPrepTimeTest` | Restaurant clicks "Need More Time" (+20 min) | PASS — customer's delivery ETA updates accordingly |
| 9 | `DriverCancelBeforeAcceptTest` | Unassigned driver tries to cancel | PASS — correctly rejected (400 "Invalid user") |
| 10 | `DriverCancelAfterAcceptTest` / `DriverCancelAfterArrivedTest` | Driver cancels after accept/arrival | PASS — correctly unassigned, order returns to pool |

**4 tests are designed to fail** — the failure IS the bug report. Fix the product, then update the assertion.

---

## 6. Admin Portal Tests

### 6.1 Navigation Smoke (`WebRestaurantNavigationTest`)
Logs in, navigates all 4 sidebar tabs: Dashboard, Live Orders, Menu Management, Discount & Promotions. Documents that Dashboard still shows ride-hailing placeholder content (known gap, not a bug in tests).

### 6.2 Add Food Item (`AddFoodItemTest`)
Three scenarios:
- Full-detail submission with all fields and image upload
- Minimum required fields only
- Validation path — leave required fields blank, verify error messages

### 6.3 Create Discount (`CreateDiscountTest`)
Happy-path discount creation: percentage discount, default "all active menu items" scope, with date range varied per run to avoid backend collision with prior runs (backend rejects overlapping same-scope discounts — confirmed).

---

## 7. Customer Journey Tests

### 7.1 Search and Order (`SearchAndOrderTest`)
Full customer flow: login → search restaurant → browse menu → add item to cart → checkout → place order.

### 7.2 Item Variation (`ItemVariationTest`)
Covers items with multiple required customization groups (size + extra required choice), quantity stepper, and special instructions. Confirms all selections reflected correctly in the cart. Built on confirmed modal mechanics from QA Burger Express live probing.

### 7.3 Login (`LoginTest`)
Phone + OTP login flow. Customer login uses freshly generated numbers: `011` + 8 random digits, OTP always `1122`.

---

## 8. Mobile App Testing (Android)

**Device:** Infinix X6725, Android 15  
**App:** com.hoponbd.rider (debug build, versionCode 45→46)  
**Methods:** Appium, ADB shell commands, logcat analysis, UI hierarchy dumps, APK decompilation (jadx)

### 9 Mobile Test Cases

| Test | What It Covers |
|------|---------------|
| `DeviceConnectionTest` | ADB device connectivity verification |
| `MainActivityIntentCrashTest` | HOP-004: crafted external intent doesn't crash app (security) |
| `RideVehicleTabResetTest` | HOP-017: switching vehicle tabs resets selection correctly |
| `ParcelSendRequestDoubleSubmitTest` | HOP-020: double-tap Send Request doesn't fire duplicate HTTP calls |
| `FoodCartRapidAddRaceTest` | HOP-032: rapid "Add to cart" taps don't race/drop items |
| `FoodHistoryTabLoadsTest` | HOP-028: food history tab loads and displays past orders |
| `FoodReceiptDownloadTest` | HOP-029: receipt download works (not main-thread I/O) |
| `FoodCartEmptyStateMessageTest` | HOP-027: empty cart shows proper message, not generic "Error" |
| `FoodAddToCartOfflineErrorTest` | HOP-027: offline add-to-cart shows specific error, not generic |

### Mobile Testing Approach
- No resource-ids available (Compose app, no testTags) — tests use raw coordinate taps confirmed by manual ADB exploration
- UI verification via `uiautomator dump` + regex matching of text nodes between known content-desc boundaries
- Logcat monitoring for HTTP request deduplication and crash detection

---

## 9. API Testing

### Driver API (`DriverAPI.java`) — Fully Confirmed
Every endpoint verified against real device network capture (HTTP Toolkit proxy):

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/services/food/api/food-orders/driver/acknowledge` | POST | All status transitions: Accepted → Arrived → PickedUp → Delivered |
| `/services/content/api/content/upload?contentCategory=OTHERS` | POST | Photo proof upload (multipart, field name "file") |

Status chain confirmed via response body: each call returns `nextStatus` pointing to the next valid state.

### Driver Chat (`DriverChat.java`) — MQTT Protocol
Driver replies published over MQTT (topic `ride/message`, QoS 1) on `wss://dev-mqtt.hoponbd.com/mqtt`. Payload structure confirmed by capturing what the customer web app publishes.

---

## 10. Bug Tracking & Verification

### HOP Bug Tracker (30 Issues: HOP-001 through HOP-030)

Maintained a structured bug tracker cross-checked against:
- Decompiled APK source (jadx) for code-level verification
- Live device testing (Appium/ADB) for behavioral verification
- Network captures (HTTP Toolkit, WebSocket inspection)
- Manual UI testing

### Verification Summary

| Status | Count | Details |
|--------|-------|---------|
| **Verified FIXED** | 9 | HOP-002, 004, 006, 014, 016, 017, 020, 027, confirmed via automation or manual re-test |
| **Verified STILL BROKEN** | 3 | HOP-012 (MQTT plaintext), 013 (debug network config), 015 (dev server in release) |
| **Likely Fixed** | 1 | HOP-005 (code review confirmed fix, not runtime-verified) |
| **Not Verifiable** (no source access) | 10 | HOP-003, 007-011, 021, 023-025 |
| **Not Yet Run** | 4 | HOP-001, 018, 019 (need specific preconditions) |
| **Inconclusive** | 1 | HOP-028 (couldn't reach specific error path) |
| **Won't Fix** | 1 | HOP-022 (product decision) |
| **Needs Verify** | 1 | HOP-026 (flagged by dev team) |

### Notable Bug Findings

**Security Issues Found:**
- **HOP-012 (P0):** MQTT uses plaintext `tcp://` — password carries user token in cleartext. Still broken in v46.
- **HOP-013 (P0):** Release build uses debug `network_security_config` with unconditional `cleartextTrafficPermitted="true"`.
- **HOP-015 (P0):** Release build still points to dev server (`dev-api.hoponbd.com`), no build flavor split.

**Web App Bugs Found (via Validation Tests):**
- Restaurant/menu item offline status not enforced at checkout — orders place silently despite zero availability.
- Customer "Cancel Order" button crashes due to missing Lucide icon in confirmation toast.
- Restaurant cancel endpoint returns null status fields on success (documented quirk).

**App Version Tracking:**
- Tracked v45 → v46 overnight update (versionCode 45→46, 2026-10-25 14:49)
- 5 issues flipped from broken to fixed in v46: HOP-002, 006, 014, 016, 017
- Re-verified all 30 issues against new build

---

## 11. Real-Time MQTT Monitoring

### MqttListener — Session-Derived Broker Connection
- Connects to `wss://dev-mqtt.hoponbd.com/mqtt` (MQTT v5 over WebSocket, HiveMQ client)
- Auth extracted from Playwright page's cookies (`_ssl_mt`, `_ssl_mu`, `_ssl_taf`) and localStorage (`_ssl_mcid`) after login
- Decompiled from dev-food.hoponbd.com's own `mqtt-message.service.ts` via DevTools source maps

### MqttConsoleCapture — WebSocket Packet Decoder
- Hooks Playwright page's MQTT WebSocket
- Decodes raw PUBLISH packets the broker pushes
- Prints real payloads the app received: `[MQTT] <- [CUSTOMER] 025-D78 | Preparing | "Preparing your food"`
- End-to-end tests wait up to 10s for final `Completed` push on both customer and restaurant pages

---

## 12. Infrastructure & Utilities

### ConsoleLogListener — Structured Terminal Output
Auto-registered via META-INF/services, active for every run method. Provides:
- Timestamped output: `[03:24:22 PM] + [RESTAURANT] Order #025-D78 accepted`
- Color-coded by role: blue (test), green (steps), yellow (warnings), red (failures), magenta (MQTT)
- Unicode/ASCII fallback detection
- Configurable: `-Dmqtt.full=true`, `-Dno.color=true`, `-Dno.unicode=true`

### BugWatcher — Passive Defect Detection
Attached to every page automatically:
- Catches `console.error` messages
- Catches uncaught JS exceptions (`page.onPageError`)
- Catches failed XHR/fetch calls (4xx/5xx, excluding expected 401s)
- Independent of test assertions — catches bugs tests don't explicitly check

### RetryAnalyzer — Flakiness Handling
- 4 retries per test
- Fresh random phone number each attempt (no repeated state)
- Handles transient OTP/login timeouts on dev backend

### ConfigReader — Externalized Configuration
- All URLs, credentials, tokens in `TestData.properties` (gitignored)
- Example file provided: `TestData.properties.example`
- Supports headless/headed toggle

---

## 13. Reporting & Evidence

| Artifact | Location |
|----------|----------|
| Surefire XML/HTML reports | `target/surefire-reports` |
| Extent HTML report | `target/ExtentReport/index.html` |
| Failure screenshots + Playwright traces | `target/test-artifacts` |
| Bug-evidence screenshots | `target/bug-evidence/issueN_*.png` |
| Bug tracker (structured XML) | `HOP-Bug-Tracker.xml` |
| Bug tracker (spreadsheet) | `HOP-Bug-Tracker.xlsx` |

Playwright traces viewable with:
```bash
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="show-trace target/test-artifacts/<name>_trace.zip"
```

---

## 14. Key Achievements

1. **Cross-System E2E Automation** — Built tests that orchestrate 3 independent systems (customer web, restaurant admin, driver API) in a single automated run, creating real orders on the live dev backend.

2. **Real MQTT Integration** — Reverse-engineered the platform's MQTT protocol from production source maps, built a session-derived listener that captures real-time order status pushes exactly as the app receives them.

3. **Bug Discovery & Verification** — Tracked 30 bugs (HOP-001–HOP-030), independently verified 14 through APK decompilation, live device testing, and automated tests. Found 3 unresolved P0 security issues.

4. **Negative-Path Test Suite** — Created 10 validation tests that reproduce real reported issues, with 4 intentionally failing as living bug reports with screenshot evidence.

5. **Mobile App Testing Without Test IDs** — Automated 9 mobile tests on a Compose-based Android app with no resource-ids using coordinate taps, UI hierarchy dumps, and logcat monitoring.

6. **Live Bug Detection** — `BugWatcher` passively catches JS errors, uncaught exceptions, and failed API calls during any test, independent of test assertions.

7. **Production-Quality Terminal Output** — Timestamped, color-coded, role-tagged console output with real MQTT interleaving makes test runs readable as a live system narrative.

8. **Docker & CI Support** — Test scripts for local, Jenkins (headless), and Docker execution environments.

---

## 15. Test Metrics

| Metric | Value |
|--------|-------|
| Total test classes | 33 |
| Total test suites (XML) | 10 |
| Page object classes | 13 |
| API client classes | 3 |
| Utility classes | 7 |
| Bugs tracked | 30 (HOP-001–HOP-030) |
| Bugs independently verified | 14 |
| Security issues found | 3 (P0, still open) |
| Systems under test | 3 (web + admin + driver API) |
| Platforms covered | 2 (Web + Android mobile) |
| Framework | Playwright (Java) + TestNG + Maven |
| Language | Java 17 |

---

*Last updated: 2026-11-01*
