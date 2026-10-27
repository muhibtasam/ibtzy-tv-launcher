package io.freemytv;

import android.app.Application;

/** Gives Prefs a context in every process. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // At power-on the home screen starts before the TV's storage unlocks (it is direct-boot aware): settings and
        // files can't be read then. HomeActivity calls ready() when it unlocks.
        android.os.UserManager um = getSystemService(android.os.UserManager.class);
        if (um != null && !um.isUserUnlocked()) {
            AppLog.w(String.format(java.util.Locale.ROOT, "startup: %-34s %7.2f s after power-on", "process started, storage locked",
                    android.os.SystemClock.elapsedRealtime() / 1000.0));
            return;
        }
        ready(this);
    }

    private static boolean ready;

    /** Settings (and the startup timeline) once the storage can be read; once per process. */
    static synchronized void ready(android.content.Context context) {
        if (ready) return;
        ready = true;
        Application c = (Application) context.getApplicationContext();
        long began = Startup.begin();
        Prefs.init(c);
        String name = android.os.Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : "";
        if (name.endsWith(":home")) {
            Startup.start(c);
            // When Android started this process (it may be well after power-on: that part isn't ours).
            Startup.line(String.format(java.util.Locale.ROOT, "startup: %-34s %7.2f s after power-on", "home process started",
                    android.os.Process.getStartElapsedRealtime() / 1000.0));
            Startup.took("Prefs.init", began);
            Startup.mark("App.onCreate done");
        }
    }
}
