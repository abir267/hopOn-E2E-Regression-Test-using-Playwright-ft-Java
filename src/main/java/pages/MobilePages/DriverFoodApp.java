package pages.MobilePages;

import utils.AdbDevice;
import utils.AdbDevice.UiNode;
import utils.ConfigReader;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The REAL driver Android app (com.hoponbd.driver) driven over adb for the food-delivery flow.
 *
 * The app is Jetpack Compose (no resource-ids), so every step is found by its visible text. The labels
 * below are not guessed - they come from the app's own string resources / FoodMainScreen.primaryActionLabel
 * (decompiled driver APK): the single primary button's text is chosen by the order's NEXT stage:
 *
 *   header "Preparing Order" (CONFIRMED live) / "Heading to Pickup"   button "Arrived at Restaurant"   (next stage = Arrived)
 *   header "Arrived at Restaurant" button "Confirm Pickup"          (next stage = PickedUp) -> sheet "Enter 4-digit OTP"
 *   header "On the Way"            button "At Delivery Location"    (next stage = Delivered) -> completion screen
 *   completion screen              photo proof -> "Complete Delivery" -> "Delivery Completed!" -> "Done"
 *
 * Requests arrive as: green banner "N request(s) available / Tap to view" -> list "N Requests" with a
 * "Details (15s)" row -> detail sheet with "Accept (4s)".
 *
 * Every wait polls one uiautomator dump at a time (~1s each) and every failure saves a screenshot + the
 * full text dump to target/driver-app-diagnostics/ so a changed screen is visible, not guessed at.
 */
public class DriverFoodApp {

    private static final Pattern ONLINE = Pattern.compile("(?i)you are currently online|^stop$");
    /** CONFIRMED live: a freshly launched app is offline - green "GO" button, "You are currently offline", service picker (Food must be ticked). */
    private static final Pattern GO_ONLINE = Pattern.compile("(?i)^(go|start|go online)$");

    private static final Pattern BANNER = Pattern.compile("(?i)\\d+\\s+requests?\\s+available|^tap to view$");
    private static final Pattern REQUEST_LIST_TITLE = Pattern.compile("(?i)^\\d+\\s+requests?$");
    private static final Pattern REQUEST_ROW = Pattern.compile("(?i)^details\\b");
    private static final Pattern ACCEPT = Pattern.compile("(?i)^accept\\b");

    /** The order screen right after Accept: CONFIRMED live titled "Preparing Order" (food still being made), "Heading to Pickup" once it is ready. */
    private static final Pattern ORDER_SCREEN = Pattern.compile("(?i)^(preparing order|heading to pickup)$");
    private static final Pattern ARRIVED_BUTTON = Pattern.compile("(?i)^arrived at restaurant$");
    private static final Pattern CONFIRM_PICKUP = Pattern.compile("(?i)^confirm pickup$");
    private static final Pattern OTP_TITLE = Pattern.compile("(?i)^enter \\d+-digit otp$");
    private static final Pattern ON_THE_WAY = Pattern.compile("(?i)^(on the way|at delivery location)$");
    private static final Pattern AT_DELIVERY_BUTTON = Pattern.compile("(?i)^at delivery location$");

    private static final Pattern PHOTO_TARGET = Pattern.compile("(?i)take a photo proof|delivery photo proof|photo proof");
    private static final Pattern COMPLETE_DELIVERY = Pattern.compile("(?i)^complete delivery$");
    private static final Pattern PERMISSION_ALLOW = Pattern.compile("(?i)^(while using the app|only this time|allow)$");

    private static final Pattern SHUTTER_ID = Pattern.compile("(?i)shutter|capture|take_?photo|camera_?btn");
    private static final Pattern CAMERA_CONFIRM_ID = Pattern.compile("(?i)(^|[/_])(done|ok|okay|confirm|save|apply|accept|btn_ok|ok_button)($|[_])");
    private static final Pattern CAMERA_CONFIRM_TEXT = Pattern.compile("(?i)^(done|ok|okay|confirm|save|use photo|apply|✓|✔)$");

    private static final Pattern MESSAGE_ICON = Pattern.compile("(?i)^message$");
    private static final Pattern CHAT_INPUT = Pattern.compile("(?i)^type a message");
    private static final Pattern CHAT_SEND = Pattern.compile("(?i)^send$");
    private static final Pattern CLOSE_SHEET = Pattern.compile("(?i)^close sheet$");

