package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * For You's Games, in For You's own row and title model (Discover.Row, Discover.Title), so they're drawn, focused,
 * scrolled and cached like Movies & Shows. Rows (RAWG, rawg.io, rawg_key): a featured carousel, Recently released,
 * Upcoming and Popular (see fetchRows). Featured logos: SteamGridDB (steamgriddb_key). Critics: OpenCritic through
 * RapidAPI (opencritic_key): scores matched by name, and a game's reviews when its details open. Players' ratings:
 * RAWG's. Nothing is made up: a score that isn't there shows as "Not rated". Adult-only games are left out.
 * Cache: games.json (rows 6 hours, OpenCritic's lists 24 hours); a failure keeps what's there.
 */
final class Games {
    static final String HERO = "g-hero", RECENT = "g-recent", UPCOMING = "g-upcoming", POPULAR = "g-popular";
    private static final long TTL = 6 * 3600_000L, RETRY = 15 * 60_000L;
    private static final int VERSION = 11, PER_ROW = 14;   // 7: Recently released, Upcoming, Popular; ratings
    private static final long CRITICS_TTL = 24 * 3600_000L;   // OpenCritic's lists: once a day
    private static final String SGDB = "https://www.steamgriddb.com/api/v2", OC = "https://opencritic-api.p.rapidapi.com",
            OC_HOST = "opencritic-api.p.rapidapi.com";
    private volatile long criticsAt;   // when OpenCritic's lists were last fetched (kept with the rows)
    private static final String API = "https://api.rawg.io/api";
    /**
     * RAWG's tags for adult-only games (left out with ESRB "Adults Only"). Not "nudity", "sexual-content" or "mature":
     * RAWG puts those on mainstream M-rated games too (GTA VI was left out by them).
     */
    private static final Set<String> ADULT = new HashSet<>(Arrays.asList("nsfw", "hentai", "porn", "erotic"));

    private static Games instance;

    static synchronized Games get(Context c) {
        if (instance == null) instance = new Games(c.getApplicationContext());
        return instance;
    }

    static boolean configured() {
        return !Config.RAWG_KEY.trim().isEmpty();
    }

    private final Context context;
    private final ExecutorService io = Recs.lowPriority(1);
    private volatile List<Discover.Row> rows = new ArrayList<>();
    private volatile long fetchedAt, failedAt;
    private volatile boolean fetching;
    private volatile String error = "";

    private Games(Context c) {
        context = c;
        // A cold-load test: "adb shell touch /sdcard/Android/data/io.freemytv/files/games-cold" before starting removes
        // the saved rows and the downloaded game art once (the file is then removed).
        try {
            File ext = c.getExternalFilesDir(null), flag = ext == null ? null : new File(ext, "games-cold");
            if (flag != null && flag.exists()) {
                flag.delete();
                cacheFile().delete();
                int n = 0;
                File[] art = new File(c.getCacheDir(), "discover").listFiles();
                for (int i = 0; art != null && i < art.length; i++) if (art[i].getName().startsWith("web") && art[i].delete()) n++;
                Startup.games("cold test: saved rows and " + n + " game images removed");
            }
        } catch (RuntimeException ignored) {
        }
        try {
            File f = cacheFile();
            if (f.exists()) {
                JSONObject o = new JSONObject(read(new FileInputStream(f)));
                if (o.optInt("version") == VERSION) {
                    rows = parse(o.getJSONArray("rows"));
                    fetchedAt = o.optLong("at");
                    criticsAt = o.optLong("criticsAt");
                    criticsFailedAt = o.optLong("criticsFailedAt");
                    JSONObject ps = o.optJSONObject("ps");
                    if (ps != null) {
                        for (java.util.Iterator<String> it = ps.keys(); it.hasNext(); ) {
                            String key = it.next();
                            try {
                                if (ps.optJSONObject(key) != null) psById.put(Integer.parseInt(key), ps.optJSONObject(key));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    JSONObject art = o.optJSONObject("ocArt2");   // (ocArt: the small banners, dropped)
                    if (art != null) {
                        for (java.util.Iterator<String> it = art.keys(); it.hasNext(); ) {
                            String key = it.next();
                            try {
                                ocArtById.put(Integer.parseInt(key), art.optString(key, ""));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    JSONArray oc = o.optJSONArray("oc");
                    if (oc != null) ocList = oc;
                    JSONArray none = o.optJSONArray("noLogo");
                    for (int i = 0; none != null && i < none.length(); i++) noLogo.add(none.optInt(i));
                    JSONObject ab = o.optJSONObject("about");   // descriptions: kept, never asked for again
                    for (java.util.Iterator<String> it = ab == null ? null : ab.keys(); it != null && it.hasNext(); ) {
                        String id = it.next();
                        String d = GamesLogic.fullStop(GamesLogic.clean(ab.optString(id), ABOUT_MAX));
                        if (!d.isEmpty()) about.put(Integer.parseInt(id), d);
                    }
                    aboutAll();
                }
            }
        } catch (Exception ignored) {
        }
    }

    List<Discover.Row> rows() { return rows; }
    boolean loading() { return rows.isEmpty() && configured() && (fetching || failedAt == 0); }
    String error() { return error; }

    /** For You's listeners hear it (the same redraw as Movies & Shows). */
    private static final int ABOUT_MAX = 600;   // the rails show as many whole sentences as fit three lines
    private final java.util.Map<Integer, String> about = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A game's short description for the rails' info (RAWG's details: one request per game, asked only once the focus
     * rests on it, on the one io thread, so never two at once; kept for the session). "" until it's in (listeners
     * hear), or when there's none or it failed (not asked again this session).
     */
    String about(Discover.Title t) {
        if (t.id <= 0) return "";
        String have = about.get(t.id);
        if (have != null) return have;
        about.put(t.id, "");
        io.execute(() -> {
            try {
                // Steam's short description first (written as a summary); else RAWG's (none for many new games).
                String d = steamAbout(t.title);
                if (d.isEmpty()) d = GamesLogic.summary(rawg("/games/" + t.id + "?").optString("description_raw", ""), ABOUT_MAX);
                about.put(t.id, d);
                if (!d.isEmpty()) changed();
            } catch (Exception e) {
                about.remove(t.id);   // a network failure: asked again the next time the focus rests on it
                AppLog.w("games about " + t.title + ": " + e.getMessage());
            }
        });
        return "";
    }

    /**
     * A game's short description from the Steam store (public, no key), for the games RAWG has none for: only a search
     * result with the same name counts (not "The Farmer Was Replaced" for "Replaced"); "" when it isn't on Steam.
     */
    private static String steamAbout(String title) throws Exception {
        JSONArray items = new JSONObject(get("https://store.steampowered.com/api/storesearch/?cc=us&l=english&term="
                + URLEncoder.encode(title, "UTF-8"))).optJSONArray("items");
        for (int i = 0; items != null && i < items.length() && i < 5; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || !GamesLogic.sameName(it.optString("name"), title)) continue;
            int app = it.optInt("id");
            if (app <= 0) return "";
            JSONObject v = new JSONObject(get("https://store.steampowered.com/api/appdetails?filters=basic&appids=" + app))
                    .optJSONObject(String.valueOf(app));
            JSONObject data = v == null ? null : v.optJSONObject("data");
            return data == null ? "" : GamesLogic.summary(data.optString("short_description", ""), ABOUT_MAX);
        }
        return "";
    }

    /**
     * Every rail game's description, asked for in the background once the rows are in (new games only: the rest are
     * in the cache), one request at a time on the io thread; then saved with the rows. Moving along a rail never
     * waits for one.
     */
    private void aboutAll() {
        boolean asked = false;
        for (Discover.Row r : rows) {
            if (!RECENT.equals(r.key) && !POPULAR.equals(r.key)) continue;
            for (Discover.Title t : r.items) asked |= !about.containsKey(t.id) && about(t).isEmpty();
        }
        if (asked) io.execute(this::save);   // after them (one thread, in order)
    }

    /** The descriptions to keep: the games on the page now that have one. */
    private JSONObject aboutJson() throws org.json.JSONException {
        JSONObject o = new JSONObject();
        for (Discover.Row r : rows) {
            for (Discover.Title t : r.items) {
                String d = about.get(t.id);
                if (d != null && !d.isEmpty()) o.put(String.valueOf(t.id), d);
            }
        }
        return o;
    }

    /** A description already in (or ""): what drawing reads, never asking for one. */
    String aboutKnown(Discover.Title t) {
        String have = about.get(t.id);
        return have == null ? "" : have;
    }

    private void changed() {
        Discover.get(context).notifyListeners();
    }

    /**
     * The saved rows show at once (read when Games is first used). A refresh runs only when they're older than 6 hours
     * (or missing): never on a tab switch or a focus move, one at a time, and after a failure not again for 15 minutes.
     * Two stages, each shown as soon as it's in:
     * 1. RAWG's three lists, side by side (one request each) → the rows are shown and saved;
     * 2. in the background: the featured games' logos (SteamGridDB) and OpenCritic's scores (its lists once a day).
     *    Nothing waits for it, and a failure there costs only the logos or scores.
     */
    void refreshIfStale() {
        final long now = System.currentTimeMillis();
        if (fetching || !configured()) return;
        boolean stale = rows.isEmpty() || now - fetchedAt > TTL || now < fetchedAt;
        final boolean fetchRows = stale && !(failedAt != 0 && now - failedAt < RETRY && now >= failedAt);
        boolean enrich = !rows.isEmpty() && (criticsDue(now) || logosMissing() || artMissing(now) || psMissing(now));
        if (!fetchRows && !enrich) return;
        fetching = true;
        changed();
        Startup.games("refresh: rows " + (fetchRows ? "due" : "fresh") + ", saved rows " + rows.size() + ", age "
                + (fetchedAt == 0 ? "none" : (now - fetchedAt) / 60000 + " min"));
        io.execute(() -> {
            try {
                if (fetchRows) {
                    try {
                        List<Discover.Row> fresh = fetchRows();
                        if (fresh.isEmpty()) throw new IOException("RAWG returned nothing");
                        rows = fresh;
                        aboutAll();
                        fetchedAt = System.currentTimeMillis();
                        failedAt = 0;
                        error = "";
                        save();
                        changed();
                        Startup.games("stage 1 shown: " + describe(fresh));
                    } catch (Exception e) {
                        failedAt = System.currentTimeMillis();
                        error = e.getMessage() == null ? e.toString() : e.getMessage();
                        AppLog.w("games: " + error);
                        Startup.games("rows failed: " + error);
                    }
                }
                if (!rows.isEmpty()) {
                    enrich();
                    save();
                    Startup.games("stage 2 shown (logos, scores)");
                }
            } finally {
                fetching = false;
                changed();
            }
        });
    }

    private static String describe(List<Discover.Row> rows) {
        StringBuilder log = new StringBuilder();
        for (Discover.Row r : rows) log.append(r.key).append(' ').append(r.items.size()).append("; ");
        return log.toString();
    }

    private static Discover.Row take(Future<Discover.Row> f, String what) {
        try {
            return f.get(25, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            AppLog.w("games " + what + ": " + e);
            Startup.games("row " + what + " failed: " + e);
            return null;
        }
    }

    /**
     * Stage 1: RAWG only, three requests side by side.
     * - Recently released: the most added games released in the last 60 days, newest first.
     * - Upcoming: the most added games with a date in the next 12 months, soonest first.
     * - Popular: the past 12 months' most added games (RAWG's count of players who added a game to their library),
     *   without the ones already in Recently released; ranked, with the count shown.
     * The featured carousel: the 3 most added recent games and the 2 most added upcoming ones, in turn. Its pictures are
     * each game's own main art (RAWG), nothing else. What stage 2 found before (logos, scores) is kept by game id.
     */
    private List<Discover.Row> fetchRows() throws Exception {
        String today = Recs.day(0);
        ExecutorService pool = Recs.lowPriority(3);
        try {
            Future<Discover.Row> recentF = pool.submit(() -> row(RECENT, Ui.tr("Recently released"),
                    "&dates=" + Recs.day(-60) + "," + today + "&ordering=-added", PER_ROW));
            Future<Discover.Row> upcomingF = pool.submit(() -> row(UPCOMING, Ui.tr("Upcoming"),
                    "&dates=" + Recs.day(1) + "," + Recs.day(365) + "&ordering=-added", PER_ROW));
            Future<Discover.Row> popularF = pool.submit(() -> row(POPULAR, Ui.tr("Popular this year"),
                    "&dates=" + Recs.day(-365) + "," + today + "&ordering=-added", 40));
            Discover.Row recent = take(recentF, "recent"), upcoming = take(upcomingF, "upcoming"), popular = take(popularF, "popular");
            List<Discover.Row> out = new ArrayList<>();
            Discover.Row hero = new Discover.Row();
            hero.key = HERO;
            hero.name = Ui.tr("Featured");
            // The bento's 6 (big first): the 3 most added recent games and the 3 most added upcoming ones, in turn.
            for (int i = 0; i < 3; i++) {   // both lists are still most-added first here
                if (recent != null && recent.items.size() > i) hero.items.add(copy(recent.items.get(i)));
                if (upcoming != null && upcoming.items.size() > i) hero.items.add(copy(upcoming.items.get(i)));
            }
            if (!hero.items.isEmpty()) out.add(hero);
            if (recent != null && !recent.items.isEmpty()) {
                java.util.Collections.sort(recent.items, (a, b) -> b.date.compareTo(a.date));   // newest first
                out.add(recent);
            }
            if (popular != null) {
                Set<Integer> skip = new HashSet<>();
                if (recent != null) for (Discover.Title t : recent.items) skip.add(t.id);
                List<Discover.Title> keep = new ArrayList<>();
                for (Discover.Title t : popular.items) {
                    if (skip.contains(t.id) || keep.size() >= PER_ROW) continue;
                    t.rank = keep.size() + 1;
                    keep.add(t);
                }
                popular.items.clear();
                popular.items.addAll(keep);
                if (!popular.items.isEmpty()) out.add(popular);
            }
            // Last: Upcoming is the release calendar (the page's order: recent, popular, upcoming).
            if (upcoming != null && !upcoming.items.isEmpty()) {
                java.util.Collections.sort(upcoming.items, (a, b) -> a.date.compareTo(b.date));   // soonest first
                out.add(upcoming);
            }
            if (recent == null && upcoming == null && popular == null) throw new IOException("RAWG didn't answer");
            // Stage 2's finds from before, by game id (no flicker: the logo and scores stay while the rows refresh).
            java.util.Map<Integer, Discover.Title> before = new java.util.HashMap<>();
            for (Discover.Row r : rows) {
                for (Discover.Title t : r.items) if (!before.containsKey(t.id) || !t.logo.isEmpty()) before.put(t.id, t);
            }
            for (Discover.Row r : out) {
                for (Discover.Title t : r.items) {
                    Discover.Title b = before.get(t.id);
                    if (b != null && t.logo.isEmpty()) t.logo = b.logo;
                }
            }
            applyCritics(out);
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // MARK: - Stage 2: logos and critics' scores

    private final Set<Integer> noLogo = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private volatile long criticsFailedAt;
    private volatile JSONArray ocList = new JSONArray();   // OpenCritic's lists: {id, name, score, tier, reviews}

    private boolean criticsDue(long now) {
        if (Config.OPENCRITIC_KEY.trim().isEmpty()) return false;
        if (criticsFailedAt != 0 && now - criticsFailedAt < 3600_000L && now >= criticsFailedAt) return false;
        return now - criticsAt > CRITICS_TTL || now < criticsAt;
    }

    /** Logos (SteamGridDB): for the bento's games, looked up once each (the result, or none, is kept). */
    private static final boolean LOGOS = true;   // the bento's 6 only: drawn instead of their names

    private boolean artMissing(long now) {
        if (Config.OPENCRITIC_KEY.trim().isEmpty() || (criticsFailedAt != 0 && now - criticsFailedAt < 3600_000L)) return false;
        for (Discover.Row r : rows) {
            if (!HERO.equals(r.key)) continue;
            for (Discover.Title t : r.items) {
                if (!ocArtById.containsKey(t.id) && !t.backdrop.startsWith("https://img.opencritic.com/")) return true;
            }
        }
        return false;
    }

    private boolean logosMissing() {
        if (!LOGOS || Config.STEAMGRIDDB_KEY.trim().isEmpty()) return false;
        for (Discover.Row r : rows) {
            if (!HERO.equals(r.key)) continue;
            for (Discover.Title t : r.items) if (t.logo.isEmpty() && !noLogo.contains(t.id)) return true;
        }
        return false;
    }

    private void enrich() {
        long now = System.currentTimeMillis();
        ExecutorService pool = Recs.lowPriority(4);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            if (LOGOS && !Config.STEAMGRIDDB_KEY.trim().isEmpty()) {
                for (Discover.Row r : rows) {
                    if (!HERO.equals(r.key)) continue;
                    for (Discover.Title t : r.items) if (t.logo.isEmpty() && !noLogo.contains(t.id)) jobs.add(pool.submit(() -> logo(t)));
                }
            }
            Future<JSONArray> oc = criticsDue(now) ? pool.submit(this::criticsList) : null;
            // PlayStation games of the bento: PS Store entry, cover and best country (one queue for them all).
            List<Discover.Title> psGames = new ArrayList<>();
            for (Discover.Row r : rows) if (HERO.equals(r.key)) for (Discover.Title t : r.items) if (onPlayStation(t)) psGames.add(t);
            if (!psGames.isEmpty()) jobs.add(pool.submit(() -> { for (Discover.Title t : psGames) psFor(t); }));
            // The bento's games without OpenCritic art from its lists: looked up one by one (2 requests each), once —
            // the result (art, or none) is kept in games.json, so each game costs that at most once.
            if (!Config.OPENCRITIC_KEY.trim().isEmpty() && (criticsFailedAt == 0 || now - criticsFailedAt > 3600_000L)) {
                for (Discover.Row r : rows) {
                    if (!HERO.equals(r.key)) continue;
                    for (Discover.Title t : r.items) {
                        if (!ocArtById.containsKey(t.id) && !t.backdrop.startsWith("https://img.opencritic.com/")) {
                            jobs.add(pool.submit(() -> ocArtFor(t)));
                        }
                    }
                }
            }
            for (Future<?> f : jobs) {
                try {
                    f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
            if (!jobs.isEmpty()) changed();   // the logos first, if the scores take longer
            if (oc != null) {
                try {
                    ocList = oc.get(60, java.util.concurrent.TimeUnit.SECONDS);
                    criticsAt = System.currentTimeMillis();
                    criticsFailedAt = 0;
                    Startup.games("critics: " + ocList.length() + " games from OpenCritic");
                } catch (Exception e) {
                    criticsFailedAt = System.currentTimeMillis();
                    Startup.games("critics failed (scores kept from before): " + (e.getCause() != null ? e.getCause() : e));
                }
            }
            applyCritics(rows);
        } finally {
            pool.shutdownNow();
        }
    }

    // MARK: - PlayStation Store (ps-store-db.live's public API: no key, 60 requests a minute; quoted with credit and date)

    private static final String PSDB = "https://ps-store-db.live";
    private static final long PS_ART_TTL = 7 * 24 * 3600_000L;   // which PS Store entry and cover: looked up again weekly
    private static final Object PS_TURN = new Object();
    private static long psLastAt;
    /**
     * RAWG id → what the PS Store has for it: {"id": PS Store DB id ("" = not there), "cover", "at"}. Kept in
     * games.json; the ranks are read again with every 6-hour refresh, the entry and cover weekly.
     */
    private final java.util.Map<Integer, JSONObject> psById = new java.util.concurrent.ConcurrentHashMap<>();

    /** One request at a time, ≥ 1.1 s apart (its limit: 60 a minute). */
    private static String ps(String path) throws Exception {
        synchronized (PS_TURN) {
            long wait = psLastAt + 1100 - android.os.SystemClock.uptimeMillis();
            if (wait > 0) Thread.sleep(wait);
            try {
                return get(PSDB + path);
            } finally {
                psLastAt = android.os.SystemClock.uptimeMillis();
            }
        }
    }

    private static boolean onPlayStation(Discover.Title t) {
        return t.platforms != null && t.platforms.contains("PS");
    }

    /**
     * A bento game on PlayStation: its PS Store entry (by its exact name only — "God of War" never takes
     * "God of War Ragnarök"'s), its cover at 1920 px (the store's current art), and where it ranks best today: the
     * country whose PS Store best-seller chart (pre-orders for games not out yet) has it highest.
     */
    private void psFor(Discover.Title t) {
        try {
            long now = System.currentTimeMillis();
            JSONObject e = psById.get(t.id);
            if (e == null || now - e.optLong("at") > PS_ART_TTL) {
                String body = ps("/api/v1/search?q=" + URLEncoder.encode(t.title, "UTF-8"));
                JSONArray found = body.trim().startsWith("[") ? new JSONArray(body) : firstArray(new JSONObject(body));
                String id = "";
                for (int i = 0; found != null && i < found.length() && id.isEmpty(); i++) {
                    JSONObject g = found.optJSONObject(i);
                    if (g != null && norm(t.title).equals(norm(g.optString("name")))) id = g.optString("id", "");
                }
                String cover = "";
                if (!id.isEmpty()) {
                    JSONObject info = new JSONObject(ps("/api/gameinfo?id=" + URLEncoder.encode(id, "UTF-8") + "&store=US"));
                    cover = big(info.optString("cover", ""));
                }
                e = new JSONObject().put("id", id).put("cover", cover).put("at", now);
                psById.put(t.id, e);
                Startup.games("ps " + t.title + ": " + (id.isEmpty() ? "not on the PS Store (RAWG/OpenCritic art stays)" : "PS Store " + id));
            }
            String id = e.optString("id", "");
            if (id.isEmpty()) return;
            JSONObject g = new JSONObject(ps("/api/v1/games/" + URLEncoder.encode(id, "UTF-8")));
            JSONArray ranks = g.optJSONArray("ranks");
            JSONObject best = null;
            int tied = 0;
            for (int i = 0; ranks != null && i < ranks.length(); i++) {
                JSONObject r = ranks.optJSONObject(i);
                if (r == null || r.optInt("rank") <= 0) continue;
                if (best == null || r.optInt("rank") < best.optInt("rank")) {
                    best = r;
                    tied = 0;
                } else if (r.optInt("rank") == best.optInt("rank")) {
                    tied++;
                }
            }
            int india = 0;
            for (int i = 0; ranks != null && i < ranks.length(); i++) {
                JSONObject r = ranks.optJSONObject(i);
                if (r != null && "IN".equals(r.optString("key")) && r.optInt("rank") > 0) india = r.optInt("rank");
            }
            e.put("india", india);
            JSONObject counts = g.optJSONObject("counts");
            e.put("label", g.isNull("label") ? "" : g.optString("label", ""))
                    .put("no1", counts == null ? 0 : counts.optInt("number_1")).put("top10", counts == null ? 0 : counts.optInt("top_10"));
            e.put("country", best == null ? "" : best.optString("key", "")).put("countryName", best == null ? "" : best.optString("name", ""))
                    .put("rank", best == null ? 0 : best.optInt("rank")).put("tied", tied)
                    .put("saved", g.optString("saved_at", ""));
        } catch (Exception ex) {
            AppLog.w("games ps " + t.title + ": " + ex.getMessage());   // kept as it was: tried again next refresh
            Startup.games("ps " + t.title + " failed: " + ex.getMessage());
        }
    }

    private static JSONArray firstArray(JSONObject o) {
        for (String k : new String[] {"results", "games", "data"}) if (o.optJSONArray(k) != null) return o.optJSONArray(k);
        return null;
    }

    /** A PS Store image address at 1920 px (its own resize parameter; it comes as w=230). */
    private static String big(String url) {
        if (!url.startsWith("https://image.api.playstation.com/")) return url;
        String base = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        return base + "?w=1920&thumb=false";
    }

    /** The PS Store's findings onto the games: its cover as their art (first choice), and where each ranks best. */
    private void applyPs(List<Discover.Row> target) {
        for (Discover.Row r : target) {
            for (Discover.Title t : r.items) {
                JSONObject e = psById.get(t.id);
                if (e == null) continue;
                String cover = e.optString("cover", "");
                if (!cover.isEmpty()) {
                    t.backdrop = cover;
                    t.poster = cover;
                }
                t.country = e.optString("country", "");
                t.countryName = e.optString("countryName", "");
                t.countryRank = e.optInt("rank");
                t.countryTied = e.optInt("tied");
                t.indiaRank = e.optInt("india");
                t.psLabel = e.optString("label", "");
                t.psNo1 = e.optInt("no1");
                t.psTop10 = e.optInt("top10");
                t.rankSaved = e.optString("saved", "");
            }
        }
    }

    /** For the credit line (its terms: quoted with the date): " (Oct 25)" from the newest ranks read, or "". */
    String psCredit() {
        String newest = "";
        for (JSONObject e : psById.values()) {
            String d = e.optString("saved", "");
            if (d.compareTo(newest) > 0) newest = d;
        }
        return newest.length() >= 10 ? " (" + Discover.shortDate(newest.substring(0, 10)) + ")" : "";
    }

    private boolean psMissing(long now) {
        for (Discover.Row r : rows) {
            if (!HERO.equals(r.key)) continue;
            for (Discover.Title t : r.items) {
                if (!onPlayStation(t)) continue;
                JSONObject e = psById.get(t.id);
                if (e == null || now - e.optLong("at") > PS_ART_TTL || (!e.optString("id").isEmpty() && !e.has("top10"))) return true;   // (read before India's rank was kept)
            }
        }
        return false;
    }

    /** RAWG id → OpenCritic art address ("" = OpenCritic has none / doesn't know it), from ocArtFor. */
    private final java.util.Map<Integer, String> ocArtById = new java.util.concurrent.ConcurrentHashMap<>();

    /** One game's OpenCritic art: by its id, else by its exact name (never a near name), then its page's images. */
    private void ocArtFor(Discover.Title t) {
        try {
            int id = t.ocId;
            if (id <= 0) {
                JSONArray found = new JSONArray(oc("/game/search?criteria=" + URLEncoder.encode(t.title, "UTF-8")));
                for (int i = 0; i < found.length() && id <= 0; i++) {
                    JSONObject g = found.optJSONObject(i);
                    if (g != null && norm(t.title).equals(norm(g.optString("name")))) id = g.optInt("id");
                }
            }
            String img = id <= 0 ? "" : ocArt(new JSONObject(oc("/game/" + id)).optJSONObject("images"));
            ocArtById.put(t.id, img);
            Startup.games("art " + t.title + ": " + (img.isEmpty() ? "none on OpenCritic (RAWG's stays)" : "OpenCritic"));
        } catch (Exception e) {
            AppLog.w("games art " + t.title + ": " + e.getMessage());   // not kept: tried again next refresh
        }
    }

    /** SteamGridDB (steamgriddb_key): the game by name (the exact name first), then its first transparent PNG logo. */
    private void logo(Discover.Title t) {
        String key = Config.STEAMGRIDDB_KEY.trim();
        try {
            String q = URLEncoder.encode(t.title, "UTF-8").replace("+", "%20");
            JSONArray found = sgdb("/search/autocomplete/" + q, key).optJSONArray("data");
            JSONObject game = null;
            for (int i = 0; found != null && i < found.length(); i++) {
                JSONObject g = found.optJSONObject(i);
                if (g != null && norm(t.title).equals(norm(g.optString("name")))) {
                    game = g;
                    break;
                }
            }
            if (game == null || game.optInt("id") <= 0) {   // only the same game's logo, never a near name's
                noLogo.add(t.id);
                return;
            }
            JSONArray logos = sgdb("/logos/game/" + game.optInt("id") + "?types=static&nsfw=false&humor=false", key).optJSONArray("data");
            for (int i = 0; logos != null && i < logos.length(); i++) {
                JSONObject l = logos.optJSONObject(i);
                if (l != null && "image/png".equals(l.optString("mime"))) {
                    t.logo = l.optString("url", "");
                    return;
                }
            }
            noLogo.add(t.id);
        } catch (Exception e) {
            AppLog.w("games logo " + t.title + ": " + e.getMessage());
        }
    }

    /** OpenCritic's recently released and popular games (two requests a day): id, name, Top Critic Average, tier. */
    private JSONArray criticsList() throws Exception {
        JSONArray out = new JSONArray();
        Exception last = null;
        for (String path : new String[] {"/game/recently-released", "/game/popular", "/game/upcoming"}) {
            try {
                JSONArray list = new JSONArray(oc(path));
                for (int i = 0; i < list.length(); i++) {
                    JSONObject g = list.optJSONObject(i);
                    if (g == null || g.optString("name").isEmpty() || g.optInt("id") <= 0) continue;
                    // Untrusted: the score clamped, text cleaned, art https only (GamesLogic).
                    out.put(new JSONObject().put("id", g.optInt("id")).put("name", GamesLogic.clean(g.optString("name"), 120))
                            .put("score", GamesLogic.score(g.optDouble("topCriticScore", -1)))
                            .put("tier", GamesLogic.clean(g.optString("tier", ""), 20))
                            .put("reviews", Math.max(0, g.optInt("numReviews", g.optInt("numTopCriticReviews", 0))))
                            .put("img", GamesLogic.https(ocArt(g.optJSONObject("images")))));
                }
            } catch (Exception e) {
                last = e;
            }
        }
        if (out.length() == 0 && last != null) throw last;
        return out;
    }

    /**
     * OpenCritic's art for a game: its masthead, else its banner (the publisher's current key art, which OpenCritic
     * keeps up to date; RAWG's main image is often the reveal-era one). "" when it has none.
     */
    private static String ocArt(JSONObject images) {
        if (images == null) return "";
        // The masthead first: full size (1918 × 1079 to 3836 × 2158 on the TV's log); the banner is 460 × 215 (soft).
        for (String kind : new String[] {"masthead", "banner"}) {
            JSONObject im = images.optJSONObject(kind);
            if (im == null) continue;
            for (String size : new String[] {"og", "lg"}) {
                String p = im.optString(size, "");
                if (!p.isEmpty()) return p.startsWith("https://") ? p : "https://img.opencritic.com/" + p;
            }
        }
        return "";
    }

    /** OpenCritic's scores onto the games with the same name (only a real score; no score: "Not rated"). */
    private void applyCritics(List<Discover.Row> target) {
        applyCriticsOnly(target);
        applyPs(target);   // last: the PS Store's cover wins where there is one
    }

    private void applyCriticsOnly(List<Discover.Row> target) {
        for (Discover.Row r : target) {
            for (Discover.Title t : r.items) {
                String img = ocArtById.get(t.id);
                if (img != null && !img.isEmpty()) {
                    t.backdrop = img;
                    t.poster = img;
                }
            }
        }
        JSONArray list = ocList;
        if (list.length() == 0) return;
        java.util.Map<String, JSONObject> byName = new java.util.HashMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject g = list.optJSONObject(i);
            if (g != null) byName.put(norm(g.optString("name")), g);
        }
        for (Discover.Row r : target) {
            for (Discover.Title t : r.items) {
                JSONObject g = byName.get(norm(t.title));
                if (g == null) continue;
                t.ocId = g.optInt("id");
                String img = g.optString("img", "");
                if (!img.isEmpty()) {   // the same game (same name): its current art, on its tile and in the bento
                    t.backdrop = img;
                    t.poster = img;
                }
                if (g.optInt("score") > 0) {
                    t.score = g.optInt("score");
                    t.tier = g.optString("tier", "");
                    t.critics = g.optInt("reviews");
                }
            }
        }
    }

    private JSONObject psJson() throws org.json.JSONException {
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<Integer, JSONObject> e : psById.entrySet()) o.put(String.valueOf(e.getKey()), e.getValue());
        return o;
    }

    private static java.util.Map<String, Object> stringKeys(java.util.Map<Integer, String> m) {
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<Integer, String> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    /** A name for matching: lower case, no ™ ® or punctuation, single spaces. */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[™®©]", "").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    // MARK: - Critics' reviews of one game (when its details open)

    static final class Review {
        String outlet = "", author = "", snippet = "", url = "", score = "";
    }

    static final class Critics {
        volatile boolean done;
        String error = "";
        int score, reviews, recommended;
        String tier = "";
        final List<Review> list = new ArrayList<>();
    }

    private final java.util.Map<Integer, Critics> critics = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A game's critics on OpenCritic, asked for only when its details open (3 requests at most, then kept for the
     * session): its Top Critic Average, tier, review count, % recommended and up to 5 reviews (outlet, the outlet's own
     * score, excerpt, link). Returns at once; listeners hear when it's in.
     */
    Critics critics(Discover.Title t) {
        Critics have = critics.get(t.id);
        if (have != null) return have;
        Critics c = new Critics();
        critics.put(t.id, c);
        if (Config.OPENCRITIC_KEY.trim().isEmpty()) {
            c.error = Ui.tr("Critics' reviews need an OpenCritic key.");
            c.done = true;
            return c;
        }
        io.execute(() -> {
            try {
                int id = t.ocId;
                if (id <= 0) {
                    JSONArray found = new JSONArray(oc("/game/search?criteria=" + URLEncoder.encode(t.title, "UTF-8")));
                    for (int i = 0; i < found.length() && id <= 0; i++) {
                        JSONObject g = found.optJSONObject(i);
                        if (g != null && norm(t.title).equals(norm(g.optString("name")))) id = g.optInt("id");
                    }
                }
                if (id <= 0) {
                    c.error = Ui.tr("Not on OpenCritic yet.");
                    return;
                }
                JSONObject g = new JSONObject(oc("/game/" + id));
                c.score = Math.max(0, (int) Math.round(g.optDouble("topCriticScore", -1)));
                c.tier = c.score > 0 ? g.optString("tier", "") : "";
                c.reviews = g.optInt("numReviews", g.optInt("numTopCriticReviews", 0));
                c.recommended = Math.max(0, (int) Math.round(g.optDouble("percentRecommended", -1)));
                if (c.reviews > 0) {
                    JSONArray rs = new JSONArray(oc("/review/game/" + id));
                    for (int i = 0; i < rs.length() && c.list.size() < 5; i++) {
                        JSONObject r = rs.optJSONObject(i);
                        if (r == null || r.optString("snippet").trim().isEmpty()) continue;
                        Review v = new Review();
                        JSONObject outlet = r.optJSONObject("Outlet");
                        v.outlet = outlet == null ? "" : outlet.optString("name", "");
                        JSONArray authors = r.optJSONArray("Authors");
                        JSONObject a = authors == null ? null : authors.optJSONObject(0);
                        v.author = a == null ? "" : a.optString("name", "");
                        v.snippet = r.optString("snippet").trim();
                        v.url = r.optString("externalUrl", "");
                        v.score = outletScore(r);
                        c.list.add(v);
                    }
                }
                if (c.score > 0) {   // the cards get it too
                    for (Discover.Row row : rows) {
                        for (Discover.Title x : row.items) {
                            if (x.id != t.id) continue;
                            x.ocId = id;
                            x.score = c.score;
                            x.tier = c.tier;
                            x.critics = c.reviews;
                        }
                    }
                }
            } catch (Exception e) {
                c.error = Ui.tr("OpenCritic didn't answer.") + " (" + (e.getMessage() == null ? e : e.getMessage()) + ")";
                critics.remove(t.id);   // opening it again tries again
            } finally {
                c.done = true;
                changed();
            }
        });
        return c;
    }

    /** The outlet's own score as it gave it ("8 / 10", "4.5 / 5 stars", "Recommended"), or "" when unclear. */
    private static String outletScore(JSONObject r) {
        double score = r.optDouble("score", -1);
        JSONObject f = r.optJSONObject("ScoreFormat");
        if (f == null || score < 0) return "";
        JSONArray options = f.optJSONArray("options");
        if (f.optBoolean("isSelect") && options != null) {
            for (int i = 0; i < options.length(); i++) {
                JSONObject o = options.optJSONObject(i);
                if (o != null && Math.abs(o.optDouble("val", -999) - score) < 0.01) return o.optString("label", "");
            }
            return "";
        }
        String name = f.optString("shortName", f.optString("name", ""));
        if (!name.contains("x")) return "";
        int decimals = f.optInt("numDecimals", 0);
        String v = String.format(java.util.Locale.US, "%." + Math.max(0, decimals) + "f", score);
        return name.replace("x", v);
    }

    private static Discover.Title copy(Discover.Title t) {
        Discover.Title c = new Discover.Title();
        c.id = t.id;
        c.kind = t.kind;
        c.title = t.title;
        c.backdrop = t.backdrop;
        c.poster = t.poster;
        c.date = t.date;
        c.platforms = t.platforms;
        c.genres = t.genres;
        c.score = t.score;
        c.added = t.added;
        c.logo = t.logo;
        c.tier = t.tier;
        c.meta = t.meta;
        c.rating = t.rating;
        c.ratings = t.ratings;
        c.ocId = t.ocId;
        c.critics = t.critics;
        return c;
    }


    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        final long t0 = android.os.SystemClock.uptimeMillis();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
            Startup.games("net " + (android.os.SystemClock.uptimeMillis() - t0) + " ms " + Startup.noQuery(c.getURL()));
        }
    }


    private static JSONObject sgdb(String path, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(SGDB + path).openConnection();
        final long t0 = android.os.SystemClock.uptimeMillis();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("SteamGridDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
            Startup.games("net " + (android.os.SystemClock.uptimeMillis() - t0) + " ms " + Startup.noQuery(c.getURL()));
        }
    }

    private static final Object OC_TURN = new Object();
    private static long ocLastAt;

    /**
     * OpenCritic's BASIC plan allows only so many requests a second (HTTP 429 when two went out together): its
     * requests go one at a time, at least 1.1 s apart.
     */
    private static String oc(String path) throws Exception {
        synchronized (OC_TURN) {
            long wait = ocLastAt + 1100 - android.os.SystemClock.uptimeMillis();
            if (wait > 0) Thread.sleep(wait);
            try {
                return ocNow(path);
            } finally {
                ocLastAt = android.os.SystemClock.uptimeMillis();
            }
        }
    }

    private static String ocNow(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(OC + path).openConnection();
        final long t0 = android.os.SystemClock.uptimeMillis();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("X-RapidAPI-Key", Config.OPENCRITIC_KEY.trim());
        c.setRequestProperty("X-RapidAPI-Host", OC_HOST);
        // As curl sends it (that works; from the TV the same request got HTTP 502): JSON, our name, and no gzip (Android
        // asks for gzip by itself).
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "FreeMyTV");
        c.setRequestProperty("Accept-Encoding", "identity");
        try {
            int code = c.getResponseCode();
            String left = c.getHeaderField("x-ratelimit-requests-remaining");   // RapidAPI's quota left (logged)
            if (left != null) Startup.games("opencritic quota left: " + left + " of " + c.getHeaderField("x-ratelimit-requests-limit"));
            if (code != 200) {
                String why = "";
                try (InputStream err = c.getErrorStream()) {   // the gateway's reason (no key in it), for the log
                    if (err != null) why = read(err).replaceAll("\\s+", " ");
                } catch (IOException ignored) {
                }
                Startup.games("opencritic HTTP " + code + ": " + (why.length() > 300 ? why.substring(0, 300) : why));
                throw new IOException("OpenCritic returned HTTP " + code);
            }
            return read(c.getInputStream());
        } finally {
            c.disconnect();
            Startup.games("net " + (android.os.SystemClock.uptimeMillis() - t0) + " ms " + Startup.noQuery(c.getURL()));
        }
    }

    private Discover.Row row(String key, String name, String query, int max) throws Exception {
        JSONArray results = rawg("/games?page_size=40" + query).optJSONArray("results");
        Discover.Row r = new Discover.Row();
        r.key = key;
        r.name = name;
        for (int i = 0; results != null && i < results.length() && r.items.size() < max; i++) {
            JSONObject g = results.optJSONObject(i);
            if (g == null || adult(g)) continue;
            String image = g.optString("background_image", "");
            if (image.isEmpty() || "null".equals(image) || g.optString("name").isEmpty()) continue;
            Discover.Title t = new Discover.Title();
            t.id = g.optInt("id");
            t.kind = "game";
            t.title = g.optString("name");
            t.backdrop = image;
            t.poster = image;   // RAWG has landscape art only: the tall cards show its centre
            t.date = g.optString("released", "");
            if ("null".equals(t.date)) t.date = "";
            t.platforms = platforms(g.optJSONArray("platforms"));
            t.genres = genre(g.optJSONArray("genres"));
            t.meta = g.isNull("metacritic") ? 0 : g.optInt("metacritic");   // Metacritic, as RAWG lists it (often none)
            t.added = g.optInt("added");
            t.rating = (float) g.optDouble("rating", 0);   // RAWG players' average, 0–5
            t.ratings = g.optInt("ratings_count");
            r.items.add(t);
        }
        return r;
    }

    private static boolean adult(JSONObject g) {
        JSONObject esrb = g.optJSONObject("esrb_rating");
        if (esrb != null && "adults-only".equals(esrb.optString("slug"))) return true;
        JSONArray tags = g.optJSONArray("tags");
        for (int i = 0; tags != null && i < tags.length(); i++) {
            JSONObject tag = tags.optJSONObject(i);
            if (tag != null && ADULT.contains(tag.optString("slug"))) return true;
        }
        return false;
    }

    /** The newest platforms first, in short names, at most three: "PS5 · Xbox Series · PC". */
    private static String platforms(JSONArray a) {
        String[][] order = {{"PlayStation 5", "PS5"}, {"Xbox Series S/X", "Xbox Series"}, {"PC", "PC"},
                {"Nintendo Switch 2", "Switch 2"}, {"Nintendo Switch", "Switch"}, {"PlayStation 4", "PS4"},
                {"Xbox One", "Xbox One"}, {"macOS", "Mac"}, {"iOS", "iOS"}, {"Android", "Android"}};
        Set<String> have = new HashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            JSONObject platform = p == null ? null : p.optJSONObject("platform");
            if (platform != null) have.add(platform.optString("name"));
        }
        List<String> out = new ArrayList<>();
        for (String[] o : order) if (have.contains(o[0]) && out.size() < 3) out.add(o[1]);
        return android.text.TextUtils.join(" · ", out);
    }

    private static String genre(JSONArray a) {
        JSONObject g = a == null ? null : a.optJSONObject(0);
        return g == null ? "" : g.optString("name");
    }

    /**
     * A RAWG image at `width` px wide (its own resize service: a fraction of the original's size): media.rawg.io/media/
     * games/… → media.rawg.io/media/resize/<width>/-/games/…; other addresses as they are.
     */
    static String sized(String url, int width) {
        String marker = "media.rawg.io/media/";
        int at = url.indexOf(marker);
        if (at < 0 || url.contains("/media/resize/") || url.contains("/media/crop/")) return url;
        return url.substring(0, at + marker.length()) + "resize/" + width + "/-/" + url.substring(at + marker.length());
    }

    // MARK: - Network and cache

    private static JSONObject rawg(String path) throws Exception {
        String key = Config.RAWG_KEY.trim();
        if (key.isEmpty()) throw new IOException("no RAWG key");
        HttpURLConnection c = (HttpURLConnection) new URL(API + path + "&key=" + URLEncoder.encode(key, "UTF-8")).openConnection();
        final long t0 = android.os.SystemClock.uptimeMillis();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("RAWG returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
            Startup.games("net " + (android.os.SystemClock.uptimeMillis() - t0) + " ms " + Startup.noQuery(c.getURL()));
        }
    }

    private File cacheFile() {
        return new File(context.getFilesDir(), "games.json");
    }

    private synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (Discover.Row r : rows) {
                JSONArray items = new JSONArray();
                for (Discover.Title t : r.items) {
                    items.put(new JSONObject().put("id", t.id).put("title", t.title).put("image", t.backdrop)
                            .put("date", t.date).put("platforms", t.platforms).put("genres", t.genres)
                            .put("score", t.score).put("added", t.added).put("logo", t.logo).put("tier", t.tier)
                            .put("meta", t.meta).put("rating", t.rating).put("ratings", t.ratings).put("oc", t.ocId)
                            .put("critics", t.critics).put("rank", t.rank).put("country", t.country).put("countryName", t.countryName)
                            .put("countryRank", t.countryRank).put("countryTied", t.countryTied).put("india", t.indiaRank).put("psLabel", t.psLabel).put("psNo1", t.psNo1).put("psTop10", t.psTop10).put("rankSaved", t.rankSaved));
                }
                a.put(new JSONObject().put("key", r.key).put("name", r.name).put("items", items));
            }
            JSONObject o = new JSONObject().put("version", VERSION).put("at", fetchedAt).put("criticsAt", criticsAt).put("rows", a)
                    .put("oc", ocList).put("noLogo", new JSONArray(noLogo))
                    .put("criticsFailedAt", criticsFailedAt)
                    .put("about", aboutJson())
                    .put("ps", psJson())
                    .put("ocArt2", new JSONObject(new java.util.HashMap<String, Object>(stringKeys(ocArtById))));   // kept: a restart doesn't ask OpenCritic again within the hour
            try (FileOutputStream out = new FileOutputStream(cacheFile())) {
                out.write(o.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    private static List<Discover.Row> parse(JSONArray a) {
        List<Discover.Row> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Discover.Row r = new Discover.Row();
            r.key = o.optString("key");
            r.name = o.optString("name");
            JSONArray items = o.optJSONArray("items");
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject x = items.optJSONObject(j);
                Discover.Title t = new Discover.Title();
                t.id = x.optInt("id");
                t.kind = "game";
                t.title = x.optString("title");
                t.backdrop = x.optString("image");
                t.poster = t.backdrop;
                t.date = x.optString("date");
                t.platforms = x.optString("platforms");
                t.genres = x.optString("genres");
                t.score = x.optInt("score");
                t.added = x.optInt("added");
                t.logo = x.optString("logo");
                t.tier = x.optString("tier");
                t.meta = x.optInt("meta");
                t.rating = (float) x.optDouble("rating", 0);
                t.ratings = x.optInt("ratings");
                t.ocId = x.optInt("oc");
                t.critics = x.optInt("critics");
                t.rank = x.optInt("rank");
                t.country = x.optString("country");
                t.countryName = x.optString("countryName");
                t.countryRank = x.optInt("countryRank");
                t.countryTied = x.optInt("countryTied");
                t.indiaRank = x.optInt("india");
                t.psLabel = x.optString("psLabel");
                t.psNo1 = x.optInt("psNo1");
                t.psTop10 = x.optInt("psTop10");
                t.rankSaved = x.optString("rankSaved");
                r.items.add(t);
            }
            if (!r.items.isEmpty()) out.add(r);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
