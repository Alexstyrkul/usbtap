package dev.oleksandr.usbtap;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/** Brings up the always-on apps this phone runs unattended, and dims the screen - run once
 *  on boot by BootReceiver. */
final class AppLauncher {
    private static final String TAG = "UsbTap";

    private AppLauncher() {
    }

    static void launchAll(Context context) {
        launch(context, "com.tailscale.ipn", 0);
        launch(context, "com.fox3d.controller", 0);
        launch(context, "com.ivuu", Intent.FLAG_ACTIVITY_CLEAR_TOP);
        setMinBrightness(context);
    }

    private static void launch(Context context, String packageName, int extraFlags) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) {
                Log.d(TAG, "No launch intent for " + packageName);
                return;
            }
            intent.addFlags(extraFlags | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d(TAG, "Launched " + packageName);
        } catch (Throwable t) {
            Log.d(TAG, "Failed to launch " + packageName + ": " + t);
        }
    }

    private static void setMinBrightness(Context context) {
        try {
            ContentResolver resolver = context.getContentResolver();
            if (!Settings.System.canWrite(context)) {
                Log.d(TAG, "No WRITE_SETTINGS access, cannot set brightness");
                return;
            }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 1);
            Log.d(TAG, "Brightness set to minimum");
        } catch (Throwable t) {
            Log.d(TAG, "Failed to set brightness: " + t);
        }
    }
}
