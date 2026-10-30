package io.ibtzy.tvlauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What shows up on the home screen and in the "All Apps" sheet, and how to open each thing.
 *
 * On the home shelf, each app is its colored logo on a dark card: the official logo in assets/logos/<key>.png
 * (downloaded from Wikimedia Commons by tools/logos.py) or, without one, the logo lifted from the app's TV art (the
 * Android TV "banner"), saved to disk after the first time. In "All Apps", 16:9 cards with the whole TV art (or the
 * icon on a dark card). Shortcuts (live TV, inputs, All Apps, settings) are icon buttons.
 */
final class HomeApps {
    private HomeApps() {}

    static final int APP = 0, LIVE_TV = 1, INPUT = 2, ALL_APPS = 3, SETTINGS = 4, ANDROID_SETTINGS = 5;
    /** The "HDMI Inputs" button: opens the bottom drawer on the inputs (the home screen handles it). */
    static final int INPUTS = 7;
    /** The Wi-Fi and Bluetooth top buttons: open the system's Wi-Fi / Bluetooth settings (see Radios for their state). */
    static final int WIFI = 8, BLUETOOTH = 9;
    /** Image size of each card (16:9; on screen it shows up to ~330 px wide). */
    static final int TILE_W = 384, TILE_H = 216;

    static final class Item {
        int kind;
        String key, label, pkg, inputId, number;
        String names = "";  // an HDMI port: every name it goes by (yours, its device's over CEC, the TV's)
        String uri;         // opens this address in the app instead of the app's home (a title's page)
        String symbol;      // icon for the shortcuts and special cards
        Bitmap tile;        // 16:9 card (apps and specials)
        Bitmap icon;        // square app icon for the dock (apps without a brand logo)
        Bitmap iconDark;    // the same icon in iOS's dark style (dark tile, the brand color on the mark)
        Bitmap logo;        // colored logo with no background (shelf); null = use the card
        Bitmap logoDark;    // version for a light background (YouTube's white icon), if any
        float logoInk;      // fraction of the logo with ink (thin logos are drawn larger, with the same "weight")
        float logoScale = 1; // size tweak by eye (compact logos need more)
        boolean connected = true;
    }

    /**
     * Apps on the home shelf, in this order, when installed (up to MAX_SHELF): key, name, package, brand color (for
     * its logo on glass) and how to lift the logo from the app's TV art: "tint" = a white logo takes the art's
     * background color (Prime Video, Globoplay); "play" = the white triangle inside the red button stays white
     * (YouTube); empty = the art's colors as they are. Last, a visual size tweak for the logo. Edit freely.
     */
    private static final String[][] SHELF = {
        {"netflix", "Netflix", "com.netflix.ninja", "E50914", "", "1"},
        {"youtube", "YouTube", "com.google.android.youtube.tv", "FF0033", "play", "1"},
        {"prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "1A98FF", "tint", "0.96"},
        {"disney", "Disney+", "com.disney.disneyplus", "1838C8", "", "1"},
        {"max", "Max", "com.wbd.stream", "6C3BE0", "", "1.32"},
        {"max", "HBO Max", "com.hbo.hbonow", "6C3BE0", "", "1.32"},
        {"appletv", "Apple TV", "com.apple.atve.androidtv.appletv", "B8BCC6", "", "1.1"},
        {"appletv", "Apple TV", "com.apple.atve.sony.appletv", "B8BCC6", "", "1.1"},   // Sony's own build
        {"hulu", "Hulu", "com.hulu.livingroomplus", "1CE783", "", "1"},
        {"paramount", "Paramount+", "com.cbs.ott", "0064FF", "", "1"},
        {"peacock", "Peacock", "com.peacocktv.peacockandroid", "6E55DC", "", "1"},
        {"globoplay", "Globoplay", "com.globo.globotv", "FF5A1F", "tint", "1"},
        {"spotify", "Spotify", "com.spotify.tv.android", "1DB954", "", "1"},
    };
    /** Settings > Shelf's limit for its own list; the dock itself shows every app (it scrolls), with no cap. */
    static final int MAX_SHELF = 80;
    /** The dock shows at most this many (the first ones, in the saved order); the rest stay in Apps and in the list. */
    static final int DOCK_MAX = 10;
    /** Live TV apps of some brands (the antenna's channels open through the standard Android request first). */
    static final String[] LIVE_TV_APPS = {"com.tcl.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter", "com.sony.dtv.tvx",
            "com.smartdevice.livetv", "com.mediatek.tv.oneworld.tvcenter"};
    /** Kept out of "All Apps" (only hidden, still installed). Add the ones your TV shows that you never use. */
    private static final Set<String> HIDDEN = new HashSet<>(java.util.Arrays.asList(
            "com.google.android.play.games",        // Google Play Games
            "com.tcl.t_solo",                       // TCL: T-Solo
            "com.tcl.waterfall.overseas",           // TCL Channel
            "com.tcl.dashboard",                    // TCL Home
            "com.tcl.ocean.instructions",           // TCL: User Guide
            "com.tcl.guard",                        // TCL: Safety Guard
            "com.homwee.factory",                   // Philco: factory menu
            "com.mstar.android.tv.disclaimercustomization",   // Philco: disclaimer
            "com.homwee.chtvmanager",               // Philco: TV Manager (cleaner)
            "com.homwee.multiscreenshare",          // Philco: Midiacast (Chromecast still works)
            "com.xiaomi.mitv.manualhelp",           // Xiaomi: user manual
            "com.Smartdevice.emanual",              // Skyworth: user manual
            "com.google.android.videos",            // Google Play Movies (discontinued)
            "com.disney.starplus",                  // Star+ (merged into Disney+)
            "com.tcl.exhibit"));                    // TCL: T-Exhibition

    private static final Map<String, Bitmap> TILES = new HashMap<>();   // key → ready card
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();   // key → colored logo with no background

    /**
     * Home screen apps. `extract`: also lifts the logos that are not on disk yet (slow: background only); without it,
     * those apps keep their TV art until the next load.
     */
    static List<Item> shelf(Context c, boolean extract) {
        return shelf(c, extract, Integer.MAX_VALUE);
    }

    /**
     * The first `limit` of them (the dock shows DOCK_MAX). Each app's picture, cheapest first and only what's drawn: the
     * bundled dock icon, else the app's own icon; the logo and the TV-art tile only for an app with neither. (Before,
     * every launchable app got all of them, the logos and tiles were thrown away, and the dock kept 10: 10 s on the
     * main thread at boot.)
     */
    static List<Item> shelf(Context c, boolean extract, int limit) {
        List<Item> items = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        long began = android.os.SystemClock.elapsedRealtime();
        List<String> packages = shownPackages(c);
        long listed = android.os.SystemClock.elapsedRealtime();
        for (String pkg : packages) {
            if (items.size() >= limit) break;
            Intent launch = launchIntent(pm, pkg);
            if (launch == null) continue;
            String[] d = known(pkg);
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            if (d == null) {
                ResolveInfo ri = pm.resolveActivity(launch, 0);
                it.key = pkg;
                it.label = ri != null ? String.valueOf(ri.loadLabel(pm)) : pkg;
            } else {
                it.key = d[0];
                it.label = d[1];
                it.logoScale = Float.parseFloat(d[5]);
            }
            // Every dock icon the same way: the official one when bundled, else the TV app's own icon, rounded alike.
            String dockKey = DOCK_KEYS.containsKey(pkg) ? DOCK_KEYS.get(pkg) : squash(it.label);
            Bitmap art = dockArt(c, dockKey);
            if (art == null) art = dockIcon(c, pkg);
            if (art != null) {
                it.icon = roundIcon(art);   // round everywhere (the dock, Most used, Apps)
                it.iconDark = roundIcon(darkIcon(dockKey + "@" + pkg, art));
            } else if (d == null) {
                // No icon at all: an app picked in Settings > Shelf that isn't in SHELF, by its TV art.
                it.tile = appTile(c, null, pkg, 0);
            } else {
                it.logo = shelfLogo(c, d[0], d[2], d[4], extract);
                if (it.logo == null) it.tile = appTile(c, d[0], d[2], 0xFF000000 | Integer.parseInt(d[3], 16));
                if (it.logo != null) it.logoInk = ink(it.logo);
                it.logoDark = ownLogoCached(c, d[0] + "-dark");
            }
            items.add(it);
        }
        if (!shelfTimed) {   // the first build in this process (the startup one): where its time went
            shelfTimed = true;
            long done = android.os.SystemClock.elapsedRealtime();
            Startup.line("startup:   dock: app list " + (listed - began) + " ms (" + packages.size() + " apps), pictures "
                    + (done - listed) + " ms (" + items.size() + " shown)");
        }
        return items;
    }

    private static boolean shelfTimed;

    // MARK: - The dock as last built, on disk (so the home screen's first frame doesn't wait for it)

    /**
     * In device storage (readable at power-on before the TV's storage unlocks): the home screen reads it while it shows
     * its boot skeleton. Only app names and icons.
     */
    private static File dockDir(Context c) {
        return new File(c.createDeviceProtectedStorageContext().getFilesDir(), "dock-cache");
    }

    private static final Object DOCK_AHEAD_LOCK = new Object();
    private static java.util.concurrent.Future<List<Item>> dockAhead;

    /** Starts reading the saved dock now (in the background), for savedDockNow: at power-on, while storage is locked. */
    static void readDockAhead(Context c) {
        final Context app = c.getApplicationContext();
        synchronized (DOCK_AHEAD_LOCK) {
            if (dockAhead != null) return;
            java.util.concurrent.ExecutorService one = java.util.concurrent.Executors.newSingleThreadExecutor();
            dockAhead = one.submit(() -> savedDock(app));
            one.shutdown();
        }
    }

    /** The saved dock: the one read ahead (waiting for it if it's still being read), else read now. */
    static List<Item> savedDockNow(Context c) {
        java.util.concurrent.Future<List<Item>> f;
        synchronized (DOCK_AHEAD_LOCK) {
            f = dockAhead;
            dockAhead = null;
        }
        if (f != null) {
            try {
                List<Item> ready = f.get();
                if (ready != null) return ready;
            } catch (Exception ignored) {
            }
        }
        return savedDock(c);
    }

    /**
     * The dock as it was last built: its apps in order with their finished icons (light and dark), read from disk in
     * milliseconds; null when there's none (first start) or it can't be read. Shown at startup while the real build
     * (package queries, icon rendering: seconds at boot) runs in the background and replaces it.
     */
    static List<Item> savedDock(Context c) {
        File dir = dockDir(c), index = new File(dir, "index.json");
        if (!index.exists()) return null;
        try {
            byte[] bytes = new byte[(int) index.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(index)) {
                int n = 0;
                while (n < bytes.length) {
                    int r = in.read(bytes, n, bytes.length - n);
                    if (r < 0) break;
                    n += r;
                }
            }
            org.json.JSONArray a = new org.json.JSONObject(new String(bytes, "UTF-8")).getJSONArray("apps");
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < a.length() && out.size() < DOCK_MAX; i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.kind = APP;
                it.pkg = o.getString("pkg");
                it.key = o.getString("key");
                it.label = o.getString("label");
                it.icon = BitmapFactory.decodeFile(new File(dir, i + ".png").getPath());
                it.iconDark = BitmapFactory.decodeFile(new File(dir, i + "-dark.png").getPath());
                if (it.icon == null) return null;   // incomplete: no saved dock rather than a broken one
                it.icon.setHasMipMap(true);
                if (it.iconDark != null) it.iconDark.setHasMipMap(true);
                out.add(it);
            }
            return out;
        } catch (Exception e) {
            AppLog.w("saved dock not read: " + e.getMessage());
            return null;
        }
    }

