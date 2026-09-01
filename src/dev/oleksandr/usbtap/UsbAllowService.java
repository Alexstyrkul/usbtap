package dev.oleksandr.usbtap;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
    private static final int FOX3D_PORT = 2525;

    // Device locale is uk-UA (confirmed via `adb shell getprop persist.sys.locale`), with
    // en-US/ru as secondary - so the real dialog's button and prompt text render in Ukrainian.
    // "OK" itself is commonly left untranslated across locales, hence "ok"/"ок" still cover it.
    private static final String[] OK_TEXTS = {"ok", "ок", "allow", "разрешить", "дозволити"};
    private static final String[] CANCEL_TEXTS = {"cancel", "отмена", "скасувати"};
    private static final String[] ALLOW_WORDS = {"allow", "разрешить", "дозволити"};

    private boolean fox3dWebServerHandled = false;

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
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;

        if (FOX3D_PACKAGE.contentEquals(packageName) && !fox3dWebServerHandled) {
            handleFox3dWindow();
            return;
        }

        if (SYSTEMUI_PACKAGE.contentEquals(packageName)) {
            handleSystemUiWindow();
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
    private void handleSystemUiWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<AccessibilityNodeInfo> textNodes = new ArrayList<>();
            List<AccessibilityNodeInfo> buttonNodes = new ArrayList<>();
            collect(root, textNodes, buttonNodes);

            if (!looksLikeUsbPermissionPrompt(textNodes) || buttonNodes.isEmpty()) {
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