    private final AdbDevice device;
    private final String appPackage;

    public DriverFoodApp(AdbDevice device) {
        this.device = device;
        this.appPackage = ConfigReader.driverAppPackage();
    }

    // ------------------------------------------------------------------ session

    /** Brings the app to the front and makes sure the driver is online (the green "You are currently online" bar / red STOP). */
    public void openAndGoOnline() {
        device.requireReady();
        device.wakeAndClearScreen();
        AdbDevice.sleep(800);
        if (device.isKeyguardShowing()) {
            // CONFIRMED live: after the 10 min screen timeout the phone re-locks behind a secure lock screen, and the
            // wake + collapse above cannot get past a PIN/pattern. Fail with the actual fix, not "not on the home screen".
            throw new IllegalStateException("[DRIVER-APP] The phone is on its lock screen (secure lock) - unlock it and keep it "
                    + "awake, then rerun. Keeping it awake: Settings > Developer options > Stay awake (screen stays on while "
                    + "charging), or raise Settings > Display > Screen timeout.");
        }
        for (String scale : new String[] {"animator_duration_scale", "transition_animation_scale", "window_animation_scale"}) {
            String value = device.globalSetting(scale);
            if (value.matches("0(\\.0+)?")) {
                // CONFIRMED live: with animations off the driver app cancels every offer ~30 ms after the request list opens
                // (the card's countdown finishes instantly), so Accept can never be reached. Fail with the fix, not a timeout.
                utils.BugReport.environment("DRIVER_APP", "phone setting " + scale + "=0",
                        "Animations are off - the driver app cancels each offer the moment its list opens");
                throw new IllegalStateException("[DRIVER-APP] The phone's " + scale + " is 0 (animations off). The driver app cancels "
                        + "every request the instant its list opens when animations are off. Turn them on: Settings > Developer "
                        + "options > Window/Transition/Animator animation scale = 1x (or adb shell settings put global " + scale + " 1.0).");
            }
        }
        if (!device.hasPackage(appPackage)) {
            throw new IllegalStateException("Driver app " + appPackage + " is not installed on the phone "
                    + "(set driver.app.package if the build id differs).");
        }
        if (!appPackage.equals(device.foregroundPackage())) {
            device.launchApp(appPackage);
            log("Launched " + appPackage);
        }
        if (device.waitFor(ONLINE, 25_000).isPresent()) {
            log("Driver is online");
            return;
        }
        Optional<UiNode> start = AdbDevice.find(device.dump(), GO_ONLINE);
        if (start.isPresent()) {
            device.tap(start.get());
            log("Driver was offline - tapped GO");
            if (device.waitFor(ONLINE, 25_000).isPresent()) {
                log("Driver is online");
                return;
            }
        }
        throw fail("Driver app is not on the online home screen (no \"You are currently online\" / STOP, no GO button). "
                + "Log in and open the app once by hand, then rerun.");
    }

    // ------------------------------------------------------------------ accept

