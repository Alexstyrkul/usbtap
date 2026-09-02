package dev.oleksandr.usbtap;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto-taps two known system dialogs that would otherwise sit waiting for a manual tap on a
 * phone that's meant to run unattended: the USB device permission prompt (for PrintHost and
 * anything else that opens a serial connection), and Fox3D's "start web server" button.
 */
public class UsbAllowService extends AccessibilityService {

    private static final String TAG = "UsbTap";
    private static final String FOX3D_PACKAGE = "com.fox3d.controller";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String PRINTHOST_PACKAGE = "dev.oleksandr.printhost";
    private static final int FOX3D_PORT = 2525;

    // Device locale is uk-UA (confirmed via `adb shell getprop persist.sys.locale`), with
    // en-US/ru as secondary - so the real dialog's button and prompt text render in Ukrainian.
    // "OK" itself is commonly left untranslated across locales, hence "ok"/"ок" still cover it.
    // Confirmed on real hardware (full node dump via logcat) that TWO distinct SystemUI dialogs
    // can appear back to back for one USB attach: the permission prompt itself - 'Надати додатку
    // PrintHost доступ до такого аксесуара: USB Serial?' - and, right after granting it, a
    // second "open the app for this accessory" confirmation - 'Відкрити додаток PrintHost, щоб
    // використовувати такий аксесуар: USB Serial?'. Both use an "OK"/"СКАСУВАТИ" button pair but
    // different verbs in the prompt sentence ("надати"="grant", "відкрити"="open") - neither is
    // "дозволити" (used by other, unrelated Android permission dialogs, kept here regardless in
    // case a future dialog variant does use it).
    private static final String[] OK_TEXTS = {"ok", "ок", "allow", "разрешить", "дозволити"};
    private static final String[] CANCEL_TEXTS = {"cancel", "отмена", "скасувати"};
    private static final String[] ALLOW_WORDS = {"allow", "разрешить", "дозволити", "надати", "відкрити"};

    // Not a "how long to stay awake" timer - see holdScreenAwake()/releaseScreenHold() for the
    // actual state-based approach this replaced. This is only a safety net against something
    // going permanently wrong (a node match that never fires, an exception, or the user simply
    // never acting at all) holding the screen on forever and draining the battery.
    private static final long WAKE_LOCK_SAFETY_CEILING_MS = 5 * 60_000;

    private boolean fox3dWebServerHandled = false;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        boolean isWindowEvent = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        boolean isActivityEvent = type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START;
        if (!isWindowEvent && !isActivityEvent) return;

        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;

        // State-based, not timer-based: hold the screen awake for as long as the whole "printer
        // just got physically attached, wait for a human to notice and press Connect whenever
        // they get to it, then tap whatever dialog shows up" flow is still going, not for a
        // fixed guess at how long that takes - it can't be guessed, since it's entirely up to
        // the human. PrintHost's own MainActivity showing (the native, Android-triggered result
        // of physically attaching the printer - not something this service starts) and any
        // SystemUI window/activity both count as "still in progress". Once already holding for
        // this flow, activity in ANY other app (e.g. the user switched over to Settings) also
        // counts as "still around" rather than "walked away" - only releaseScreenHold(), called
        // after successfully tapping a real dialog, lets it go from here.
        boolean relevantPackage = SYSTEMUI_PACKAGE.contentEquals(packageName)
                || PRINTHOST_PACKAGE.contentEquals(packageName);
        boolean alreadyHolding = wakeLock != null && wakeLock.isHeld();
        if (relevantPackage || alreadyHolding) {
            // The lock screen keeps generating its own CONTENT_CHANGED events while the display
            // is off - its clock ticking over a minute, the battery percentage updating while
            // charging - confirmed on real hardware as a real cause of the screen waking itself
            // for no reason. A brand-new window (the actual dialog, or MainActivity appearing)
            // always arrives as STATE_CHANGED; skip waking for a mere CONTENT_CHANGED update
            // while still asleep, since it's never the thing worth waking up for.
            if (!(isAsleep() && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
                holdScreenAwake();
            }
        }

        if (!isWindowEvent) return; // the dialog-detection logic below only cares about windows
        if (isAsleep()) return; // can't reliably read a window's content while the display is off

        Log.d(TAG, "onAccessibilityEvent type=" + type + " pkg=" + packageName
                + " windowId=" + event.getWindowId());

        if (FOX3D_PACKAGE.contentEquals(packageName) && !fox3dWebServerHandled) {
            handleFox3dWindow();
            return;
        }

        if (SYSTEMUI_PACKAGE.contentEquals(packageName)) {
            handleSystemUiWindow(event.getWindowId());
        }
    }

