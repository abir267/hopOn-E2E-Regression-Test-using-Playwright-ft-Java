# DeviceProperties

Placeholder only, currently unused.

This project targets desktop web via Playwright (Chromium/Firefox/WebKit
viewport emulation), not a real device farm. If mobile-web emulation is
needed later, Playwright's built-in `Playwright.devices` presets
(`iPhone 13`, `Pixel 5`, etc.) can be used directly in `BaseRunner` without
needing this directory — it exists only as a placeholder in case a real
device matrix (BrowserStack/Sauce Labs config, native app device profiles)
becomes a requirement.