    /**
     * Banner -> request list -> detail sheet -> Accept, as a small state machine: one dump, at most one tap,
     * then dump again. The list/sheet are timed (Details ~15s, Accept ~4s) and the app drops a request from
     * them when it is taken or expires, so each state is acted on the moment it is seen. Done once the
     * order screen ("Preparing Order") is showing.
     */
    public void acceptRequest(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastTapAt = 0;
        String lastTapped = "";
        String lastSeen = "";
        long startedAt = System.currentTimeMillis();
        device.startRecording();
        while (System.currentTimeMillis() < deadline) {
            List<UiNode> screen = device.dump();
            if (screen.isEmpty()) {
                continue;
            }
            // One line per CHANGE of what is on screen, with the time - the timeline of the request's short life.
            String seen = summarize(screen);
            if (!seen.equals(lastSeen)) {
                lastSeen = seen;
                log("+" + (System.currentTimeMillis() - startedAt) / 1000.0 + "s screen: " + seen);
            }
            if (AdbDevice.find(screen, ORDER_SCREEN).isPresent()) {
                log("Request accepted - order screen showing, "
                        + (System.currentTimeMillis() - startedAt) / 1000 + "s after waiting began");
                device.stopRecordingAndSave("accepted");
                return;
            }
            String action = null;
            Optional<UiNode> target = Optional.empty();
            Optional<UiNode> accept = AdbDevice.find(screen, ACCEPT);
            Optional<UiNode> row = AdbDevice.find(screen, REQUEST_ROW);
            if (accept.isPresent()) {
                action = "detail sheet -> Accept";
                target = accept;
            } else if (row.isPresent() && AdbDevice.find(screen, REQUEST_LIST_TITLE).isPresent()) {
                action = "request list -> open request";
                target = row;
            } else if (AdbDevice.find(screen, BANNER).isPresent()) {
                action = "banner -> open list";
                target = AdbDevice.find(screen, BANNER);
            }
            if (target.isPresent()) {
                // Re-tapping the same state straight away just double-fires a button that is mid-transition.
                if (!action.equals(lastTapped) || System.currentTimeMillis() - lastTapAt > 2500) {
                    device.tap(target.get());
                    lastTapped = action;
                    lastTapAt = System.currentTimeMillis();
                    log("Tapped " + action + " (\"" + target.get().label() + "\") at +"
                            + (System.currentTimeMillis() - startedAt) / 1000.0 + "s");
                }
            }
        }
        String film = device.stopRecordingAndSave("accept-failed");
        throw fail("No request could be accepted on the phone within " + timeoutMs / 1000 + "s. Last tapped: "
                + (lastTapped.isEmpty() ? "nothing (no banner ever appeared - is the driver online and near the restaurant?)" : lastTapped)
                + (film == null ? "" : " Film: " + film + "."));
    }