    /**
     * SystemUI owns both the USB permission dialog AND the notification shade / quick settings
     * panel - any event from either arrives with the same package name, so the dialog has to be
     * told apart from the shade by its actual content, not just by which process posted it.
     *
     * Confirmed as a real bug on real hardware: the old version of this method treated ANY node
     * anywhere in the current SystemUI window containing the substring "USB" as "the permission
     * dialog is showing" - which also matches an ordinary "charging via USB" notification/tile in
     * the shade. When that fired, it looked for a button labelled "OK"/"Allow"/"Разрешить" and,
     * if none matched (which it never does in the shade), blindly clicked the LAST clickable
     * button found anywhere in the window - which turned out to be the quick-settings gear icon,
     * silently launching Settings and closing the shade every time it was pulled down while a
     * USB-related notification was showing.
     *
     * Fix: require the "USB" text to appear in the SAME node as an "Allow"/"Разрешить" word -
     * that's the actual sentence shape of Android's real permission prompt ("Allow X to access
     * USB Serial?" / its Russian equivalent), which nothing in the shade happens to say. And
     * never fall back to guessing a button - if a real prompt was detected but no button
     * explicitly matches OK_TEXTS, do nothing rather than clicking something unknown.
     */
    private void handleSystemUiWindow(int windowId) {
        // Waking for this window (if it was needed) and holding the screen for it already
        // happened in onAccessibilityEvent before this method was even called - this method only
        // has to worry about reading and tapping the actual dialog content.
        AccessibilityNodeInfo root = resolveRoot(windowId);
        if (root == null) {
            Log.d(TAG, "handleSystemUiWindow: no root found for windowId=" + windowId);
            return;
        }
        try {
            List<AccessibilityNodeInfo> textNodes = new ArrayList<>();
            List<AccessibilityNodeInfo> buttonNodes = new ArrayList<>();
            collect(root, textNodes, buttonNodes);

            for (AccessibilityNodeInfo n : textNodes) {
                Log.d(TAG, "textNode: [" + n.getClassName() + "] \"" + n.getText() + "\"");
            }
            for (AccessibilityNodeInfo n : buttonNodes) {
                Log.d(TAG, "buttonNode: [" + n.getClassName() + "] \"" + n.getText() + "\" id="
                        + n.getViewIdResourceName());
            }

            if (!looksLikeUsbPermissionPrompt(textNodes) || buttonNodes.isEmpty()) {
                Log.d(TAG, "not a recognized USB prompt (looksLike=" + looksLikeUsbPermissionPrompt(textNodes)
                        + " buttons=" + buttonNodes.size() + ")");
                return;
            }

            AccessibilityNodeInfo okButton = null;
            for (AccessibilityNodeInfo node : buttonNodes) {
                if (matches(node.getText(), OK_TEXTS) && !matches(node.getText(), CANCEL_TEXTS)) {
                    okButton = node;
                    break;
                }
            }
            if (okButton == null) {
                Log.d(TAG, "USB permission prompt detected but no OK/Allow button matched - not guessing");
                return;
            }
            Log.d(TAG, "Clicking USB permission OK button: " + okButton.getText());
            okButton.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            // Job done for this dialog - let go. If a follow-up dialog (like the "open the app"
            // confirmation that shows up ~2.3s after the permission prompt) is still coming, its
            // own STATE_CHANGED window event re-acquires the hold on its own; nothing is missed
            // by releasing eagerly here rather than waiting to see if one shows up.
            releaseScreenHold();
        } finally {
            root.recycle();
        }
    }

