package io.ibtzy.tvlauncher;

import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

/**
 * The startup timeline, in the log: each step once per process, as seconds since the TV was switched on
 * (SystemClock.elapsedRealtime) and the time since the previous step. Read after a reboot:
 *   adb logcat -d -s ibtzyTV | grep startup
 * Steps before this process existed (firmware, boot animation, Android starting the home screen's process) show as the
 * gap between "power-on" (0 s) and "process started".
 */
final class Startup {
    private static final Set<String> logged = new HashSet<>();
    private static long last = -1;
    // Also kept in a file (a boot's log is so long the system drops these lines within a minute):
    //   adb shell cat /sdcard/Android/data/io.ibtzy.tvlauncher/files/startup.txt
    private static java.io.File file;
    private static final java.util.concurrent.ExecutorService writer =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** The home process starting: a new file for this start (the old one is replaced). */
    static synchronized void start(android.content.Context c) {
        try {
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            file = new java.io.File(dir, "startup.txt");
            gamesT0 = android.os.SystemClock.uptimeMillis();   // games-timing.txt counts from the app's start
            final java.io.File f = file;
            writer.execute(() -> f.delete());
        } catch (RuntimeException ignored) {
        }
    }

    private static void write(final String line) {
        final java.io.File f = file;
        if (f == null) return;
        writer.execute(() -> {
            try (java.io.FileWriter out = new java.io.FileWriter(f, true)) {
                out.write(line + "\n");
            } catch (java.io.IOException ignored) {
            }
        });
    }

    /**
     * Something worth reading later (titles opened or not): the log and titles.txt, kept to its last 200 lines.
     *   adb shell cat /sdcard/Android/data/io.ibtzy.tvlauncher/files/titles.txt
     */
    private static long gamesT0;
    private static boolean gamesStarted;

    /**
     * Gaming load timing (no keys: addresses are logged without their query):
     *   adb shell cat /sdcard/Android/data/io.ibtzy.tvlauncher/files/games-timing.txt
     */
    static synchronized void games(final String text) {
        final java.io.File f = file;
        if (f == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (gamesT0 == 0) gamesT0 = now;
        final java.io.File out = new java.io.File(f.getParentFile(), "games-timing.txt");
        final String line = String.format(java.util.Locale.ROOT, "%7d ms  %s  [%s]", now - gamesT0, text, Thread.currentThread().getName());
        final boolean first = !gamesStarted;
        gamesStarted = true;
        writer.execute(() -> {
            try (java.io.FileWriter w = new java.io.FileWriter(out, !first)) {
                w.write(line + "\n");
            } catch (java.io.IOException ignored) {
            }
        });
    }

    /** An address for the log: host and path, never the query (keys travel there). */
    static String noQuery(java.net.URL u) {
        String p = u.getPath();
        return u.getHost() + (p.length() > 70 ? p.substring(0, 70) + "…" : p);
    }

    static void event(final String text) {
        AppLog.w(text);
        final java.io.File f = file;
        if (f == null) return;
        final java.io.File events = new java.io.File(f.getParentFile(), "titles.txt");
        final String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + " " + text;
        writer.execute(() -> {
            java.util.List<String> lines = new java.util.ArrayList<>();
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader(events))) {
                for (String l; (l = in.readLine()) != null; ) lines.add(l);
            } catch (java.io.IOException ignored) {
            }
            lines.add(line);
            while (lines.size() > 200) lines.remove(0);
            try (java.io.FileWriter out = new java.io.FileWriter(events)) {
                for (String l : lines) out.write(l + "\n");
            } catch (java.io.IOException ignored) {
            }
        });
    }

    /** A line for the log and the file. */
    static void line(String text) {
        AppLog.w(text);
        write(text);
    }

    private Startup() {
    }

    /** Logs a step the first time it happens in this process. Main thread or not: cheap and synchronized. */
    static synchronized void mark(String step) {
        if (!logged.add(step)) return;
        long now = SystemClock.elapsedRealtime();
        line(String.format(java.util.Locale.ROOT, "startup: %-34s %7.2f s after power-on%s", step, now / 1000.0,
                last < 0 ? "" : " (+" + (now - last) + " ms)"));
        last = now;
    }

    /** Times one blocking step on the way to the first frame (logged when it took 20 ms or more). */
    static long begin() {
        return SystemClock.elapsedRealtime();
    }

    static void took(String step, long began) {
        long ms = SystemClock.elapsedRealtime() - began;
        if (ms >= 20) line("startup:   " + step + " took " + ms + " ms (main thread)");
    }
}