    /** The first few texts on screen, enough to tell banner / list / sheet / home apart in a log line. */
    private static String summarize(List<UiNode> screen) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (UiNode n : screen) {
            String label = n.label();
            if (!label.isEmpty() && !label.startsWith("Google") && shown < 8) {
                sb.append(shown++ == 0 ? "" : " | ").append(label);
            }
        }
        return sb.length() == 0 ? "(no text)" : sb.toString();
    }

    // ------------------------------------------------------------------ pickup

    /** "Arrived at Restaurant" button (header still "Preparing Order" / "Heading to Pickup") -> button changes to "Confirm Pickup". */
    public void markArrivedAtRestaurant() {
        tapUntil(ARRIVED_BUTTON, CONFIRM_PICKUP, 30_000, "Arrived at Restaurant");
        log("Marked Arrived at Restaurant");
    }

    /** "Confirm Pickup" -> OTP sheet -> types the restaurant's PIN -> on to "On the Way". */
    public void confirmPickup(String pin) {
        tapUntil(CONFIRM_PICKUP, OTP_TITLE, 30_000, "Confirm Pickup");
        log("OTP sheet open, entering PIN " + pin);
        device.typeText(pin);
        // CONFIRMED live: the sheet submits by itself once the 4th digit is in. When that request is not accepted the sheet
        // just stays, with no message - seen once as "503 no available server" from the dev gateway on
        // POST /services/food/api/food-orders/driver/acknowledge, with the right PIN. ONE submit only: no automatic second
        // try (user instruction 2026-11-16) - the error is printed and the test stops.
        if (device.waitFor(ON_THE_WAY, 25_000).isEmpty()) {
            throw fail("PIN " + pin + " was typed (the sheet submits itself on the 4th digit) but the app never moved to \"On the Way\". "
                    + "Look for a [BUG][DRIVER_APP] line above: a 4xx/5xx answer from POST .../food-orders/driver/acknowledge "
                    + "leaves the sheet open with no message; otherwise the PIN is wrong.");
        }
        log("Pickup confirmed - on the way to the customer");
    }

    // ------------------------------------------------------------------ delivery

    /**
     * "At Delivery Location" -> completion screen -> "Take a Photo Proof" (system camera) -> "Proof of delivery" sheet
     * -> "Complete Delivery" -> back on the online home screen. All CONFIRMED live (2026-11-10).
     *
     * Note the completion screen's TITLE reads "Delivery Completed!" while the order is still Delivering on the
     * server (photo proof not yet submitted) - so that text is NOT a done signal. Done is: Complete Delivery was
     * tapped and the app is back on the online home screen. Screen-driven loop, because cash vs online payment
     * changes which extra step may appear in between.
     */
    public void completeDelivery(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean photoTaken = false;
        boolean completeTapped = false;
        String lastTapped = "";
        long lastTapAt = 0;
        while (System.currentTimeMillis() < deadline) {
            String foreground = device.foregroundPackage();
            if (!foreground.isEmpty() && !foreground.equals(appPackage)) {
                // Focus left the driver app mid-photo-step: the phone's camera (or its permission dialog).
                if (captureWithSystemCamera(foreground)) {
                    photoTaken = true;
                }
                continue;
            }
            List<UiNode> screen = device.dump();
            if (screen.isEmpty()) {
                continue;
            }
            if (completeTapped && AdbDevice.find(screen, ONLINE).isPresent()) {
                log("Delivery completed - app is back on the online home screen");
                return;
            }
            Optional<UiNode> allow = AdbDevice.find(screen, PERMISSION_ALLOW);
            Optional<UiNode> atDelivery = AdbDevice.find(screen, AT_DELIVERY_BUTTON);
            Optional<UiNode> photo = AdbDevice.find(screen, PHOTO_TARGET);
            Optional<UiNode> complete = AdbDevice.find(screen, COMPLETE_DELIVERY);
            String action;
            Optional<UiNode> target;
            if (allow.isPresent()) {
                action = "permission dialog";
                target = allow;
            } else if (!photoTaken && photo.isPresent()) {
                action = "photo proof";
                target = photo;
            } else if (photoTaken && complete.isPresent()) {
                action = "Complete Delivery";
                target = complete;
            } else if (atDelivery.isPresent()) {
                action = "At Delivery Location";
                target = atDelivery;
            } else {
                continue;
            }
            if (!action.equals(lastTapped) || System.currentTimeMillis() - lastTapAt > 3500) {
                device.tap(target.get());
                lastTapped = action;
                lastTapAt = System.currentTimeMillis();
                completeTapped |= action.equals("Complete Delivery");
                log("Tapped " + action + " (\"" + target.get().label() + "\")");
            }
        }
        throw fail("Delivery was not completed on the phone within " + timeoutMs / 1000 + "s. Last tapped: " + lastTapped);
    }

    /**
     * The driver app's TakePicture contract opens the phone's own camera app. Shutter is found by id/text
     * (OEM-specific, so searched not hard-coded), then the keycode CAMERA as fallback; then the OEM's confirm
     * tick. Returns true once focus is back in the driver app.
     */
    private boolean captureWithSystemCamera(String foregroundPackage) {
        AdbDevice.sleep(1500);
        if (foregroundPackage.contains("permissioncontroller")) {
            // First-run CAMERA permission dialog: allow it and return - the app then launches the camera
            // itself, which the caller's loop sees as the next foreground change.
            AdbDevice.find(device.dump(), PERMISSION_ALLOW).ifPresent(device::tap);
            log("Camera permission dialog - allowed");
            return false;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            List<UiNode> screen = device.dump();
            Optional<UiNode> shutter = screen.stream().filter(n -> SHUTTER_ID.matcher(n.resourceId()).find()).findFirst();
            if (shutter.isPresent()) {
                device.tap(shutter.get());
                log("Camera: tapped shutter (" + shutter.get().resourceId() + ")");
            } else {
                device.keyEvent(27); // KEYCODE_CAMERA
                log("Camera: no shutter id found, sent KEYCODE_CAMERA");
            }
            AdbDevice.sleep(2500);
            List<UiNode> after = device.dump();
            Optional<UiNode> confirm = after.stream()
                    .filter(n -> CAMERA_CONFIRM_ID.matcher(n.resourceId()).find() || (n.clickable() && CAMERA_CONFIRM_TEXT.matcher(n.label()).find()))
                    .findFirst();
            if (confirm.isPresent()) {
                device.tap(confirm.get());
                log("Camera: confirmed photo (" + confirm.get().resourceId() + " \"" + confirm.get().label() + "\")");
            }
            long backBy = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < backBy) {
                if (appPackage.equals(device.foregroundPackage())) {
                    return true;
                }
                AdbDevice.sleep(700);
            }
        }
        throw fail("Camera app never returned to the driver app after the shutter + confirm taps (see the diagnostics screenshot for the OEM camera layout).");
    }

    // ------------------------------------------------------------------ chat with the customer

    /**
     * The order screen's "Message" icon (content-desc "Message", next to the customer's phone number) opens the chat
     * bottom sheet: messages as text, an input with the hint "Type a message...", a send icon described "Send", and
     * quick replies ("I am coming...", "Wait for few minutes", "I am at pickup point") - from the decompiled chat module.
     */
    public void openChat() {
        tapUntil(MESSAGE_ICON, CHAT_INPUT, 20_000, "Message icon");
        log("Chat sheet open");
    }

    /** Types the text into the chat input and taps Send; returns once the message shows in the conversation. */
    public void sendChat(String text) {
        String typed = adbSafe(text);
        List<UiNode> screen = device.dump();
        UiNode input = AdbDevice.find(screen, CHAT_INPUT)
                .orElseThrow(() -> fail("Chat input (\"Type a message...\") not found - is the chat sheet open?"));
        device.tap(input);
        AdbDevice.sleep(400);
        device.typeText(typed);
        AdbDevice.sleep(400);
        UiNode send = AdbDevice.find(device.dump(), CHAT_SEND)
                .orElseThrow(() -> fail("Chat Send button not found after typing \"" + text + "\""));
        device.tap(send);
        if (!chatShows(text, 15_000)) {
            throw fail("Sent \"" + text + "\" from the phone but it never appeared in the chat");
        }
        log("Chat sent: \"" + text + "\"");
    }

    /** Taps one of the chat's quick-reply chips by its label. */
    public void sendQuickReply(String label) {
        Pattern chip = Pattern.compile("(?i)^" + Pattern.quote(label) + "$");
        UiNode node = device.waitFor(chip, 10_000)
                .orElseThrow(() -> fail("Quick reply \"" + label + "\" not found in the chat sheet"));
        device.tap(node);
        if (!chatShows(label, 15_000)) {
            throw fail("Quick reply \"" + label + "\" was tapped but never appeared in the chat");
        }
        log("Chat quick reply sent: \"" + label + "\"");
    }

    /** True once a message with this text is on the phone's chat screen (e.g. what the customer sent from the web). */
    public boolean chatShows(String text, long timeoutMs) {
        Pattern message = Pattern.compile(Pattern.quote(text));
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            for (UiNode n : device.dump()) {
                // the message bubble, not the (now emptied) input field
                if (!CHAT_INPUT.matcher(n.label()).find() && n.matches(message)) {
                    return true;
                }
            }
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    /**
     * Dismisses the chat sheet. CONFIRMED live, two separate things:
     *  - after typing, the keyboard covers the sheet's "Close sheet" strip (bottom edge), and KEYCODE_BACK only hides
     *    the keyboard - it does NOT close the sheet (the app has no back callback for it);
     *  - so: BACK once to drop the keyboard, then tap "Close sheet".
     */
    public void closeChat() {
        device.keyEvent(4);
        AdbDevice.sleep(600);
        UiNode close = device.waitFor(CLOSE_SHEET, 10_000)
                .orElseThrow(() -> fail("The chat sheet has no \"Close sheet\" control on screen (keyboard still up?)"));
        device.tap(close);
        if (device.waitFor(MESSAGE_ICON, 10_000).isEmpty()) {
            throw fail("Tapped \"Close sheet\" but the order screen did not come back");
        }
        log("Chat sheet closed");
    }

    /** `adb shell input text` cannot take every character: spaces become %s and anything outside a safe set is dropped. */
    private static String adbSafe(String text) {
        return text.replace(" ", "%s").replaceAll("[^A-Za-z0-9%.,!?'-]", "");
    }

    // ------------------------------------------------------------------ helpers

    /** Taps the button, re-tapping every ~4s, until the next screen's marker text appears. */
    private void tapUntil(Pattern button, Pattern nextMarker, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastTapAt = 0;
        while (System.currentTimeMillis() < deadline) {
            List<UiNode> screen = device.dump();
            if (AdbDevice.find(screen, nextMarker).isPresent()) {
                return;
            }
            Optional<UiNode> hit = AdbDevice.find(screen, button);
            if (hit.isPresent() && System.currentTimeMillis() - lastTapAt > 4000) {
                device.tap(hit.get());
                lastTapAt = System.currentTimeMillis();
            }
        }
        throw fail("Tapped \"" + what + "\" but the next screen never appeared within " + timeoutMs / 1000 + "s");
    }

    private IllegalStateException fail(String message) {
        return new IllegalStateException("[DRIVER-APP] " + message + " Diagnostics: " + device.saveDiagnostics("failure"));
    }

    private static void log(String message) {
        System.out.println("[DRIVER-APP] " + message);
    }
}
