package io.ibtzy.tvlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Opening a title (Home's cards, every For You row): only in a streaming app installed on this TV, at that title, through
 * a link the app itself declares it opens. Shared by both tabs, so they behave the same.
 *
 * Where the links come from: Watchmode (config.properties "watchmode_key"), per title, its subscription or free sources
 * in tmdb_region (and India for Crunchyroll, as the rest of the app does): each source's Android TV deep link when
 * Watchmode has one, else its web address (netflix.com/title/…, primevideo.com/detail/…, hotstar.com/…,
 * crunchyroll.com/series/…). Rent and buy sources are never used. Looked up once per title and kept (links don't change);
 * a title with none is checked again after a week.
 *
 * Verified, not assumed: a link counts only if Android says the installed app has an activity for it (a VIEW intent
 * restricted to that app's package resolves). An app that is installed but doesn't open that link, a title with no
 * link, no key, no installed service: not launchable, and nothing is opened (never the app's home, a browser or a store).
 */
final class TitleLaunch {
    interface Listener { void onTitleLinks(); }

    private static TitleLaunch instance;

    static synchronized TitleLaunch get(Context c) {
        if (instance == null) instance = new TitleLaunch(c.getApplicationContext());
        return instance;
    }

    /** The order used when several installed apps can open a title (after the one the card shows, if it can). */
    private static final String[] ORDER = {"netflix", "prime", "hotstar", "crunchyroll", "disney", "max", "appletv", "youtube"};
    // A title Watchmode had nothing for: asked again after 12 h (new releases get their links within a day or two;
    // was 7 days, so this week's titles stayed unopenable all week). Titles with links aren't asked again.
    private static final long EMPTY_RETRY = 12 * 3600_000L;

    private final Context context;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Recs.lowPriority(1);
    private final Map<String, Map<String, String>> links = new HashMap<>();   // "movie:123" → service → url
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private final Map<String, Boolean> resolves = new HashMap<>();            // pkg + url → the app opens it
    private final java.util.List<Listener> listeners = new java.util.ArrayList<>();
    private volatile Map<String, String> apps;                                // service → installed package
    private volatile long retryAt;

    private TitleLaunch(Context c) {
        context = c;
        cache = c.getSharedPreferences("title-links-2", Context.MODE_PRIVATE);   // -2: the earlier lookups (wrong link handling) are dropped
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean configured() {
        return !Config.WATCHMODE_KEY.trim().isEmpty();
    }

    /** The installed apps were read again (an app installed or removed): launchability is checked afresh. */
    void appsChanged() {
        apps = null;
        synchronized (resolves) { resolves.clear(); }
    }

    private Map<String, String> apps() {
        Map<String, String> a = apps;
        if (a == null) {
            a = Recs.detectApps(context);   // the same detection as the rest of the app (known packages, then app names)
            apps = a;
        }
        return a;
    }

    /** The service has an app installed here (whether or not a title's link is known yet). */
    boolean installed(String service) {
        return service != null && apps().containsKey(service);
    }

    /**
     * The intent that opens this title, or null: not launchable (no installed app with a verified link for it, or the
     * links aren't known yet). `shown` = the service the card shows, preferred when it can open it. Main thread; from
     * memory and a cached resolve check (no network).
     */
    Intent intent(String kind, int tmdbId, String shown) {
        Map<String, String> byService = linksOf(kind, tmdbId);
        if (byService == null || byService.isEmpty()) return null;
        Map<String, String> installed = apps();
        java.util.List<String> order = new java.util.ArrayList<>();
        if (shown != null) order.add(shown);
        for (String s : ORDER) if (!order.contains(s)) order.add(s);
        for (String service : order) {
            String pkg = installed.get(service), url = appLink(service, byService.get(service));
            if (pkg == null || url == null) continue;
            Intent in = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if ("netflix".equals(service)) {
                // Tested on this TV: Netflix opens the title page for this link + extra when it starts fresh; already
                // running, it showed its own home instead. CLEAR_TASK restarts its screen the way a fresh start does.
                in.putExtra("source", "30").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            } else if ("prime".equals(service)) {
                // Tested on this TV with adb: Prime Video opens the title from its primevideo.com link (no BROWSABLE
                // category, as adb sends it); started afresh like Netflix, so a Prime already open doesn't stay put.
                in.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            } else if (url.startsWith("https://")) {
                in.addCategory(Intent.CATEGORY_BROWSABLE);
            }
            if (opens(pkg, url, in)) return in;
        }
        return null;
    }

    /**
     * The link the service's TV app opens at the title. Netflix: its title page link with the bare title id
     * (https://www.netflix.com/title/<id>, the form tested on this TV; no country path or tracking), nothing without an id.
     */
    private static String appLink(String service, String url) {
        if (url == null) return null;
        if ("netflix".equals(service)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("netflix\\.com/(?:[a-z-]+/)?(?:title|watch)/(\\d+)").matcher(url);
            return m.find() ? "https://www.netflix.com/title/" + m.group(1) : null;
        }
        if ("prime".equals(service)) {
            // Watchmode gives amazon.com/gp/video/detail/<gti>, which the Prime Video TV app doesn't open (seen on this
            // TV). The same title id in Prime Video's own link form (tested on this TV): app.primevideo.com/detail?gti=.
            // Without a gti (an ASIN-only link): nothing, rather than a guess.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(amzn1\\.dv\\.gti\\.[0-9a-fA-F-]+)").matcher(url);
            return m.find() ? "https://app.primevideo.com/detail?gti=" + m.group(1) : null;
        }
        return url;
    }

    /**
     * Why OK can't open this title (for the log; one line, plain words): what's known about its links and the apps
     * here. Main thread, from memory (no network).
     */
    String whyNot(String kind, int tmdbId, String shown) {
        if (!configured()) return "no Watchmode key";
        if (shown == null) return "no streaming service known for it here";
        if (!"other".equals(shown) && !installed(shown)) return "its service (" + shown + ") isn't installed";
        Map<String, String> byService = linksOf(kind, tmdbId);
        if (byService == null) {
            synchronized (pending) {
                if (pending.contains(kind + ":" + tmdbId)) return "its links are being looked up";
            }
            return System.currentTimeMillis() < retryAt ? "lookups paused after an error (10 min)" : "its links aren't looked up yet";
        }
        if (byService.isEmpty()) return "Watchmode has no subscription link for it";
        StringBuilder sb = new StringBuilder("links " + byService.keySet() + ", installed " + apps().keySet() + ":");
        for (Map.Entry<String, String> e : byService.entrySet()) {
            String pkg = apps().get(e.getKey()), url = appLink(e.getKey(), e.getValue());
            sb.append(' ').append(e.getKey()).append(pkg == null ? " not installed;" : url == null ? " no usable title id;"
                    : !Boolean.TRUE.equals(resolveCached(pkg, url)) ? " app refuses " + url + ";" : " ok;");
        }
        return sb.toString();
    }

    private Boolean resolveCached(String pkg, String url) {
        synchronized (resolves) {
            for (Map.Entry<String, Boolean> e : resolves.entrySet()) if (e.getKey().startsWith(pkg + "|" + url)) return e.getValue();
        }
        return null;
    }

    boolean launchable(String kind, int tmdbId, String shown) {
        return intent(kind, tmdbId, shown) != null;
    }

    /** Android's answer: does this installed app declare an activity for the link? (Cached per app and link.) */
    private boolean opens(String pkg, String url, Intent in) {
        String key = pkg + "|" + url;
        synchronized (resolves) {
            Boolean known = resolves.get(key);
            if (known != null) return known;
        }
        boolean ok;
        try {
            ok = !context.getPackageManager().queryIntentActivities(in, PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
        } catch (RuntimeException e) {
            ok = false;
        }
        synchronized (resolves) { resolves.put(key, ok); }
        if (!ok) Startup.event("title link not opened by " + pkg + ": " + url);
        return ok;
    }

    private Map<String, String> linksOf(String kind, int id) {
        String key = kind + ":" + id;
        synchronized (links) {
            if (links.containsKey(key)) return links.get(key);
        }
        String saved = cache.getString(key, null);
        if (saved == null) return null;
        Map<String, String> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(saved);
            JSONObject l = o.optJSONObject("links");
            for (java.util.Iterator<String> it = l == null ? null : l.keys(); it != null && it.hasNext(); ) {
                String s = it.next();
                m.put(s, l.getString(s));
            }
        } catch (Exception ignored) {
        }
        synchronized (links) { links.put(key, m); }
        return m;
    }

    /**
     * Looks the title's links up in the background, if needed: only titles a service installed here streams (no
     * requests for titles that could never open), each once (a title with none: again after a week).
     */
    void request(String kind, int tmdbId, String shownService) {
        if (!configured() || tmdbId <= 0 || kind == null) return;
        if (shownService != null && !"other".equals(shownService) && !installed(shownService)) return;
        if (shownService == null) return;   // no streaming provider known for it here: nothing to open
        String key = kind + ":" + tmdbId;
        String saved = cache.getString(key, null);
        if (saved != null) {
            try {
                JSONObject o = new JSONObject(saved);
                boolean empty = o.optJSONObject("links") == null || o.optJSONObject("links").length() == 0;
                if (!empty || System.currentTimeMillis() - o.optLong("at") < EMPTY_RETRY) return;
            } catch (Exception ignored) {
                return;
            }
        }
        if (System.currentTimeMillis() < retryAt) return;
        synchronized (pending) {
            if (!pending.add(key)) return;
        }
        io.execute(() -> {
            try {
                if (System.currentTimeMillis() < retryAt) return;   // an error a moment ago: the queued ones wait too
                Map<String, String> found = lookUp(kind, tmdbId, shownService);
                JSONObject l = new JSONObject();
                for (Map.Entry<String, String> e : found.entrySet()) l.put(e.getKey(), e.getValue());
                cache.edit().putString(key, new JSONObject().put("at", System.currentTimeMillis()).put("links", l).toString()).apply();
                synchronized (links) { links.put(key, found); }
                Startup.event("title links " + key + ": " + (found.isEmpty() ? "none" : found.keySet()));
                main.post(() -> { for (Listener li : new java.util.ArrayList<>(listeners)) li.onTitleLinks(); });
            } catch (Exception e) {
                retryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
                Startup.event("title links: " + e.getMessage());
            } finally {
                synchronized (pending) { pending.remove(key); }
            }
        });
    }

    /**
     * Watchmode: the TMDB id → its own id → its sources (subscription or free; never rent or buy). Asked without a
     * region: Watchmode answers 400 for regions the key's plan doesn't include (the free plan has no PK), so it returns
     * every region the plan has. Our region's sources first, any service. Another region's link only for the service
     * the card shows: that service's availability here is TMDB's (watch/providers for our region, subscription only);
     * Watchmode only gives the title's page in that app (the same title id in every country).
     */
    private static Map<String, String> lookUp(String kind, int tmdbId, String shown) throws Exception {
        Map<String, String> out = new HashMap<>();
        JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + kind + "_id&search_value=" + tmdbId))
                .optJSONArray("title_results");
        if (found == null || found.length() == 0) {
            Startup.event("title links " + kind + ":" + tmdbId + ": Watchmode has no title with this TMDB id");
            return out;
        }
        int wm = found.getJSONObject(0).getInt("id");
        JSONArray sources = new JSONArray(watchmode("/title/" + wm + "/sources/"));
        // What Watchmode gave (for the log): each source as service/type/region and which link fields hold a link.
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < sources.length() && i < 25; i++) {
            JSONObject s = sources.getJSONObject(i);
            seen.append(i == 0 ? "" : ", ").append(s.optString("name")).append('/').append(s.optString("type")).append('/')
                    .append(s.optString("region")).append(link(s.optString("android_tv_url")) != null ? " tv" : "")
                    .append(link(s.optString("web_url")) != null ? " web" : "");
        }
        Startup.event("title links " + kind + ":" + tmdbId + " (wm " + wm + ", shown " + shown + "): " + sources.length()
                + " sources" + (seen.length() > 0 ? ": " + seen : ""));
        for (int pass = 0; pass < 2; pass++) {   // our region first; then other regions, for the shown service only
            for (int i = 0; i < sources.length(); i++) {
                JSONObject s = sources.getJSONObject(i);
                String type = s.optString("type"), region = s.optString("region");
                if (!"sub".equals(type) && !"free".equals(type)) continue;   // never rent or buy
                if ((pass == 0) != Recs.REGION.equals(region)) continue;
                String service = service(s.optString("name"));
                if (service == null || out.containsKey(service)) continue;
                if (pass == 1 && !service.equals(shown)) continue;
                // The TV deep link when it is one (the free plan puts a "paid plans only" note there), else the web page.
                String url = link(s.optString("android_tv_url"));
                if (url == null) url = link(s.optString("web_url"));
                if (url != null) out.put(service, url);
            }
        }
        return out;
    }

    /** A usable link: https, or an app's own scheme (never plain http, never a note in place of a link). */
    private static String link(String v) {
        if (v == null) return null;
        v = v.trim();
        if (v.isEmpty() || v.contains(" ")) return null;
        if (v.startsWith("https://")) return v;
        int colon = v.indexOf("://");
        return colon > 0 && !v.startsWith("http://") && v.substring(0, colon).matches("[a-zA-Z][a-zA-Z0-9+.-]*") ? v : null;
    }

    /** Watchmode's source name → the app's service key (the same services the rest of the app knows). */
    static String service(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("netflix")) return "netflix";
        if (n.contains("prime video") || n.contains("amazon prime") || n.equals("amazon")) return "prime";
        if (n.contains("hotstar") || n.contains("jiocinema") || n.contains("jio cinema")) return "hotstar";
        if (n.contains("crunchyroll")) return "crunchyroll";
        if (n.contains("disney")) return "disney";
        if (n.equals("max") || n.contains("hbo max")) return "max";
        if (n.contains("apple tv")) return "appletv";
        if (n.contains("youtube")) return "youtube";
        return null;
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                String why = "";
                try (InputStream err = c.getErrorStream()) {
                    if (err != null) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        byte[] buf = new byte[2048];
                        int n;
                        while ((n = err.read(buf)) > 0 && b.size() < 400) b.write(buf, 0, n);
                        why = ": " + b.toString("UTF-8").replaceAll("\\s+", " ");
                    }
                } catch (IOException ignored) {
                }
                throw new IOException("Watchmode returned HTTP " + code + why);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