    /**
     * Saves the dock just built (background thread), when it differs from the saved one: other apps, order, names or an
     * app updated (its icon may have changed). A dock with an app that has no icon (drawn by its logo or TV art) isn't
     * saved: the startup then builds it as before.
     */
    static void saveDock(Context c, List<Item> items) {
        try {
            PackageManager pm = c.getPackageManager();
            org.json.JSONArray a = new org.json.JSONArray();
            StringBuilder sig = new StringBuilder();
            for (Item it : items) {
                if (it.kind != APP || it.icon == null || it.pkg == null) return;
                long updated = 0;
                try {
                    updated = pm.getPackageInfo(it.pkg, 0).lastUpdateTime;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                a.put(new org.json.JSONObject().put("pkg", it.pkg).put("key", it.key).put("label", it.label));
                sig.append(SAVED_ICON).append(':').append(it.pkg).append('|').append(it.key).append('|').append(it.label).append('|').append(updated).append(';');
            }
            File dir = dockDir(c), index = new File(dir, "index.json");
            String signature = sig.toString();
            if (index.exists()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(index)) {
                    byte[] bytes = new byte[(int) index.length()];
                    int n = in.read(bytes);
                    if (n > 0 && signature.equals(new org.json.JSONObject(new String(bytes, 0, n, "UTF-8")).optString("sig"))) return;
                } catch (Exception ignored) {
                }
            }
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            index.delete();   // while the icons change, no index: never a half-written dock
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                writePng(it.icon, new File(dir, i + ".png"));
                File dark = new File(dir, i + "-dark.png");
                if (it.iconDark != null) writePng(it.iconDark, dark);
                else dark.delete();
            }
            File tmp = new File(dir, "index.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(new org.json.JSONObject().put("sig", signature).put("apps", a).toString().getBytes("UTF-8"));
            }
            if (!tmp.renameTo(index)) tmp.delete();
            AppLog.w("dock saved for the next start: " + items.size() + " apps");
        } catch (Exception e) {
            AppLog.w("dock not saved: " + e.getMessage());
        }
    }

    /**
     * The saved icons' size: the dock draws them at 80 px, 120 focused (k = 1 at 1920 wide); 192 leaves room for a 4K
     * layout's 1.6×. Reading 20 of them at boot: a fraction of the 512 px ones (697 ms measured).
     */
    private static final int SAVED_ICON = 192;

    private static void writePng(Bitmap b, File f) throws java.io.IOException {
        Bitmap small = b.getWidth() > SAVED_ICON ? Bitmap.createScaledBitmap(b, SAVED_ICON, SAVED_ICON * b.getHeight() / b.getWidth(), true) : b;
        try (FileOutputStream out = new FileOutputStream(f)) {
            small.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            if (small != b) small.recycle();
        }
    }

    private static String[] known(String pkg) {
        for (String[] d : SHELF) if (d[2].equals(pkg)) return d;
        return null;
    }

    /** True while the shelf shows the most used apps (moving apps by hand is off then: the order is the usage). */
    static volatile boolean autoShelf;

    /**
     * The packages the shelf shows: with shelf_most_used (config.properties), the TV apps used most in the last 30
     * days, most used first, topped up from the saved list; otherwise (or with no usage access yet) the saved list.
     * Usage access: adb shell appops set io.ibtzy.tvlauncher GET_USAGE_STATS allow
     */
    static List<String> shownPackages(Context c) {
        // The dock: every app you'd launch, in your order. First the order you saved by moving apps (hold OK on the
        // dock), skipping any uninstalled since (the others keep their places); then the shelf_apps list
        // (config.properties); then every other app, by name. Each app once. Before, it was cut at 10 and the
        // fixed list overrode (and disabled) moving apps.
        autoShelf = false;
        PackageManager pm = c.getPackageManager();
        List<String> out = new ArrayList<>();
        List<String> saved = Prefs.shelf();
        if (saved != null) for (String pkg : saved) if (!out.contains(pkg) && launchIntent(pm, pkg) != null) out.add(pkg);
        for (String pkg : fixedShelf(c)) if (!out.contains(pkg)) out.add(pkg);
        for (String pkg : dockApps(c)) if (!out.contains(pkg)) out.add(pkg);
        return out;   // no cap: the dock scrolls
    }

    /**
     * Every app for the dock, by name: every app with a launcher entry (TV or phone launcher; the manifest's <queries>
     * makes them all visible on Android 11+), ibtzy TV Launcher included (its settings, from its own launcher entry). Left
     * out, each for a reason, and logged: other home screens (launchers), the TV's settings and live TV (they have their
     * own buttons), and the HIDDEN list (TV makers' promo, demo and manual apps). Nothing else is filtered: before,
     * ibtzy TV Launcher itself, never-updated preinstalled apps and any second app with an equal name were dropped silently.
     */
    static List<String> dockApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> why = new java.util.LinkedHashMap<>();
        Set<String> homes = new HashSet<>();
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
                if (ri.activityInfo != null && !ri.activityInfo.packageName.equals(c.getPackageName())) homes.add(ri.activityInfo.packageName);
            }
        } catch (RuntimeException ignored) {
        }
        List<String[]> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] app : launchable(c)) {
            String pkg = app[0];
            if (!seen.add(pkg)) continue;
            if (homes.contains(pkg)) why.put(pkg, "home screen");
            else if (pkg.equals("com.android.tv.settings")) why.put(pkg, "TV settings (its own button)");
            else if (java.util.Arrays.asList(LIVE_TV_APPS).contains(pkg)) why.put(pkg, "live TV (its own button)");
            else if (HIDDEN.contains(pkg)) why.put(pkg, "hidden list");
            else apps.add(app);
        }
        Collections.sort(apps, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        List<String> out = new ArrayList<>();
        for (String[] app : apps) out.add(app[0]);
        AppLog.w("dock: " + out.size() + " apps " + out + "; left out: " + why);
        return out;
    }

    /**
     * shelf_apps in config.properties: the apps in that order, each by package or by name (as the TV shows it; case and
     * spaces don't matter: an exact name first, then one that starts with it, then one that contains it). Apps that
     * aren't installed are skipped.
     */
    static List<String> fixedShelf(Context c) {
        List<String> out = new ArrayList<>();
        if (Config.SHELF_APPS.trim().isEmpty()) return out;
        PackageManager pm = c.getPackageManager();
        List<String[]> apps = launchable(c);
        for (String want : Config.SHELF_APPS.split(",")) {
            if (out.size() >= MAX_SHELF) break;
            String w = squash(want);
            if (w.isEmpty()) continue;
            String found = null;
            if (want.trim().contains(".") && launchIntent(pm, want.trim()) != null) found = want.trim();
            for (int pass = 0; pass < 3 && found == null; pass++) {
                for (String[] app : apps) {
                    String name = squash(app[1]);
                    boolean hit = pass == 0 ? name.equals(w) : pass == 1 ? name.startsWith(w) : name.contains(w);
                    if (hit && !out.contains(app[0])) {   // ibtzy TV Launcher included: it's an app like the others
                        found = app[0];
                        break;
                    }
                }
            }
            if (found != null && !out.contains(found)) {
                out.add(found);
                DOCK_KEYS.put(found, w);
            }
        }
        return out;
    }

    /** Package -> dock key (the shelf_apps name it matched), for its icon in assets/dock/. */
    private static final Map<String, String> DOCK_KEYS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Bitmap> ART = new HashMap<>();

    /** A streaming service's official icon (Recs' app key: "netflix", "prime", "crunchyroll"…), or null. */
    static Bitmap serviceArt(Context c, String app) {
        if (app == null) return null;
        return dockArt(c, app.equals("prime") ? "primevideo" : app);
    }

    /** The official icon bundled for this dock key (tools/fetch_dock_icons.py), or null. */
    private static Bitmap dockArt(Context c, String key) {
        if (key == null || key.isEmpty()) return null;
        // Read without a lock: cards call this while drawing, and the class lock is held by the icon builders
        // (dockIcon, appTile...) for long stretches in the background: a For You frame waited 230-400 ms on it.
        Object known = ART_READY.get(key);
        if (known != null) return known == NO_ART ? null : (Bitmap) known;
        synchronized (ART) {
            known = ART_READY.get(key);
            if (known != null) return known == NO_ART ? null : (Bitmap) known;
            Bitmap b = loadDockArt(c, key);
            ART.put(key, b);
            ART_READY.put(key, b == null ? NO_ART : b);
            return b;
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> ART_READY = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object NO_ART = new Object();

    private static Bitmap loadDockArt(Context c, String key) {
        Bitmap b = null;
        try (InputStream in = c.getAssets().open("dock/" + key + ".png")) {
            b = BitmapFactory.decodeStream(in);
            if (b != null) {
                // iOS 26+ artwork bakes its own rounded corners and glass rim into the square: trim 3% so only the
                // icon's face is left for our own corners.
                int m = Math.round(b.getWidth() * 0.03f);
                b = Bitmap.createBitmap(b, m, m, b.getWidth() - 2 * m, b.getHeight() - 2 * m);
                b.setHasMipMap(true);
            }
        } catch (IOException ignored) {
        }
        return b;
    }

    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace(" ", "").replace("+", "plus").trim();
    }

    /** When each app was last in the foreground (Android's usage stats; read with mostUsed). */
    static final Map<String, Long> LAST_USED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Integer> CATEGORY = new java.util.concurrent.ConcurrentHashMap<>();

    /** The app's Spotlight category (AppsLogic), from its package and Android's declared category; kept. */
    static int category(Context c, String pkg) {
        if (pkg == null) return AppsLogic.TOOLS;
        Integer have = CATEGORY.get(pkg);
        if (have != null) return have;
        int declared = -1;
        try {
            declared = c.getPackageManager().getApplicationInfo(pkg, 0).category;
        } catch (Exception ignored) {
        }
        int cat = AppsLogic.category(pkg, declared);
        CATEGORY.put(pkg, cat);
        return cat;
    }

    /** Up to `n` launchable TV apps by time in the foreground over the last 30 days; null without usage access. */
    static List<String> mostUsed(Context c, int n) {
        UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (um == null) return null;
        long now = System.currentTimeMillis();
        Map<String, UsageStats> stats;
        try {
            stats = um.queryAndAggregateUsageStats(now - 30L * 24 * 3600_000L, now);
        } catch (RuntimeException e) {
            return null;
        }
        if (stats == null || stats.isEmpty()) return null;   // no usage access: Android answers with nothing
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>(HIDDEN);
        skip.add(c.getPackageName());
        skip.add("com.android.tv.settings");
        skip.add("com.google.android.tungsten.setupwraith");
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
                if (ri.activityInfo != null) skip.add(ri.activityInfo.packageName);   // home screens
            }
        } catch (RuntimeException ignored) {
        }
        List<UsageStats> list = new ArrayList<>(stats.values());
        Collections.sort(list, (a, b) -> Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));
        List<String> out = new ArrayList<>();
        for (UsageStats u : list) {
            if (u.getLastTimeUsed() > 0) LAST_USED.put(u.getPackageName(), u.getLastTimeUsed());   // Apps' tiles: when
            if (out.size() >= n) break;
            String pkg = u.getPackageName();
            if (u.getTotalTimeInForeground() < 60_000 || skip.contains(pkg) || out.contains(pkg)) continue;
            if (launchIntent(pm, pkg) == null) continue;
            out.add(pkg);
        }
        return out;
    }

    private static final Map<String, Bitmap> ICONS = new HashMap<>();
    private static final Map<String, Bitmap> DARK = new HashMap<>();

    /**
     * The icon as iOS draws it in dark mode: its background (the area connected to the edges, gradients included)
     * becomes a dark tile; a colored background with a white mark turns two-tone (the mark takes the brand color, as
     * Prime Video's blue text on black); marks on white or black stay as they are (YouTube's red button).
     */
    static synchronized Bitmap darkIcon(String key, Bitmap src) {
        if (DARK.containsKey(key)) return DARK.get(key);
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        // 1. The background: grown from the border while colors change smoothly.
        boolean[] bg = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        float[] edge = new float[3];
        int edgeN = 0;
        for (int i = 0; i < w * h; i++) {
            int x = i % w, y = i / w;
            if (x != 0 && y != 0 && x != w - 1 && y != h - 1) continue;
            edge[0] += r(px[i]); edge[1] += g(px[i]); edge[2] += b(px[i]);
            edgeN++;
        }
        for (int k = 0; k < 3; k++) edge[k] /= Math.max(1, edgeN);
        // A dark background grows only over near-black (Netflix's shaded N must stay); others take gradients.
        boolean darkEdge = (edge[0] + edge[1] + edge[2]) / 3 < 0.15f;
        float seedMax = darkEdge ? 0.1f : 0.3f, growMax = darkEdge ? 0.1f : 0.35f;
        for (int i = 0; i < w * h; i++) {
            int x = i % w, y = i / w;
            if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && dist(px[i], edge) < seedMax) { bg[i] = true; queue[tail++] = i; }
        }
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int n : next) {
                if (n < 0 || bg[n]) continue;
                if (dist(px[n], px[i]) < 0.06f && dist(px[n], edge) < growMax) { bg[n] = true; queue[tail++] = n; }
            }
        }
        float[] mean = new float[3];
        int count = 0;
        for (int i = 0; i < w * h; i++) {
            if (!bg[i]) continue;
            mean[0] += r(px[i]); mean[1] += g(px[i]); mean[2] += b(px[i]);
            count++;
        }
        if (count < w * h / 10) {   // no clear background (a photo-like icon): smaller, on the same dark tile
            Bitmap framed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(framed);
            cv.drawPaint(tilePaint(h));
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float in = w * 0.14f, rr = (w - 2 * in) * 0.245f;
            p.setShader(new android.graphics.BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            android.graphics.Matrix mx = new android.graphics.Matrix();
            mx.setScale((w - 2 * in) / w, (h - 2 * in) / h);
            mx.postTranslate(in, in);
            p.getShader().setLocalMatrix(mx);
            cv.drawRoundRect(new RectF(in, in, w - in, h - in), rr, rr, p);
            framed.setHasMipMap(true);
            DARK.put(key, framed);
            return framed;
        }
        for (int k = 0; k < 3; k++) mean[k] /= count;
        float bgLum = (mean[0] + mean[1] + mean[2]) / 3, bgSat = sat(mean);
        // 2. Two-tone when the background is a color and the mark on it is (mostly) white.
        boolean colored = bgSat > 0.3f && bgLum > 0.12f && bgLum < 0.9f;
        int marks = 0, white = 0;
        for (int i = 0; i < w * h; i++) {
            if (bg[i]) continue;
            marks++;
            float[] c = {r(px[i]), g(px[i]), b(px[i])};
            if ((c[0] + c[1] + c[2]) / 3 > 0.75f && sat(c) < 0.2f) white++;
        }
        boolean twoTone = colored && marks > 0 && white > marks * 0.45f;
        float[] brand = mean.clone();
        float top = Math.max(brand[0], Math.max(brand[1], brand[2]));
        if (top > 0 && top < 0.9f) for (int k = 0; k < 3; k++) brand[k] = Math.min(1, brand[k] * 0.9f / top);
        float[] whiteC = {1, 1, 1};
        float span = Math.max(0.05f, dist3(whiteC, mean));
        // 3. Paint: dark tile (a touch lighter at the top, as iOS), marks over it.
        int[] out = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int y = i / w;
            float v = (float) y / (h - 1);
            float[] tile = {0x15 / 255f, 0x15 / 255f, 0x15 / 255f};   // #151515, every icon alike
            float[] c = {r(px[i]), g(px[i]), b(px[i])};
            float a;          // how much of the mark this pixel is (0 = background)
            float[] mark;
            if (bg[i]) {
                a = 0;
                mark = c;
            } else if (twoTone) {
                a = clamp(dist3(c, mean) / span);
                mark = brand;
            } else {
                // The mark as it is; on its anti-aliased edge, "color to alpha": how much of the pixel is the mark
                // (its largest step away from the old background), the mark's own color un-mixed, then laid on the
                // new tile. No halo of the old background (YouTube's red on white went grey-pink).
                float m = 1;
                if (nearBackground(bg, i, w, h)) {
                    m = 0;
                    for (int k = 0; k < 3; k++) {
                        float step = c[k] < mean[k] ? (mean[k] - c[k]) / Math.max(0.001f, mean[k]) : (c[k] - mean[k]) / Math.max(0.001f, 1 - mean[k]);
                        m = Math.max(m, step);
                    }
                    m = clamp(m);
                }
                float[] o = new float[3];
                for (int k = 0; k < 3; k++) {
                    float own = m > 0.001f ? clamp((c[k] - (1 - m) * mean[k]) / m) : 0;
                    o[k] = mix(tile[k], own, m);
                }
                int alpha = px[i] >>> 24;
                out[i] = (alpha << 24) | (Math.round(255 * o[0]) << 16) | (Math.round(255 * o[1]) << 8) | Math.round(255 * o[2]);
                continue;
            }
            int alpha = px[i] >>> 24;
            out[i] = (alpha << 24) | (Math.round(255 * mix(tile[0], mark[0], a)) << 16)
                    | (Math.round(255 * mix(tile[1], mark[1], a)) << 8) | Math.round(255 * mix(tile[2], mark[2], a));
        }
        Bitmap dark = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        dark.setHasMipMap(true);
        DARK.put(key, dark);
        return dark;
    }

    /** The #151515 tile every dock icon sits on. */
    private static Paint tilePaint(int h) {
        Paint p = new Paint();
        p.setColor(0xFF151515);
        return p;
    }

    private static boolean nearBackground(boolean[] bg, int i, int w, int h) {
        int x = i % w, y = i / w;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && yy >= 0 && xx < w && yy < h && bg[yy * w + xx]) return true;
            }
        }
        return false;
    }

    private static float r(int p) { return ((p >> 16) & 255) / 255f; }
    private static float g(int p) { return ((p >> 8) & 255) / 255f; }
    private static float b(int p) { return (p & 255) / 255f; }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }

    private static float dist(int p, float[] c) {
        float dr = r(p) - c[0], dg = g(p) - c[1], db = b(p) - c[2];
        return (float) Math.sqrt((dr * dr + dg * dg + db * db) / 3);
    }

    private static float dist(int p, int q) {
        return dist(p, new float[] {r(q), g(q), b(q)});
    }

    private static float dist3(float[] c, float[] d) {
        float dr = c[0] - d[0], dg = c[1] - d[1], db = c[2] - d[2];
        return (float) Math.sqrt((dr * dr + dg * dg + db * db) / 3);
    }

    private static final int ICON = 512;
    private static final java.util.WeakHashMap<Bitmap, Bitmap> ROUND = new java.util.WeakHashMap<>();

    /**
     * An icon made for a circle (the square result is clipped round where it's drawn): every logo, round ones included
     * (Spotify, YouTube Music, as the owner chose), centred and fitted inside the circle on its own background colour
     * (a dark one when it has none), like Netflix's N. Analysed by IconShape on a 96 px copy; made
     * once per icon.
     */
    static synchronized Bitmap roundIcon(Bitmap src) {
        if (src == null) return null;
        Bitmap have = ROUND.get(src);
        if (have != null) return have;
        int n = 96;
        Bitmap small = Bitmap.createScaledBitmap(src, n, n, true);
        int[] px = new int[n * n];
        small.getPixels(px, 0, n, 0, 0, n, n);
        if (small != src) small.recycle();
        int bg = IconShape.background(px, n, n);
        int[] b = IconShape.contentBounds(px, n, n, bg);
        Bitmap out = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        float sx = src.getWidth() / (float) n, sy = src.getHeight() / (float) n;
        if (b == null) {
            cv.drawBitmap(src, null, new RectF(0, 0, ICON, ICON), p);
        } else {
            Rect from = new Rect(Math.round(b[0] * sx), Math.round(b[1] * sy), Math.round(b[2] * sx), Math.round(b[3] * sy));
            cv.drawColor((bg >>> 24) < 32 ? 0xFF15161C : bg | 0xFF000000);
            float cw = from.width(), ch = from.height();
            float scale = Math.min(0.6f * ICON / Math.max(cw, ch), 0.78f * ICON / (float) Math.hypot(cw, ch));
            float dw = cw * scale, dh = ch * scale;
            cv.drawBitmap(src, from, new RectF((ICON - dw) / 2, (ICON - dh) / 2, (ICON + dw) / 2, (ICON + dh) / 2), p);
        }
        out.setHasMipMap(true);
        ROUND.put(src, out);
        return out;
    }

    /**
     * Square dock icon, like a macOS one: an adaptive icon's layers in a rounded square; an old-style icon that fills
     * its square as it is, clipped to the same shape; a free-form one centered on a light tile.
     */
    static synchronized Bitmap dockIcon(Context c, String pkg) {
        if (ICONS.containsKey(pkg)) return ICONS.get(pkg);
        Drawable icon = null;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) {
                try {
                    icon = pm.getResourcesForApplication(ai).getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
            if (icon == null) icon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (icon == null) {
            ICONS.put(pkg, null);
            return null;
        }
        Bitmap b = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            int pad = ICON / 4;
            for (Drawable layer : new Drawable[] {a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(-pad, -pad, ICON + pad, ICON + pad);
                layer.draw(canvas);
            }
        } else {
            Bitmap raw = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, ICON, ICON);
            icon.draw(new Canvas(raw));
            // Full bleed when the edges' middles are opaque (a square icon with its own background).
            int m = ICON / 2, e = ICON / 40;
            boolean full = (raw.getPixel(m, e) >>> 24) > 200 && (raw.getPixel(e, m) >>> 24) > 200
                    && (raw.getPixel(m, ICON - 1 - e) >>> 24) > 200 && (raw.getPixel(ICON - 1 - e, m) >>> 24) > 200;
            if (full) {
                canvas.drawBitmap(raw, 0, 0, null);
            } else {
                canvas.drawColor(0xFFF2F2F7);
                int s = Math.round(ICON * 0.7f), o = (ICON - s) / 2;
                icon.setBounds(o, o, o + s, o + s);
                icon.draw(canvas);
            }
            raw.recycle();
        }
        b.setHasMipMap(true);
        ICONS.put(pkg, b);
        return b;
    }

    /** The shelf's packages in order: the user's list (Settings > Shelf), or the built-in one. */
    static List<String> shelfPackages(Context c) {
        List<String> custom = Prefs.shelf();
        if (custom != null) return custom;
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String[] d : SHELF) if (out.size() < MAX_SHELF && launchIntent(pm, d[2]) != null) out.add(d[2]);
        return out;
    }

    /** Every app the shelf could hold, as {package, name}: the ones from SHELF first, then the other TV apps by name. */
    static List<String[]> shelfCandidates(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] d : SHELF) if (launchIntent(pm, d[2]) != null && seen.add(d[2])) out.add(new String[] {d[2], d[1]});
        List<String[]> more = new ArrayList<>();
        for (String[] app : launchable(c)) {
            if (app[0].equals(c.getPackageName()) || HIDDEN.contains(app[0]) || !seen.add(app[0])) continue;
            more.add(app);
        }
        Collections.sort(more, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        out.addAll(more);
        return out;
    }

    // MARK: - Top buttons (the round ones at the top right)

    static final String TOP_WIFI = "wifi", TOP_BLUETOOTH = "bluetooth", TOP_SETTINGS = "settings";
    /** The keys saved before Wi-Fi and Bluetooth replaced them: Live TV became Wi-Fi, HDMI Inputs Bluetooth. */
    private static final String OLD_LIVE = "live", OLD_INPUTS = "inputs";
    static final List<String> TOP_DEFAULT = Collections.unmodifiableList(java.util.Arrays.asList(TOP_WIFI, TOP_BLUETOOTH, TOP_SETTINGS));
    static final int MAX_TOP = TOP_DEFAULT.size();

    /** The keys of the top buttons, in order (Settings > Top Buttons; by default Wi-Fi, Bluetooth, Settings). */
    static List<String> topButtonKeys() {
        return topButtonKeys(Prefs.topButtons());
    }

    /** A saved list (null: never edited) to the keys shown; an older "live" / "inputs" keeps its place as Wi-Fi / Bluetooth. */
    static List<String> topButtonKeys(List<String> saved) {
        if (saved == null) return TOP_DEFAULT;
        List<String> keys = new ArrayList<>();
        for (String key : saved) {
            if (OLD_LIVE.equals(key)) key = TOP_WIFI;
            else if (OLD_INPUTS.equals(key)) key = TOP_BLUETOOTH;
            if (TOP_DEFAULT.contains(key) && !keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    /** The top buttons, ready to draw (0 to 3 of them), Wi-Fi and Bluetooth with their current state. */
    static List<Item> topButtons(Context c) {
        List<Item> items = new ArrayList<>();
        for (String key : topButtonKeys()) items.add(topButton(key));
        Radios.apply(c, items);
        return items;
    }

    private static Item topButton(String key) {
        switch (key) {
            case TOP_WIFI: return action(WIFI, TOP_WIFI, Ui.tr("Wi-Fi"), "wifi");
            case TOP_BLUETOOTH: return action(BLUETOOTH, TOP_BLUETOOTH, Ui.tr("Bluetooth"), "bluetooth");
            // The TV's own settings (ibtzy TV Launcher's stay in the drawer: "Home Settings"); the key keeps saved orders.
            default: return action(ANDROID_SETTINGS, TOP_SETTINGS, Ui.tr("light|Settings"), "settings");
        }
    }

    /** What can go in the top buttons, as {key, name}. */
    static List<String[]> topCandidates() {
        List<String[]> out = new ArrayList<>();
        for (String key : TOP_DEFAULT) out.add(new String[] {key, topButton(key).label});
        return out;
    }

    /** Live TV, the HDMI inputs with a device sending a signal, and All Apps. */
    static List<Item> shortcuts(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) {
            items.add(shortcut(LIVE_TV, "live", Ui.tr("Live TV"), "tv"));
        }
        items.addAll(inputs(c));
        items.add(shortcut(ALL_APPS, "all", Ui.tr("All Apps"), "apps"));
        return items;
    }

    private static Item shortcut(int kind, String key, String label, String symbol) {
        Item it = new Item();
        it.kind = kind;
        it.key = key;
        it.label = label;
        it.symbol = symbol;
        return it;
    }

    /** Icon button (the ones in the corner of the home screen). */
    static Item action(int kind, String key, String label, String symbol) {
        return shortcut(kind, key, label, symbol);
    }

    // MARK: - The Apps section's suggestions and app store (real data only)

    private static final int RECENT_MAX = 12;

    /** An app opened from this home screen: first in its "Recently opened" list (kept on this TV, never sent). */
    static void opened(Context c, String pkg) {
        if (pkg == null) return;
        android.content.SharedPreferences sp = c.getSharedPreferences("apps-history", Context.MODE_PRIVATE);
        List<String> list = new ArrayList<>();
        list.add(pkg);
        for (String p : sp.getString("recent", "").split(",")) {
            if (!p.isEmpty() && !p.equals(pkg) && list.size() < RECENT_MAX) list.add(p);
        }
        sp.edit().putString("recent", String.join(",", list)).apply();
    }

    /**
     * Suggestions for the Apps section, from the best source this TV gives: {label, package, package…}. Most used
     * (Android's usage stats, when the home screen has usage access), else the apps opened from this home screen most
     * recently, else the apps installed most recently (not built in). Empty when none of these has anything: nothing
     * is made up.
     */
    static List<String> suggestions(Context c, int n) {
        List<String> out = new ArrayList<>();
        List<String> used = mostUsed(c, n);
        PackageManager pm = c.getPackageManager();
        String recent = c.getSharedPreferences("apps-history", Context.MODE_PRIVATE).getString("recent", "");
        List<String> opened = new ArrayList<>();
        for (String p : recent.split(",")) {
            if (!p.isEmpty() && !HIDDEN.contains(p) && launchIntent(pm, p) != null && opened.size() < n) opened.add(p);
        }
        if (used != null && !used.isEmpty()) {
            // This TV clears its usage history when it restarts: the few apps used since then, then the ones opened
            // from here before (so the row is full the morning after, not one app).
            out.add(Ui.tr("Most used"));
            out.addAll(used);
            for (String p : opened) if (out.size() <= n && !out.contains(p)) out.add(p);
            return out;
        }
        if (!opened.isEmpty()) {
            out.add(Ui.tr("Recently opened"));
            out.addAll(opened);
            return out;
        }
        List<long[]> times = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
        for (String[] app : launchable(c)) {
            try {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(app[0], 0);
                int flags = pi.applicationInfo.flags;
                if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                if (HIDDEN.contains(app[0]) || app[0].equals(c.getPackageName())) continue;
                times.add(new long[] {pi.firstInstallTime, pkgs.size()});
                pkgs.add(app[0]);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        Collections.sort(times, (a, b) -> Long.compare(b[0], a[0]));
        if (times.isEmpty()) return out;
        out.add(Ui.tr("Recently installed"));
        for (int i = 0; i < Math.min(n, times.size()); i++) out.add(pkgs.get((int) times.get(i)[1]));
        return out;
    }

    /**
     * The app store this TV can search, as {kind, package, name}: "play" (Google Play's own app takes market://
     * searches), "other" (another store app takes them), "web" (no store app; a browser opens Google Play's search
     * page). Null: nothing on this TV can search for apps. Read from what's installed, never assumed.
     */
    static String[] appStore(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent market = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=tv&c=apps"));
        List<ResolveInfo> stores;
        try {
            stores = pm.queryIntentActivities(market, 0);
        } catch (RuntimeException e) {
            stores = new ArrayList<>();
        }
        for (ResolveInfo ri : stores) {
            if ("com.android.vending".equals(ri.activityInfo.packageName)) return new String[] {"play", "com.android.vending", "Google Play"};
        }
        if (!stores.isEmpty()) {
            ResolveInfo ri = stores.get(0);
            return new String[] {"other", ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm))};
        }
        Intent web = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/search?q=tv&c=apps"));
        try {
            if (web.resolveActivity(pm) != null) return new String[] {"web", null, "Google Play"};
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** The store's search for `query` (its own results page: compatibility and availability are the store's to say). */
    static Intent storeSearch(String[] store, String query) {
        String q = android.net.Uri.encode(query.trim());
        if ("web".equals(store[0])) {
            return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/search?q=" + q + "&c=apps"));
        }
        return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=" + q + "&c=apps")).setPackage(store[1]);
    }

    /** ibtzy TV Launcher settings, in the corner of the home screen. */
    static Item settings() {
        return shortcut(SETTINGS, "settings", Ui.tr("Settings"), "settings");
    }

    /** Shortcuts in the bottom drawer. */
    static List<Item> drawerShortcuts() {
        List<Item> items = new ArrayList<>();
        items.add(shortcut(ALL_APPS, "all", Ui.tr("All Apps"), "apps"));
        items.add(shortcut(SETTINGS, "settings", Ui.tr("Home Settings"), "settings"));
        // No "TV Settings" here any more: the round Settings button at the top opens them.
        return items;
    }

    /** A streaming service's app (the "Watch" button of the featured pick). */
    static Item app(String pkg, String label, String uri) {
        Item it = app(pkg, label);
        it.uri = uri;
        return it;
    }

    static Item app(String pkg, String label) {
        Item it = new Item();
        it.kind = APP;
        it.key = pkg;
        it.pkg = pkg;
        it.label = label;
        return it;
    }

    /**
     * The drawer's four inputs, in this order, each on its own HDMI port (your setup): {key, name, HDMI port number, or
     * "" for none}. The PC/Laptop has no port yet: give it one here (e.g. "3") when it's plugged in.
     */
    private static final String[][] CONSOLES = {
        {"ps", "PlayStation 5", "1"},
        {"xbox", "Xbox Series X", "4"},
        {"nintendo", "Nintendo Switch 2", "2"},
        {"pc", "PC/Laptop", ""},
    };

    /**
     * For the bottom drawer: the four cards above, always in that order, on their fixed HDMI ports. A card is enabled
     * (green dot) only while the TV reports its port truly "connected" (a device on and sending a signal); otherwise it
     * stays dimmed and disabled. The port number comes from CONSOLES, the state from the TV: nothing is made up.
     */
    static List<Item> consoles(Context c) {
        List<Item> ports = inputs(c, true);
        List<Item> out = new ArrayList<>();
        for (String[] k : CONSOLES) {
            // PC/Laptop has no brand logo: it keeps the monitor icon; the consoles use their logos (assets/console).
            Item it = shortcut(INPUT, "console-" + k[0], k[1], "pc".equals(k[0]) ? "desktop_windows" : null);
            it.connected = false;   // disabled until its port has a signal
            Item port = null;
            if (!k[2].isEmpty()) {
                it.number = k[2];
                for (Item p : ports) if (k[2].equals(p.number)) port = p;
            }
            if (port != null) {
                it.inputId = port.inputId;
                it.connected = port.connected;
            }
            AppLog.w("input " + k[1] + ": " + (k[2].isEmpty() ? "no HDMI port assigned"
                    : port == null ? "HDMI " + k[2] + " not found on this TV"
                    : "HDMI " + k[2] + (port.connected ? " connected" : " no signal")));
            out.add(it);
        }
        return out;
    }

    /** For the bottom drawer: live TV and every HDMI input (the ones with no device are shown dimmed). */
    static List<Item> allInputs(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) items.add(shortcut(LIVE_TV, "live", Ui.tr("Live TV"), "tv"));
        items.addAll(inputs(c, true));
        return items;
    }

    private static List<Item> inputs(Context c) {
        return inputs(c, false);
    }

    /**
     * HDMI inputs: only the ones with a device sending a signal (or all of them, with `all`). A name you gave the input
     * in the TV's settings is used as the label; without one, the name the device reports over HDMI-CEC ("PlayStation 5").
     * The icon follows what that name says the device is (see deviceSymbol).
     */
    private static List<Item> inputs(Context c, boolean all) {
        List<Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> list = new ArrayList<>();
        // A device that talks HDMI-CEC gets its own input, a child of the port's, named after the device: port input id
        // -> that name.
        Map<String, String> devices = new HashMap<>();
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() == null) {
                    list.add(info);
                } else {
                    devices.put(info.getParentId(), String.valueOf(info.loadLabel(c)).trim());
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
        Pattern digit = Pattern.compile("(\\d)");
        for (int i = 0; i < list.size(); i++) {
            TvInputInfo info = list.get(i);
            int number = i + 1;
            Matcher m = digit.matcher(String.valueOf(info.loadLabel(c)));
            if (m.find()) number = Integer.parseInt(m.group(1));
            // TCL TVs report "connected standby" for every port, with or without a device; only truly "connected"
            // (a device that is on and sending a signal) makes the input show up.
            int state = TvInputManager.INPUT_STATE_DISCONNECTED;
            try {
                state = tim.getInputState(info.getId());
            } catch (RuntimeException ignored) {
            }
            if (!all && state != TvInputManager.INPUT_STATE_CONNECTED) continue;
            CharSequence custom = null;
            try {
                custom = info.loadCustomLabel(c);
            } catch (RuntimeException ignored) {
            }
            String reported = devices.containsKey(info.getId()) ? devices.get(info.getId()) : "";
            String label = custom != null && custom.length() > 0 ? custom.toString()
                    : !reported.isEmpty() && state == TvInputManager.INPUT_STATE_CONNECTED ? reported : "HDMI " + number;
            String symbol = state == TvInputManager.INPUT_STATE_CONNECTED || custom != null
                    ? deviceSymbol(label) : "hdmi";
            Item it = shortcut(INPUT, "hdmi" + number, label, symbol);
            it.inputId = info.getId();
            it.number = String.valueOf(number);
            it.connected = state == TvInputManager.INPUT_STATE_CONNECTED;
            it.names = (custom != null ? custom + " " : "") + reported + " " + info.loadLabel(c);
            out.add(it);
        }
        Collections.sort(out, (a, b) -> a.number.compareTo(b.number));
        return out;
    }

    /** Words in a device's name, by the icon they pick: a game console, a streaming box, a sound system, a TV box. */
    private static final String[][] DEVICE_WORDS = {
        {"gamepad", "playstation", "ps5", "ps4", "ps3", "xbox", "nintendo", "switch", "steam deck", "console", "game"},
        {"play", "apple tv", "chromecast", "fire tv", "firetv", "roku", "shield", "mi box", "tv box", "tvbox", "stick", "streaming", "google tv", "android tv"},
        {"speaker", "soundbar", "sound bar", "receiver", "denon", "yamaha", "marantz", "onkyo", "bose", "sonos", "jbl", "audio", "home theater"},
        {"tv", "cable", "set-top", "decoder", "decodificador", "tuner", "sky", "claro", "vivo"},
    };

    /** The icon for an HDMI device, from the words in its name; a name that says nothing (or a plain port) is the HDMI plug. */
    static String deviceSymbol(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String[] group : DEVICE_WORDS) {
            for (int i = 1; i < group.length; i++) if (n.contains(group[i])) return group[0];
        }
        return "hdmi";
    }

    /**
     * The Apps section: every launchable app Android lets the home screen see (launchable(): TV apps, plus phone apps
     * that aren't built in), the dock's included, A to Z, each once; the ones hidden in config left out; plus our own
     * settings. The TV's Settings and its live TV app are apps like the others here.
     */
    static List<Item> others(Context c) {
        Set<String> skip = new HashSet<>();
        skip.add(c.getPackageName());
        skip.addAll(HIDDEN);
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] app : launchable(c)) {
            String pkg = app[0];
            if (skip.contains(pkg) || !seen.add(pkg)) continue;
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            it.key = pkg;
            it.label = app[1];
            it.tile = appTile(c, null, pkg, 0);
            // Apps shows the square icon, the same art as the dock (the official one when bundled, else the app's own).
            String dockKey = DOCK_KEYS.containsKey(pkg) ? DOCK_KEYS.get(pkg) : squash(it.label);
            Bitmap art = dockArt(c, dockKey);
            if (art == null) art = dockIcon(c, pkg);
            if (art != null) {
                it.icon = roundIcon(art);   // round everywhere (the dock, Most used, Apps)
                it.iconDark = roundIcon(darkIcon(dockKey + "@" + pkg, art));
            }
            items.add(it);
        }
        Collections.sort(items, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        synchronized (HomeApps.class) {
            TILES.keySet().removeIf(k -> k.contains(".") && !seen.contains(k));
        }
        items.add(special(c, SETTINGS, "ibtzy-settings", "ibtzy TV Launcher", "sparkles"));
        return items;
    }

    private static Item special(Context c, int kind, String key, String label, String symbol) {
        Item it = shortcut(kind, key, label, symbol);
        it.tile = symbolTile(c, key, symbol);
        return it;
    }

    /** Opens the item with the system zoom transition growing out of the card (the app starts opening right away). */
    static boolean open(Activity a, Item it, View from, int x, int y, int w, int h) {
        Intent intent, plain = null;   // plain: the app's own home, when it won't take the title's address
        switch (it.kind) {
            case APP:
                intent = launchIntent(a.getPackageManager(), it.pkg);
                plain = intent;
                if (plain != null) plain.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (intent != null && it.uri != null) {
                    intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it.uri)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(it.pkg);
                    if (it.uri.startsWith("intent:")) {   // Watch Next's own link (Continue Watching): the app's intent as written
                        try {
                            intent = Intent.parseUri(it.uri, Intent.URI_INTENT_SCHEME).setPackage(it.pkg);
                        } catch (java.net.URISyntaxException e) {
                            intent = plain;
                        }
                    }
                    // Netflix ignores the title's address when it is already open or when no source is named: it opens its own home.
                    // Like the TV launchers do, name its activity and the source (30) and start it in a fresh task.
                    if ("com.netflix.ninja".equals(it.pkg) && plain != null && plain.getComponent() != null) {
                        intent.setComponent(plain.getComponent()).putExtra("source", "30").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    }
                }
                break;
            case LIVE_TV:
                intent = liveTv(a);
                break;
            case INPUT:
                intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId));
                break;
            case SETTINGS:
                intent = new Intent().setComponent(new ComponentName(a, SettingsActivity.class));
                break;
            case ANDROID_SETTINGS:
                intent = new Intent(Settings.ACTION_SETTINGS);
                break;
            case WIFI:          // the system page: apps can't switch Wi-Fi themselves since Android 10
                intent = systemPage(a, Settings.ACTION_WIFI_SETTINGS);
                plain = new Intent(Settings.ACTION_SETTINGS);   // a TV without that page: its main settings
                break;
            case BLUETOOTH:     // switching Bluetooth needs a permission the home screen doesn't have
                intent = systemPage(a, Settings.ACTION_BLUETOOTH_SETTINGS);
                plain = new Intent(Settings.ACTION_SETTINGS);
                break;
            default:
                return false;
        }
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(intent, ActivityOptions.makeScaleUpAnimation(from, x, y, Math.max(w, 1), Math.max(h, 1)).toBundle());
            if (it.kind == APP) opened(a, it.pkg);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + it.label + ": " + e.getMessage());
            if (plain == null || plain == intent) return false;
            plain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                a.startActivity(plain);
                return true;
            } catch (ActivityNotFoundException | SecurityException e2) {
                return false;
            }
        }
    }

    /**
     * A system settings page, sent explicitly to the exported activity that the TV declares for `action` (the system
     * one first), so a launcher-side chooser or a hidden app can't swallow it. When none is declared, the main
     * settings (the caller's fallback) and a log line say so: `adb logcat -s ibtzyTV | grep "settings page"`.
     */
    static Intent systemPage(Context c, String action) {
        Intent intent = new Intent(action);
        try {
            List<ResolveInfo> found = c.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            ActivityInfo pick = null;
            for (ResolveInfo r : found) {
                ActivityInfo ai = r.activityInfo;
                if (ai == null || !ai.exported || !ai.enabled) continue;
                boolean system = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                AppLog.w("settings page " + action + ": " + ai.packageName + "/" + ai.name + (system ? " (system)" : ""));
                if (pick == null || (system && (pick.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0)) pick = ai;
            }
            if (pick == null) {
                AppLog.w("settings page " + action + ": no activity on this TV, opening the main settings");
                return new Intent(Settings.ACTION_SETTINGS);
            }
            return intent.setComponent(new ComponentName(pick.packageName, pick.name));
        } catch (RuntimeException e) {
            AppLog.w("settings page " + action + ": " + e);
            return intent;
        }
    }

    /**
     * Live TV: tune the antenna input directly. MediaTek TV Center otherwise restores the last source used, which can
     * be HDMI, so its explicit activity receives the tuner URI. Other TV apps keep the standard channel-list request.
     */
    static Intent liveTv(Context c) {
        PackageManager pm = c.getPackageManager();
        // MediaTek OneWorld TV Center (KTC/NextStar) has one input per tuner mode (antenna, cable, satellite): tuning one
        // switches the TV to that mode and hides the other channel lists, so it opens as it is, on its own last channel.
        Intent oneWorld = launchIntent(pm, "com.mediatek.tv.oneworld.tvcenter");
        if (oneWorld != null) return oneWorld;
        String tuner = tunerInput(c);
        Intent mediaTek = launchIntent(pm, "com.mediatek.wwtv.tvcenter");
        if (mediaTek != null && tuner != null) {
            return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(tuner))
                    .setComponent(mediaTek.getComponent());
        }
        if (tuner != null) {
            Intent channels = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner));
            if (channels.resolveActivity(pm) != null) return channels;
        }
        if (mediaTek != null) return mediaTek;
        for (String pkg : LIVE_TV_APPS) {
            Intent app = launchIntent(pm, pkg);
            if (app != null) return app;
        }
        return null;
    }

    /**
     * The TV's antenna (tuner) input, or null. The TV maker's hardware tuner comes first: system apps also declare
     * tuner inputs (TCL: AirPlay), and Android names the hardware ones "…/HW<id>".
     */
    static String tunerInput(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        String system = null, fallback = null;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_TUNER || info.isPassthroughInput()) continue;
                if (info.getServiceInfo() != null && info.getServiceInfo().applicationInfo != null
                        && (info.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    if (info.getId().contains("/HW")) return info.getId();
                    if (system == null) system = info.getId();
                }
                if (fallback == null) fallback = info.getId();
            }
        } catch (RuntimeException ignored) {
        }
        return system != null ? system : fallback;
    }

    static List<String[]> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(tv, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (seen.add(pkg)) out.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
        }
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) {
            String pkg = ri.activityInfo.packageName;
            if ((ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || !seen.add(pkg)) continue;
            out.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
        }
        return out;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    // MARK: - Colored logos (shelf)

    /** Bump when the way logos are lifted changes (the ones saved on disk are redone). */
    private static final int LOGO_VERSION = 2;

    private static Bitmap shelfLogo(Context c, String key, String pkg, String mode, boolean extract) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey(key)) return LOGOS.get(key);
        }
        Bitmap logo = ownLogo(c, key);
        File file = logoFile(c, key, pkg), none = new File(file.getPath() + ".none");
        if (logo == null && file.exists()) logo = BitmapFactory.decodeFile(file.getPath());
        if (logo == null) {
            if (none.exists()) {
                logo = null;
            } else if (!extract) {
                return null;
            } else {
                logo = bannerLogo(c, pkg, mode.equals("tint"), mode.equals("play"));
                saveLogo(logo, file, none);
            }
        }
        if (logo != null) logo.setHasMipMap(true);
        synchronized (LOGOS) {
            LOGOS.put(key, logo);
        }
        return logo;
    }

    /** File for the logo lifted from the TV art: it changes when the app is updated (the art may have changed). */
    private static File logoFile(Context c, String key, String pkg) {
        long updated = 0;
        try {
            updated = c.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return new File(new File(c.getCacheDir(), "logos"), key + "-" + updated + "-v" + LOGO_VERSION + ".png");
    }

    private static void saveLogo(Bitmap logo, File file, File none) {
        File dir = file.getParentFile();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String key = file.getName().substring(0, file.getName().lastIndexOf('-', file.getName().lastIndexOf("-v") - 1));
        File[] old = dir.listFiles((d, name) -> name.matches(java.util.regex.Pattern.quote(key) + "-\\d+-v\\d+\\.png(\\.none)?"));
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        try (FileOutputStream out = new FileOutputStream(logo != null ? file : none)) {
            if (logo != null) logo.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            AppLog.w("logo not saved: " + e.getMessage());
        }
    }

    /** Fraction (0 to 1) of the logo's pixels that have ink, by sampling. */
    private static float ink(Bitmap logo) {
        int w = logo.getWidth(), h = logo.getHeight(), hits = 0, total = 0;
        for (int y = 0; y < h; y += Math.max(1, h / 48)) {
            for (int x = 0; x < w; x += Math.max(1, w / 96)) {
                if ((logo.getPixel(x, y) >>> 24) > 128) hits++;
                total++;
            }
        }
        return total == 0 ? 0.4f : Math.max(0.08f, (float) hits / total);
    }

    /**
     * Logo lifted from the app's TV art: the background is removed (estimated from the edges, gradients are fine), the
     * brand colors stay and black turns white (the card is dark). With `tint`, a white logo on a colored background
     * takes the background's color; with `holes`, holes inside colored shapes (the play triangle) turn white. The
     * edges are "decontaminated" (no halo from the background).
     */
    private static Bitmap bannerLogo(Context c, String pkg, boolean tint, boolean holes) {
        Drawable d = bannerDrawable(c, pkg);
        if (d == null) return null;
        final int w = 768, h = 432;
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(src);
        canvas.drawColor(0xFF15161C);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        src.recycle();
        // Edge profiles (a 3% band), smoothed: the background at any point is the "patch" spanning the four sides.
        int band = Math.round(h * 0.03f);
        float[][] left = new float[h][3], right = new float[h][3], top = new float[w][3], bottom = new float[w][3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < band; x++) {
                add(left[y], px[y * w + x], 1f / band);
                add(right[y], px[y * w + w - 1 - x], 1f / band);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < band; y++) {
                add(top[x], px[y * w + x], 1f / band);
                add(bottom[x], px[(h - 1 - y) * w + x], 1f / band);
            }
        }
        smooth(left, 25);
        smooth(right, 25);
        smooth(top, 41);
        smooth(bottom, 41);
        float[] p00 = top[0], p10 = top[w - 1], p01 = bottom[0], p11 = bottom[w - 1];
        float[] mean = new float[3];
        for (int i = 0; i < 3; i++) mean[i] = (p00[i] + p10[i] + p01[i] + p11[i] + top[w / 2][i] + bottom[w / 2][i] + left[h / 2][i] + right[h / 2][i]) / 8;
        float bgLum = lum(mean), bgSat = sat(mean);
        int[] out = new int[w * h];
        float[] bg = new float[3], col = new float[3];
        int opaque = 0;
        for (int y = 0; y < h; y++) {
            float v = (float) y / (h - 1);
            for (int x = 0; x < w; x++) {
                float u = (float) x / (w - 1);
                for (int i = 0; i < 3; i++) {
                    bg[i] = (1 - v) * top[x][i] + v * bottom[x][i] + (1 - u) * left[y][i] + u * right[y][i]
                            - ((1 - u) * (1 - v) * p00[i] + u * (1 - v) * p10[i] + (1 - u) * v * p01[i] + u * v * p11[i]);
                }
                int p = px[y * w + x];
                col[0] = ((p >> 16) & 255) / 255f;
                col[1] = ((p >> 8) & 255) / 255f;
                col[2] = (p & 255) / 255f;
                float dist = (float) Math.sqrt(((col[0] - bg[0]) * (col[0] - bg[0]) + (col[1] - bg[1]) * (col[1] - bg[1])
                        + (col[2] - bg[2]) * (col[2] - bg[2])) / 3);
                float a = smoothstep(0.07f, 0.22f, dist);
                if (a <= 0.004f) continue;
                for (int i = 0; i < 3; i++) col[i] = clamp((col[i] - (1 - a) * bg[i]) / a);   // no background halo
                float l = lum(col), s = sat(col);
                if (bgLum > 0.6f && l < 0.42f && s < 0.3f) {
                    col[0] = col[1] = col[2] = 1f;                  // black text on a light background → white
                } else if (tint && bgLum <= 0.6f && bgLum > 0.1f && bgSat > 0.35f && l > 0.7f && s < 0.25f) {
                    vivid(bg, col);                                 // white logo on brand color → that color, vivid
                }
                out[y * w + x] = (Math.round(a * 255) << 24) | (Math.round(col[0] * 255) << 16) | (Math.round(col[1] * 255) << 8) | Math.round(col[2] * 255);
                if (a > 0.5f) opaque++;
            }
        }
        float area = (float) opaque / (w * h);
        if (area < 0.01f || area > 0.6f) return null;    // couldn't split the logo from the background: keep the card
        if (holes) fillHoles(out, w, h);
        Bitmap logo = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        return trimLogo(logo);
    }

    private static Drawable bannerDrawable(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                int res = ai.getBannerResource();
                if (res != 0) {
                    android.content.res.Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                    android.util.TypedValue value = new android.util.TypedValue();
                    r.getValue(res, value, true);
                    Drawable d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                    if (d != null) AppLog.w("TV banner for " + pkg + ": " + value.string + " (" + d.getIntrinsicWidth() + "×" + d.getIntrinsicHeight() + ")");
                    return d;
                }
            }
            return pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** Closed holes inside colored shapes (the play triangle) turn white; the ones in letters don't. */
    private static void fillHoles(int[] px, int w, int h) {
        byte[] mark = new byte[w * h];   // 1 = outside (reached from the border), 2 = hole already visited
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            for (int y : new int[] {0, h - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x : new int[] {0, w - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int n : next) {
                if (n >= 0 && mark[n] == 0 && (px[n] >>> 24) < 128) { mark[n] = 1; queue[tail++] = n; }
            }
        }
        for (int start = 0; start < w * h; start++) {
            if (mark[start] != 0 || (px[start] >>> 24) >= 128) continue;
            head = 0;
            tail = 0;
            queue[tail++] = start;
            mark[start] = 2;
            float satSum = 0;
            int border = 0;
            while (head < tail) {
                int i = queue[head++], x = i % w, y = i / w;
                int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                for (int n : next) {
                    if (n < 0) continue;
                    if ((px[n] >>> 24) >= 128) {
                        float[] c = {((px[n] >> 16) & 255) / 255f, ((px[n] >> 8) & 255) / 255f, (px[n] & 255) / 255f};
                        satSum += sat(c);
                        border++;
                    } else if (mark[n] == 0) {
                        mark[n] = 2;
                        queue[tail++] = n;
                    }
                }
            }
            if (border > 0 && satSum / border > 0.45f && tail < w * h / 20) {
                for (int k = 0; k < tail; k++) px[queue[k]] = 0xFFFFFFFF;
            }
        }
    }

    private static Bitmap trimLogo(Bitmap b) {
        Bitmap t = trim(b);
        int pad = Math.max(2, Math.round(Math.max(t.getWidth(), t.getHeight()) * 0.02f));
        Bitmap out = Bitmap.createBitmap(t.getWidth() + 2 * pad, t.getHeight() + 2 * pad, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(t, pad, pad, null);
        return out;
    }

    private static void add(float[] acc, int p, float k) {
        acc[0] += ((p >> 16) & 255) / 255f * k;
        acc[1] += ((p >> 8) & 255) / 255f * k;
        acc[2] += (p & 255) / 255f * k;
    }

    /** Moving average (odd window) on each channel of the profile. */
    private static void smooth(float[][] profile, int window) {
        int n = profile.length, r = window / 2;
        float[][] copy = new float[n][];
        for (int i = 0; i < n; i++) copy[i] = profile[i].clone();
        for (int i = 0; i < n; i++) {
            float[] sum = new float[3];
            int count = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(n - 1, i + r); j++) {
                for (int k = 0; k < 3; k++) sum[k] += copy[j][k];
                count++;
            }
            for (int k = 0; k < 3; k++) profile[i][k] = sum[k] / count;
        }
    }

    private static float lum(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    private static float sat(float[] c) {
        float max = Math.max(c[0], Math.max(c[1], c[2])), min = Math.min(c[0], Math.min(c[1], c[2]));
        return max <= 0 ? 0 : (max - min) / max;
    }

    /** The local background color (gradients included), vivid and bright: the logo stands out on the dark card. */
    private static void vivid(float[] bg, float[] out) {
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(Math.round(bg[0] * 255), Math.round(bg[1] * 255), Math.round(bg[2] * 255), hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = 1f;
        int c = android.graphics.Color.HSVToColor(hsv);
        out[0] = ((c >> 16) & 255) / 255f;
        out[1] = ((c >> 8) & 255) / 255f;
        out[2] = (c & 255) / 255f;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    // MARK: - Cards

    private static synchronized Bitmap appTile(Context c, String key, String pkg, int brand) {
        String cacheKey = key != null ? key : pkg;
        if (TILES.containsKey(cacheKey)) return TILES.get(cacheKey);
        Bitmap logo = key == null ? null : ownLogo(c, key);
        Bitmap tile = logo != null ? logoTile(logo, brand) : bannerTile(c, pkg);
        if (tile == null) tile = iconTile(c, pkg);
        tile.setHasMipMap(true);
        TILES.put(cacheKey, tile);
        return tile;
    }

    /** The app's official TV art (the Android TV banner), at the highest density the app has. */
    private static Bitmap bannerTile(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Drawable d = null;
        try {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                int res = ai.getBannerResource();
                if (res != 0) d = pm.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
            }
            if (d == null) d = pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        canvas.drawColor(0xFF15161C);   // banners with transparency sit on a dark background
        d.setBounds(0, 0, TILE_W, TILE_H);
        d.draw(canvas);
        return b;
    }

    /** No banner: the app's icon (in its proper shape, with some margin) on a dark card. */
    private static Bitmap iconTile(Context c, String pkg) {
        Bitmap b = darkTile();
        Canvas canvas = new Canvas(b);
        Drawable icon = null;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) {
                try {
                    icon = pm.getResourcesForApplication(ai).getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
            if (icon == null) icon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (icon == null) return b;
        int size = Math.round(TILE_H * 0.56f);
        float left = (TILE_W - size) / 2f, top = (TILE_H - size) / 2f;
        if (icon instanceof AdaptiveIconDrawable) {
            // Adaptive icon: the layers clipped to a squircle, like an Apple icon.
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            Path clip = new Path();
            clip.addRoundRect(new RectF(left, top, left + size, top + size), size * 0.2237f, size * 0.2237f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            int pad = size / 4;
            for (Drawable layer : new Drawable[] {a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(Math.round(left) - pad, Math.round(top) - pad, Math.round(left) + size + pad, Math.round(top) + size + pad);
                layer.draw(canvas);
            }
            canvas.restore();
        } else {
            // Legacy icon (a free-form drawing with transparency): smaller, no frame.
            int s = Math.round(TILE_H * 0.46f);
            icon.setBounds((TILE_W - s) / 2, (TILE_H - s) / 2, (TILE_W + s) / 2, (TILE_H + s) / 2);
            icon.draw(canvas);
        }
        return b;
    }

    /** Settings and the like: the icon in white on a dark card. */
    private static synchronized Bitmap symbolTile(Context c, String key, String symbol) {
        if (TILES.containsKey(key)) return TILES.get(key);
        Bitmap b = darkTile();
        Symbols.draw(c, new Canvas(b), symbol, TILE_W / 2f, TILE_H / 2f, TILE_H * 0.36f, 0xF2FFFFFF);
        b.setHasMipMap(true);
        TILES.put(key, b);
        return b;
    }

    /** The official logo (no background) on dark glass with a faint brand tint coming from below. */
    private static Bitmap logoTile(Bitmap logo, int brand) {
        Bitmap b = darkTile();
        Canvas canvas = new Canvas(b);
        Paint glow = new Paint();
        glow.setShader(new RadialGradient(TILE_W / 2f, TILE_H * 1.15f, TILE_W * 0.75f, Ui.alpha(brand, 0.38f), 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, TILE_W, TILE_H, glow);
        boolean wide = logo.getWidth() > logo.getHeight() * 1.35f;
        float maxW = TILE_W * (wide ? 0.62f : 0.4f), maxH = TILE_H * (wide ? 0.42f : 0.56f);
        float scale = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
        float w = logo.getWidth() * scale, h = logo.getHeight() * scale;
        canvas.drawBitmap(logo, null, new RectF((TILE_W - w) / 2, (TILE_H - h) / 2, (TILE_W + w) / 2, (TILE_H + h) / 2),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return b;
    }

    private static Bitmap darkTile() {
        Bitmap b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, TILE_H, 0xFF2B2E3A, 0xFF17191F, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, TILE_W, TILE_H, bg);
        return b;
    }

    /** Official logo (tools/logos.py): trims the transparent margins and scales it down to at most 512 px. */
    /** The bundled logo, read once per process: reading it again on every load made a new image and filled caches. */
    private static Bitmap ownLogoCached(Context c, String key) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey("own:" + key)) return LOGOS.get("own:" + key);
        }
        Bitmap b = ownLogo(c, key);
        synchronized (LOGOS) {
            LOGOS.put("own:" + key, b);
        }
        return b;
    }

    private static Bitmap ownLogo(Context c, String key) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getAssets().open("logos/" + key + ".png")) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / (opts.inSampleSize * 2) >= 640) opts.inSampleSize *= 2;
            Bitmap b;
            try (InputStream in = c.getAssets().open("logos/" + key + ".png")) {
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            if (b == null) return null;
            b = trim(b);
            float scale = Math.min(1f, 512f / Math.max(b.getWidth(), b.getHeight()));
            if (scale < 1f) b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)), Math.max(1, Math.round(b.getHeight() * scale)), true);
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 10) {
                    if (y < top) top = y;
                    bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (bottom < 0) return b;
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }
}