    /**
     * State-based screen hold for the whole attach-to-tap flow - see onAccessibilityEvent's
     * comment for why this replaced an earlier fixed-timer approach. Idempotent: safe to call
     * repeatedly as new relevant events keep arriving, each call simply refreshes the safety
     * ceiling rather than stacking additional wake time.
     */
    @SuppressWarnings("deprecation") // SCREEN_BRIGHT_WAKE_LOCK has no non-deprecated replacement
    private void holdScreenAwake() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) return;
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "UsbTap:usbFlow");
        }
        if (wakeLock.isHeld()) {
            wakeLock.release(); // acquire(timeout) doesn't refresh an already-held lock's timer
        } else {
            Log.d(TAG, "Holding screen awake for the attach-to-tap flow");
        }
        wakeLock.acquire(WAKE_LOCK_SAFETY_CEILING_MS);
    }

    /** Lets go once the actual job (tapping a real dialog) is done - Android's own normal screen
     *  timeout governs from here, not an artificial window picked by us. */
    private void releaseScreenHold() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "Released screen hold - dialog handled");
        }
    }

    private boolean isAsleep() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return powerManager != null && !powerManager.isInteractive();
    }

    /**
     * getRootInActiveWindow() can legitimately return null right as a brand-new window (like
     * SystemUI's UsbPermissionActivity) first appears - "active window" tracking can lag one
     * frame behind the WINDOW_STATE_CHANGED event that just fired for it. Confirmed on real
     * hardware: the USB permission prompt reliably failed to get tapped with zero log output,
     * meaning this early-return was silently eating every attempt. Falling back to the specific
     * window named by the event itself (via getWindows(), matched by ID) finds the same content
     * without depending on "active window" tracking having caught up yet.
     */
    private AccessibilityNodeInfo resolveRoot(int windowId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) return root;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getId() == windowId) {
                AccessibilityNodeInfo fallbackRoot = window.getRoot();
                if (fallbackRoot != null) {
                    Log.d(TAG, "resolveRoot: getRootInActiveWindow() was null, used getWindows() fallback");
                    return fallbackRoot;
                }
            }
        }
        return null;
    }

    /** True only for a node whose own text names both "usb" and an allow/разрешить word
     *  together - the shape of the real prompt sentence, not just "USB" appearing somewhere. */
    private boolean looksLikeUsbPermissionPrompt(List<AccessibilityNodeInfo> textNodes) {
        for (AccessibilityNodeInfo node : textNodes) {
            CharSequence text = node.getText();
            if (text == null) continue;
            String lower = text.toString().toLowerCase(Locale.ROOT);
            if (lower.contains("usb") && containsAny(lower, ALLOW_WORDS)) {
                return true;
            }
        }
        return false;
    }

    private void handleFox3dWindow() {
        fox3dWebServerHandled = true;
        new Fox3dCheckThread(this).start();
    }

    void handleFox3dWindowBackground() {
        if (isPortOpen(FOX3D_PORT)) {
            Log.d(TAG, "Fox3D web server already listening on 2525, not touching WS button");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.d(TAG, "Fox3D: root is null, cannot find WS button");
            return;
        }
        try {
            AccessibilityNodeInfo wsButton = findByResourceId(root, "com.fox3d.controller:id/action_webserver");
            if (wsButton == null) {
                Log.d(TAG, "Fox3D: WS button not found in current window");
            } else {
                Log.d(TAG, "Fox3D: web server not listening, clicking WS button");
                wsButton.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } finally {
            root.recycle();
        }
    }

    private boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private AccessibilityNodeInfo findByResourceId(AccessibilityNodeInfo node, String resourceId) {
        if (node == null) return null;
        if (resourceId.equals(node.getViewIdResourceName())) return node;
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo found = findByResourceId(node.getChild(i), resourceId);
            if (found != null) return found;
        }
        return null;
    }

    private boolean matches(CharSequence text, String[] candidates) {
        if (text == null) return false;
        String trimmed = text.toString().trim().toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (trimmed.equals(candidate)) return true;
        }
        return false;
    }

    private boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    /** Walks the tree once, bucketing every node with visible text and every clickable
     *  Button-class node - the two shapes handleSystemUiWindow() needs to look at. */
    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> textNodes,
                          List<AccessibilityNodeInfo> buttonNodes) {
        if (node == null) return;
        CharSequence className = node.getClassName();
        if (node.getText() != null && node.getText().length() > 0) {
            textNodes.add(node);
        }
        if (className != null && className.toString().contains("Button") && node.isClickable()) {
            buttonNodes.add(node);
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            collect(node.getChild(i), textNodes, buttonNodes);
        }
    }
}
