package io.ibtzy.tvlauncher;

import android.util.Log;

/** The app's log (adb logcat -s ibtzyTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("ibtzyTV", message);
    }
}
