package com.hopon.pages;

import com.hopon.config.ConfigReader;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class BasePage {

    protected final Page page;

    protected BasePage(Page page) {
        this.page = page;
    }

    // Scroll the element to the middle of the screen so you can always see what the bot is doing
    protected void scrollToCenter(String selector) {
        Locator locator = page.locator(selector);
        locator.waitFor();
        locator.evaluate("el => el.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'instant'})");
    }

    // Centred click, for form fields, buttons and tabs inside the page content
    protected void click(String selector) {
        scrollToCenter(selector);
        page.click(selector);
    }

    // Some fields start readonly (anti-autofill) and unlock on focus, so click first, then fill
    protected void clickAndFill(String selector, String value) {
        scrollToCenter(selector);
        page.click(selector);
        page.locator(selector).evaluate("el => el.removeAttribute('readonly')");   // safeguard
        page.fill(selector, value);
    }

    // Waits until the element is gone or hidden
    protected void waitUntilHidden(String selector) {
        page.locator(selector).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
    }

    // Finds a file in src/test/resources/files, with a clear error if it is missing
    protected Path uploadFilePath(String fileName) {
        Path path = Paths.get("src", "test", "resources", "files", fileName);
        if (!Files.exists(path)) {
            throw new IllegalStateException("Upload file not found: " + path.toAbsolutePath());
        }
        return path;
    }

    // Sends the file straight into the hidden <input type="file">, then waits (default 3 seconds)
    protected void uploadFile(String inputSelector, String fileName) {
        Locator input = page.locator(inputSelector);
        input.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
        input.evaluate("el => el.parentElement.scrollIntoView({block: 'center', behavior: 'instant'})");
        input.setInputFiles(uploadFilePath(fileName));

        double ms = Double.parseDouble(ConfigReader.getOrDefault("upload.wait.ms", "3000"));
        page.waitForTimeout(ms);
    }
}