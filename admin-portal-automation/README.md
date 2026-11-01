# HopOn Food Automation
Java 17 + Maven + Playwright + TestNG. Scenarios and credentials live in the "HopOn Food- Automation data" Google Sheet.

## Setup
1. Put the service account key in the project root as `google-credentials.json` (git-ignored).
2. Share the sheet with the `client_email` from that file (Editor).
3. Run `LoginTest` from IntelliJ, or `mvn clean test`. Browsers download automatically on first run.

## How results work
Annotate a test with `@SheetScenario(feature = "...", scenario = "...")`.
After the test, the matching Scenario row gets Results (Pass/Fail), Is Automated? (Yes) and Last Checked Time. Missing rows are added.
