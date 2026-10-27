package io.freemytv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.os.UserManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    private BroadcastReceiver unlock;   // waiting for the storage to unlock (power-on), with the skeleton showing
    private long skeletonAt;            // when the skeleton went up (elapsedRealtime), for the startup timeline
    private boolean resumed;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserManager um = getSystemService(UserManager.class);
        if (um != null && !um.isUserUnlocked()) {
            // Power-on, the storage still locked: Android starts us now (direct-boot aware) instead of its black
            // placeholder. Only the layout's outline (nothing can be read yet); the real screen the moment it unlocks.
            skeletonAt = SystemClock.elapsedRealtime();
            setContentView(new BootSkeleton(this));
            prepareWhileLocked();
            unlock = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    unlocked();
                }
            };
            registerReceiver(unlock, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            if (um.isUserUnlocked()) unlocked();   // it unlocked in between
            return;
        }
        start();
    }

    private static volatile String prepared = "";

    /**
     * The seconds the storage takes to unlock, put to use: what the home screen needs that doesn't come from settings
     * or files. Fonts (from the app), the dock (its names and icons are in device storage), and the language
     * machinery's first use (dates, plurals). In the background: the skeleton keeps moving.
     */
    private void prepareWhileLocked() {
        final Context app = getApplicationContext();
        HomeApps.readDockAhead(app);
        Scene.buildAhead();   // the glass shaders compile meanwhile
        new Thread(() -> {
            long began = SystemClock.elapsedRealtime();
            try {
                Ui.init(app);
                android.icu.text.PluralRules.forLocale(java.util.Locale.getDefault());
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.getDefault()).format(new java.util.Date());
                java.text.NumberFormat.getInstance().format(1.5);
                prepared = "fonts, dates and plurals " + (SystemClock.elapsedRealtime() - began) + " ms";
            } catch (RuntimeException e) {
                prepared = "not prepared: " + e.getMessage();
            }
        }, "home-prepare").start();
    }

    /** The storage unlocked: settings, then the real home screen in place of the skeleton. */
    private void unlocked() {
        if (unlock == null) return;
        try {
            unregisterReceiver(unlock);
        } catch (IllegalArgumentException ignored) {
        }
        unlock = null;
        if (isFinishing() || isDestroyed()) return;
        // Android reads each settings file in its own background thread from the first request: all of them now, so
        // they load side by side while the main settings are read below (each read waits only for its own file).
        for (String file : new String[] {"homedata", "recs", "recs-history", "home-ui", "apps-history", "title-links-2"}) {
            getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        }
        App.ready(this);
        Startup.line(String.format(java.util.Locale.ROOT, "startup: %-34s %7.2f s after power-on", "boot skeleton shown (locked)",
                skeletonAt / 1000.0));
        Startup.mark("storage unlocked");
        if (!prepared.isEmpty()) Startup.line("startup:   prepared while locked: " + prepared);
        prefsVersion = Prefs.version;
        start();
        if (home != null) home.continueBootSkeleton();   // the same skeleton, then a fade to the screen
        if (resumed && home != null) home.resume();
    }

    /** The home screen itself (the storage can be read). */
    private void start() {
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Startup.mark("HomeActivity.onCreate");
        long began = Startup.begin();
        Ui.init(this);
        Startup.took("Ui.init (fonts, strings)", began);
        began = Startup.begin();
        home = new HomeView(this);
        Startup.took("new HomeView", began);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        Startup.mark("HomeActivity.onCreate done");
        home.postDelayed(this::rememberLook, 10_000);   // also when the TV is switched off without leaving Home
    }

    /** For the next power-on's skeleton: this screen's skeleton shapes (only written when they changed). */
    private void rememberLook() {
        if (home != null) BootSkeleton.remember(this, home.bootLayout());
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (home == null) return;   // the skeleton (storage locked): nothing to resume yet
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        resumed = false;
        if (home != null) {
            rememberLook();
            home.pause();
        }
        super.onPause();
    }

    /** Home button pressed: back to the entry state (Explore, focus on its tab; drawer and sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (unlock != null) {
            try {
                unregisterReceiver(unlock);
            } catch (IllegalArgumentException ignored) {
            }
            unlock = null;
        }
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (home != null && level >= TRIM_MEMORY_COMPLETE) {
            Recs.get(this).trim();
            home.trimCaches();
        }
    }

    /** The speech recognizer's answer (voice search in Apps). Comes before onResume. */
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == AppsVoice.REQUEST && home != null) home.onVoiceResult(resultCode, data);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (home == null) return super.dispatchKeyEvent(event);   // the skeleton: no settings to read yet
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
