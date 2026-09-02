package dev.oleksandr.usbtap;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

    // Debounce for telling "the screen has genuinely been asleep for a while, a new dialog
    // appearing is worth waking for" apart from "the screen JUST turned off because the user
    // pressed the power button to lock it". Confirmed as a real bug on real hardware: pressing
    // power to lock turns the display off essentially synchronously, but the SystemUI window
    // event for the resulting keyguard becoming the active window arrives at this listener
    // afterward - by then isInteractive() already reports false, making that event
    // indistinguishable from "a real dialog just appeared after a long idle" by event data alone.
    // Requiring the screen to have been off for at least this long already closes that race
    // without needing to read window content first (which isn't reliably possible while asleep
    // anyway - see wakeScreenIfAsleep()'s own comment).
    //
    // A first attempt tracked this off of accessibility events themselves (updating a "last seen
    // interactive" timestamp whenever one arrived while the screen was on) - confirmed on real
    // hardware to be unreliable: with no events at all during a quiet stretch of the screen just
    // sitting on, that timestamp went stale past the debounce window on its own, so the very next
    // event - even a totally ordinary one, nothing to do with locking - read as "screen has been
    // off a while" the instant the user actually turned it off. ACTION_SCREEN_OFF/_ON are the
    // real, immediate system signal for this and don't have that gap.
    private static final long GENUINE_SLEEP_DEBOUNCE_MS = 800;
    private volatile long screenTurnedOffAt = 0; // 0 = currently on (or not yet known off)

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenTurnedOffAt = System.currentTimeMillis();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                screenTurnedOffAt = 0;
            }
        }
    };

    private boolean fox3dWebServerHandled = false;

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenStateReceiver, filter);
        // If the service is (re)starting while the screen already happens to be off (e.g. an
        // app update while asleep), there was no ACTION_SCREEN_OFF to catch - treat it as
        // "already been off a while" (1, not System.currentTimeMillis(), so the debounce math
        // below is trivially satisfied) rather than defaulting to "just turned off".
        screenTurnedOffAt = isAsleep() ? 1L : 0L;
        Log.d(TAG, "Service connected");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (IllegalArgumentException e) {
            // wasn't registered - nothing to do
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;

        // Confirmed as a real bug on real hardware, through two different designs before this
        // one: holding a screen-type wake lock across the whole attach-to-tap flow - whether or
        // not it carried ACQUIRE_CAUSES_WAKEUP - blocks the physical power button from locking
        // the phone at all for as long as it's held. The user was explicit that being able to
        // lock the phone manually at any moment is a hard requirement, so this doesn't hold
        // anything: it only ever triggers a brief, one-shot wake-up transition when a genuinely
        // new relevant window (PrintHost's MainActivity - the native, Android-triggered result of
        // physically attaching the printer, not something this service starts - or a SystemUI
        // dialog) appears while the screen is asleep. From that wake onward, Android's own normal
        // screen timeout governs, same as if the user had pressed the power button themselves -
        // and pressing it to lock always works, since nothing here is ever held.
        boolean asleep = isAsleep();
        long offAt = screenTurnedOffAt;
        boolean genuinelyAsleep = asleep
                && offAt != 0 && System.currentTimeMillis() - offAt > GENUINE_SLEEP_DEBOUNCE_MS;

        boolean relevantPackage = SYSTEMUI_PACKAGE.contentEquals(packageName)
                || PRINTHOST_PACKAGE.contentEquals(packageName);
        if (relevantPackage && genuinelyAsleep && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            wakeScreenIfAsleep();
        }

        if (asleep) return; // can't reliably read a window's content while the display is off

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
        // Waking for this window (if it was needed) already happened in onAccessibilityEvent
        // before this method was even called - this method only has to worry about reading and
        // tapping the actual dialog content.
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
        } finally {
            root.recycle();
        }
    }

    /**
     * A brief, one-shot wake-up transition - nothing is held afterward. Two earlier designs both
     * held a screen-type wake lock across the whole attach-to-tap flow (first with a fixed
     * stay-awake timer, then state-based, released only after a successful dialog tap); both were
     * confirmed on real hardware to block the physical power button from locking the phone at all
     * for as long as anything was held, regardless of whether the lock itself carried
     * ACQUIRE_CAUSES_WAKEUP. Since being able to lock the phone manually at any moment is a hard
     * requirement, this holds nothing: from the moment of this wake, Android's own normal screen
     * timeout governs, exactly as if the user had pressed the power button themselves - which
     * means pressing it again to lock always works too, since there's nothing left to fight it.
     */
    @SuppressWarnings("deprecation") // SCREEN_BRIGHT_WAKE_LOCK has no non-deprecated replacement
    private void wakeScreenIfAsleep() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) return;
        PowerManager.WakeLock oneShot = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "UsbTap:wakeupTrigger");
        oneShot.acquire(1000);
        oneShot.release();
        Log.d(TAG, "Woke the screen for a relevant window appearing");
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
