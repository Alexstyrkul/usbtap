package dev.oleksandr.usbtap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "UsbTap";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "BootReceiver: " + intent.getAction());
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AppLauncher.launchAll(context);
        }
    }
}
