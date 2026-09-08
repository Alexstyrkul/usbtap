# usbtap

Small Android accessibility service that auto-confirms the system's "Allow
this app to access the USB device?" permission dialog. Companion app to
[PrintHost](https://github.com/Alexstyrkul/printhost) - a phone running
PrintHost as an unattended 3D-printer server can't tap "Allow" itself every
time the printer is (re)connected, so this taps it for it.

It also exposes a broadcast-triggered screen lock
(`dev.oleksandr.usbtap.ACTION_LOCK_SCREEN`), which is what PrintHost's
dashboard "Lock" button calls - locking the screen isn't something a regular
app can do on its own, but an accessibility service can.

usbtap doesn't do anything else: no camera, no printer communication, no
network access beyond a local loopback probe used to tell one specific
third-party app's window apart from the system permission dialog.

## Setup

1. `./build.sh` (needs the Android SDK build-tools and `ANDROID_HOME` set;
   see the script for the exact tool versions it expects)
2. `adb install -r out/signed.apk`
3. **On Android 13+**, the toggle in step 4 is greyed out at first - this is
   Android blocking sideloaded (non-Play-Store) apps from enabling an
   accessibility service by default. Go to Settings → Apps → usbtap → the
   ⋮ menu (top right) → "Allow restricted settings" first.
4. Enable it under Settings → Accessibility → usbtap (it does nothing until
   this is turned on - Android accessibility services can't self-enable)
5. If you also want the screen-lock button on PrintHost's dashboard to work,
   no extra setup is needed - it works automatically once usbtap is enabled

## Why an accessibility service, and not a standard permission grant

Android always re-prompts for USB permission unless the app is granted it
with elevated privileges usbtap doesn't have (or unless you re-grant it by
hand over adb after every reconnect). Tapping the dialog is the only way to
handle this from an ordinary, unprivileged app.

## What this is not

A general-purpose dialog-clicking bot. It only recognizes and taps the
specific USB permission dialog (matched by its actual button text - not a
blind "tap whatever's on screen"), plus one other app's window it's tuned to
recognize. It won't do anything useful outside that.

## Language support

Button-text matching only recognizes the words actually seen on a real
device so far: English ("Allow"/"OK"), Russian ("разрешить"), and Ukrainian
("дозволити"/"надати"/"відкрити"). On a phone set to any other language, the
dialog won't be recognized and won't get tapped. To add a language, add its
words to `OK_TEXTS` and `ALLOW_WORDS` near the top of
[`UsbAllowService.java`](src/dev/oleksandr/usbtap/UsbAllowService.java).

## License

MIT - see [LICENSE](LICENSE).
