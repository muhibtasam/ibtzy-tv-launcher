package io.ibtzy.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;

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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * For You: a global discovery feed, independent of the streaming apps on the TV. Four rows:
 * - Trending this week: TMDB's weekly trending (movies and series, anime among them), its top 10 in TMDB's order.
 * - Popular searches: Trakt's recently searched titles. If Trakt doesn't answer that, the row is Trakt's most watched
 *   this week, named so (never called "searches").
 * - Upcoming: movies first released and series premiering in the next 120 days (TMDB), by current popularity.
 * - Most anticipated: Trakt's anticipated lists (ranked by how many people's lists a title is on).
 * Each title's availability (an indicator only, never a filter): a known service streaming it in tmdb_region (anime:
 * Crunchyroll in India too, as the Home tab does); else "in cinemas" only for a confirmed theatrical release in
 * tmdb_region within the last 120 days; else nothing. Cached for 6 hours; the last good feed stays when a refresh fails.
 */
final class Discover {
    interface Listener { void onDiscover(); }

    static final class Title {
        int id;
        String kind;            // "movie" or "tv"
        String title = "", backdrop = "", poster = "", date = "";
        boolean anime;
        String app;             // the service it streams on in tmdb_region (netflix, prime, crunchyroll…), or null
        int moreApps;           // other services streaming it too
        boolean cinema;         // a confirmed theatrical release in tmdb_region (in the last 120 days, or upcoming)
        // Games (Games.java; kind "game"): their platforms and genre, the Metascore RAWG lists (0: none), how many
        // RAWG users added it (its popularity).
        String platforms = "", genres = "";
        // Movies and shows (Movies V1): TMDB's overview and average (0–10, 0: none); genres above, short ("Sci-fi · Drama").
        String overview = "";
        float vote;
        int score, added;
        String shots = "";      // games: up to 5 screenshot addresses, one per line (the hero's strip)
        String hero = "", logo = "";   // featured games: SteamGridDB's top hero (key art) and logo (transparent PNG)
        String tier = "";       // Critics' picks: OpenCritic's tier ("Mighty", "Strong"…); score is its Top Critic Average
        // Games' scores, each from its own source (shown with it; none: "Not rated"):
        int meta;               // Metacritic, as RAWG lists it
        float rating;           // RAWG players' average (0–5), from `ratings` players
        int ratings;
        int ocId, critics;      // OpenCritic's id and review count (score: its Top Critic Average, tier: its tier)
        int rank;               // Popular: its place
        // Games on PlayStation: where the PS Store best-seller chart (pre-orders for games not out yet) has it highest
        // today (ps-store-db.live): the country's code and name, its rank there, how many other stores share it, and when.
        String country = "", countryName = "", rankSaved = "";
        int countryRank, countryTied;
        int indiaRank;
        // PS Store pre-order / sales strength (ps-store-db.live; it has no player or wishlist counts): its own label
        // ("Extremely strong preorders"), and in how many countries' stores it's #1 / top 10 today.
        String psLabel = "";
        int psNo1, psTop10;          // its place on India's PS Store chart today (0: not on it): "Trending in India"
    }

    static final class Row {
        String key, name;
        final List<Title> items = new ArrayList<>();
    }

    static final String TRENDING = "trending", SEARCHES = "searches", UPCOMING = "upcoming", ANTICIPATED = "anticipated";
    private static final long TTL = 6 * 3600_000L, RETRY = 15 * 60_000L;
    private static final int VERSION = 1;
    private static final int DETAILS = 2;   // titles carry genres, rating and description (Movies V1)
    private static final String API = "https://api.themoviedb.org/3", IMG = "https://image.tmdb.org/t/p/";

    private static Discover instance;

    static synchronized Discover get(Context c) {
        if (instance == null) instance = new Discover(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Recs.lowPriority(1), images = Recs.lowPriority(2);
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Row> rows = new ArrayList<>();
    private volatile long fetchedAt, failedAt;
    private volatile boolean fetching;
    private volatile String error = "";

    private Discover(Context c) {
        context = c;
        try {
            File f = cacheFile();
            if (f.exists()) {
                JSONObject o = new JSONObject(read(new FileInputStream(f)));
                if (o.optInt("version") == VERSION && o.optString("region").equals(Recs.REGION)) {
                    rows = parse(o.getJSONArray("rows"));
                    // Saved before titles kept their genres, rating and description: shown now, fetched again at once.
                    fetchedAt = o.optInt("details") >= DETAILS ? o.optLong("at") : 0;
                }
            }
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    List<Row> rows() { return rows; }
    boolean loading() { return rows.isEmpty() && (fetching || failedAt == 0); }
    String error() { return error; }

    private void changed() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onDiscover(); });
    }

    /** Games' rows changed (they're drawn by the same listeners). */
    void notifyListeners() {
        changed();
    }

    /** Fetches when the feed is older than 6 hours (or missing); a failure waits 15 minutes before trying again. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (fetching || !Recs.hasKey(context)) return;
        boolean stale = rows.isEmpty() || now - fetchedAt > TTL || now < fetchedAt;
        if (!stale || (failedAt != 0 && now - failedAt < RETRY && now >= failedAt)) return;
        fetching = true;
        changed();
        io.execute(() -> {
            try {
                List<Row> fresh = fetch();
                if (fresh.isEmpty()) throw new IOException("TMDB and Trakt returned nothing");
                rows = fresh;
                fetchedAt = System.currentTimeMillis();
                failedAt = 0;
                error = "";
                save();
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("for you: " + error);
            } finally {
                fetching = false;
                changed();
            }
        });
    }

    // MARK: - The four rows

    private List<Row> fetch() throws Exception {
        ExecutorService pool = Recs.lowPriority(6);
        try {
            Future<Row> trending = pool.submit(this::trending);
            Future<Row> searches = pool.submit(this::searches);
            Future<Row> upcoming = pool.submit(this::upcomingRow);
            Future<Row> anticipated = pool.submit(this::anticipated);
            List<Row> out = new ArrayList<>();
            for (Future<Row> f : new Future[] {trending, searches, upcoming, anticipated}) {
                try {
                    Row r = f.get();
                    if (r != null && !r.items.isEmpty()) out.add(r);
                } catch (Exception e) {
                    AppLog.w("for you row: " + e.getCause());
                }
            }
            // Every title's details (images, dates, availability), 6 at a time; titles without artwork leave.
            Map<String, Title> all = new LinkedHashMap<>();
            for (Row r : out) for (Title t : r.items) all.put(t.kind + ":" + t.id, t);
            List<Future<?>> jobs = new ArrayList<>();
            Map<String, JSONObject> details = new java.util.concurrent.ConcurrentHashMap<>();
            for (Title t : all.values()) {
                jobs.add(pool.submit(() -> {
                    try {
                        String extra = "movie".equals(t.kind) ? "watch/providers,release_dates" : "watch/providers";
                        details.put(t.kind + ":" + t.id, tmdb("/" + t.kind + "/" + t.id + "?language=en-US&append_to_response=" + extra));
                    } catch (Exception ignored) {
                    }
                }));
            }
            for (Future<?> j : jobs) {
                try {
                    j.get();
                } catch (Exception ignored) {
                }
            }
            for (Row r : out) {
                List<Title> kept = new ArrayList<>();
                for (Title t : r.items) {
                    JSONObject d = details.get(t.kind + ":" + t.id);
                    if (d != null) apply(t, d);
                    boolean art = TRENDING.equals(r.key) ? !t.backdrop.isEmpty() : !t.poster.isEmpty() || !t.backdrop.isEmpty();
                    if (art && !t.title.isEmpty()) kept.add(t);
                }
                r.items.clear();
                r.items.addAll(kept);
            }
            out.removeIf(r -> r.items.isEmpty());
            StringBuilder log = new StringBuilder();
            for (Row r : out) log.append(r.key).append(' ').append(r.items.size()).append("; ");
            AppLog.w("for you: " + log);
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** TMDB's weekly trending, movies and series together, its first 10 in its own order. */
    private Row trending() throws Exception {
        Row r = row(TRENDING, Ui.tr("Trending this week"));
        JSONArray a = tmdb("/trending/all/week?language=en-US").getJSONArray("results");
        for (int i = 0; i < a.length() && r.items.size() < 10; i++) {
            JSONObject o = a.getJSONObject(i);
            String kind = o.optString("media_type");
            if (!"movie".equals(kind) && !"tv".equals(kind)) continue;
            r.items.add(fromTmdb(o, kind));
        }
        return r;
    }

    /** Trakt's recently searched titles; if Trakt won't give them, its most watched this week, named so. */
    private Row searches() throws Exception {
        if (!Trakt.configured()) return null;
        Row r = row(SEARCHES, Ui.tr("Popular searches"));
        try {
            interleave(r, Trakt.getPublic("/search/recent_by_id/global/movies?limit=20"),
                    Trakt.getPublic("/search/recent_by_id/global/shows?limit=20"), 12);
        } catch (Exception e) {
            AppLog.w("for you searches: " + e.getMessage());
        }
        if (r.items.isEmpty()) {
            r.name = Ui.tr("Most watched this week");
            interleave(r, Trakt.getPublic("/movies/watched/weekly?limit=12"), Trakt.getPublic("/shows/watched/weekly?limit=12"), 12);
        }
        return r;
    }

    /** Movies and series coming out in the next 120 days, by TMDB's current popularity, alternating. */
    private Row upcomingRow() throws Exception {
        Row r = row(UPCOMING, Ui.tr("Coming soon"));
        String from = Recs.day(1), to = Recs.day(120);
        JSONArray movies = tmdb("/discover/movie?language=en-US&include_adult=false&sort_by=popularity.desc"
                + "&primary_release_date.gte=" + from + "&primary_release_date.lte=" + to).getJSONArray("results");
        JSONArray shows = tmdb("/discover/tv?language=en-US&include_adult=false&sort_by=popularity.desc"
                + "&first_air_date.gte=" + from + "&first_air_date.lte=" + to).getJSONArray("results");
        for (int i = 0; r.items.size() < 12 && (i < movies.length() || i < shows.length()); i++) {
            if (i < movies.length()) r.items.add(fromTmdb(movies.getJSONObject(i), "movie"));
            if (r.items.size() < 12 && i < shows.length()) r.items.add(fromTmdb(shows.getJSONObject(i), "tv"));
        }
        return r;
    }

    /** Trakt's most anticipated (on the most people's lists), movies and series by that count. */
    private Row anticipated() throws Exception {
        if (!Trakt.configured()) return null;
        Row r = row(ANTICIPATED, Ui.tr("Most anticipated"));
        List<Object[]> both = new ArrayList<>();
        for (String path : new String[] {"/movies/anticipated?limit=12", "/shows/anticipated?limit=12"}) {
            JSONArray a = Trakt.getPublic(path);
            for (int i = 0; i < a.length(); i++) {
                JSONObject row = a.optJSONObject(i);
                Title t = row == null ? null : fromTrakt(row);
                if (t != null) both.add(new Object[] {row.optInt("list_count"), t});
            }
        }
        both.sort((x, y) -> Integer.compare((Integer) y[0], (Integer) x[0]));
        for (Object[] o : both) if (r.items.size() < 12) r.items.add((Title) o[1]);
        return r;
    }

    private static Row row(String key, String name) {
        Row r = new Row();
        r.key = key;
        r.name = name;
        return r;
    }

    private static void interleave(Row r, JSONArray a, JSONArray b, int max) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; r.items.size() < max && (i < a.length() || i < b.length()); i++) {
            for (JSONArray list : new JSONArray[] {a, b}) {
                if (i >= list.length() || r.items.size() >= max) continue;
                Title t = fromTrakt(list.optJSONObject(i));
                if (t != null && seen.add(t.kind + ":" + t.id)) r.items.add(t);
            }
        }
    }

    private static Title fromTmdb(JSONObject o, String kind) {
        Title t = new Title();
        t.id = o.optInt("id");
        t.kind = kind;
        t.title = o.optString("movie".equals(kind) ? "title" : "name");
        t.backdrop = clean(o.optString("backdrop_path"));
        t.poster = clean(o.optString("poster_path"));
        t.date = clean(o.optString("movie".equals(kind) ? "release_date" : "first_air_date"));
        return t;
    }

    private static Title fromTrakt(JSONObject row) {
        if (row == null) return null;
        JSONObject item = row.optJSONObject("movie");
        String kind = "movie";
        if (item == null) {
            item = row.optJSONObject("show");
            kind = "tv";
        }
        JSONObject ids = item == null ? null : item.optJSONObject("ids");
        int tmdb = ids == null ? 0 : ids.optInt("tmdb");
        if (tmdb <= 0) return null;
        Title t = new Title();
        t.id = tmdb;
        t.kind = kind;
        t.title = item.optString("title");
        return t;
    }

    /** Details: title, images, anime, date, and availability (an indicator only). */
    private static void apply(Title t, JSONObject d) {
        String name = d.optString("movie".equals(t.kind) ? "title" : "name");
        if (!name.isEmpty()) t.title = name;
        if (t.backdrop.isEmpty()) t.backdrop = clean(d.optString("backdrop_path"));
        if (t.poster.isEmpty()) t.poster = clean(d.optString("poster_path"));
        if (t.date.isEmpty()) t.date = clean(d.optString("movie".equals(t.kind) ? "release_date" : "first_air_date"));
        JSONArray genres = d.optJSONArray("genres");
        boolean animation = false;
        String[] names = new String[genres == null ? 0 : genres.length()];
        for (int i = 0; genres != null && i < genres.length(); i++) {
            JSONObject g = genres.optJSONObject(i);
            if (g == null) continue;
            animation |= g.optInt("id") == 16;
            names[i] = g.optString("name", "");
        }
        t.genres = MoviesLogic.genres(names);
        t.overview = MoviesLogic.description(d.optString("overview", ""));
        double vote = d.optDouble("vote_average", 0);
        t.vote = Double.isNaN(vote) ? 0 : (float) vote;
        t.anime = animation && "ja".equals(d.optString("original_language"));
        JSONObject results = d.optJSONObject("watch/providers") == null ? null : d.optJSONObject("watch/providers").optJSONObject("results");
        List<String> apps = services(results, Recs.REGION);
        if (apps.isEmpty() && t.anime) {
            for (String s : services(results, Recs.CRUNCHYROLL_REGION)) if ("crunchyroll".equals(s)) apps.add(s);
        }
        t.app = null;
        t.moreApps = 0;
        for (String s : apps) if (t.app == null || ("other".equals(t.app) && !"other".equals(s))) t.app = s;
        if (t.app != null) t.moreApps = apps.size() - 1;
        t.cinema = false;
        if (t.app == null && "movie".equals(t.kind)) {
            String start = Recs.day(-120), today = Recs.day(0), soon = Recs.day(120);
            JSONObject rd = d.optJSONObject("release_dates");
            JSONArray countries = rd == null ? null : rd.optJSONArray("results");
            for (int i = 0; countries != null && i < countries.length(); i++) {
                JSONObject c = countries.optJSONObject(i);
                if (c == null || !Recs.REGION.equals(c.optString("iso_3166_1"))) continue;
                JSONArray dates = c.optJSONArray("release_dates");
                for (int j = 0; dates != null && j < dates.length(); j++) {
                    JSONObject x = dates.optJSONObject(j);
                    String when = x == null ? "" : x.optString("release_date", "");
                    if (x == null || x.optInt("type") != 3 || when.length() < 10) continue;
                    when = when.substring(0, 10);
                    if (when.compareTo(start) >= 0 && when.compareTo(soon) <= 0) {
                        t.cinema = true;
                        if (when.compareTo(today) > 0) t.date = when;   // its date in this country
                    }
                }
            }
        }
    }

    /** The services streaming it (subscription) in a country: known keys, "other" for the rest. */
    private static List<String> services(JSONObject results, String region) {
        List<String> out = new ArrayList<>();
        JSONObject r = results == null ? null : results.optJSONObject(region);
        JSONArray flat = r == null ? null : r.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length(); i++) {
            String n = flat.optJSONObject(i).optString("provider_name").toLowerCase(Locale.ROOT);
            String key = TitleLaunch.service(n);   // the same service keys as the app and its launcher
            if (key == null) key = "other";
            if (!out.contains(key) || "other".equals(key)) out.add(key);
        }
        return out;
    }

    private static String clean(String s) {
        return s == null || "null".equals(s) ? "" : s;
    }

    /** "Dec 16" (this year) or "Jan 8, 2027". */
    static String shortDate(String date) {
        if (date == null || date.length() < 10) return "";
        try {
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1, Integer.parseInt(date.substring(8, 10)));
            boolean thisYear = Calendar.getInstance().get(Calendar.YEAR) == c.get(Calendar.YEAR);
            return new SimpleDateFormat(thisYear ? "MMM d" : "MMM d, yyyy", Ui.locale()).format(c.getTime());
        } catch (RuntimeException e) {
            return "";
        }
    }

    static boolean upcoming(Title t) {
        return t.date.length() >= 10 && t.date.substring(0, 10).compareTo(Recs.day(0)) > 0;
    }

    // MARK: - Network and cache

    private static JSONObject tmdb(String path) throws Exception {
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private File cacheFile() {
        return new File(context.getFilesDir(), "discover.json");
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Row r : rows) {
                JSONArray items = new JSONArray();
                for (Title t : r.items) {
                    items.put(new JSONObject().put("id", t.id).put("kind", t.kind).put("title", t.title)
                            .put("backdrop", t.backdrop).put("poster", t.poster).put("date", t.date).put("anime", t.anime)
                            .put("app", t.app == null ? "" : t.app).put("more", t.moreApps).put("cinema", t.cinema)
                            .put("genres", t.genres).put("overview", t.overview).put("vote", t.vote));
                }
                a.put(new JSONObject().put("key", r.key).put("name", r.name).put("items", items));
            }
            JSONObject o = new JSONObject().put("version", VERSION).put("details", DETAILS).put("region", Recs.REGION).put("at", fetchedAt).put("rows", a);
            try (FileOutputStream out = new FileOutputStream(cacheFile())) {
                out.write(o.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    private static List<Row> parse(JSONArray a) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String key = o.optString("key");
            Row r = row(key, UPCOMING.equals(key) ? Ui.tr("Coming soon") : o.optString("name"));
            JSONArray items = o.optJSONArray("items");
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject x = items.optJSONObject(j);
                Title t = new Title();
                t.id = x.optInt("id");
                t.kind = x.optString("kind");
                t.title = x.optString("title");
                t.backdrop = x.optString("backdrop");
                t.poster = x.optString("poster");
                t.date = x.optString("date");
                t.anime = x.optBoolean("anime");
                t.app = x.optString("app").isEmpty() ? null : x.optString("app");
                t.moreApps = x.optInt("more");
                t.cinema = x.optBoolean("cinema");
                t.genres = x.optString("genres", "");
                t.overview = x.optString("overview", "");
                double vote = x.optDouble("vote", 0);
                t.vote = Double.isNaN(vote) ? 0 : (float) vote;
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

    // MARK: - Card images: made once at the card's exact size (rounded, the bottom shaded for the title), in the background

    private final Map<String, Bitmap> cards = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 130; }   // tall + wide each
    };
    private final java.util.Set<String> pending = new java.util.HashSet<>();

    /**
     * The card for `path` (a TMDB image path: backdrop for wide cards, poster for tall ones) at w × h px, or null while
     * it's being made (the caller draws a placeholder; listeners hear when it's ready).
     */
    private final Map<String, Long> failedCards = new java.util.HashMap<>();   // guarded by `cards`

    Bitmap card(String path, boolean wide, int w, int h, float radius) {
        if (path == null || path.isEmpty() || w <= 0 || h <= 0) return null;
        String key = path + "@" + w + "x" + h;
        synchronized (cards) {
            Bitmap b = cards.get(key);
            if (b != null) return b;
            Long failedAt = failedCards.get(key);   // a picture that failed: not asked again for 5 minutes (it was every frame)
            if (failedAt != null && System.currentTimeMillis() - failedAt < 300_000L) return null;
            if (!pending.add(key)) return null;
        }
        images.execute(() -> {
            Bitmap out = null;
            try {
                // Whole addresses (Games): 1280 px when the crop needs more than 640 (a 16:9 picture cut to w × h needs
                // max(w, h·16/9) px across), else 640. TMDB paths: as before.
                boolean big = path.startsWith("https://") ? Math.max(w, h * 16f / 9f) > 640 : w >= 1000;
                Bitmap src = image(path, big ? "w1280" : wide ? "w780" : "w342");
                if (src != null) out = compose(src, w, h, radius);
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("for you image: " + e);
            }
            synchronized (cards) {
                pending.remove(key);
                // Mipmapped: the art is drawn smaller than it's decoded (a 1280 px picture on a 400 px card), and sampling it
                // without mipmaps read the whole texture every frame (Games 60.0 → 43.8 ms a frame on the TV, p50).
                if (out != null) {
                    out.setHasMipMap(true);
                    cards.put(key, out);
                }
                if (out == null) failedCards.put(key, System.currentTimeMillis());
                else failedCards.remove(key);
            }
            if (out != null) changed();
        });
        return null;
    }

    private static volatile boolean pruned;

    /**
     * The picture cache on disk (cache/discover: For You's and Games' art and logos, kept across restarts) stays
     * bounded: past 200 MB, the least recently used files go until it's under 150 MB. Once per start, off the UI.
     */
    private static void prune(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return;
        long total = 0;
        for (File f : all) total += f.length();
        if (total <= 200L << 20) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int gone = 0;
        for (File f : all) {
            if (total <= 150L << 20) break;
            long len = f.length();
            if (f.delete()) {
                total -= len;
                gone++;
            }
        }
        AppLog.w("image cache: " + gone + " old files removed");
    }

    private Bitmap image(String path, String size) throws IOException {
        File dir = new File(context.getCacheDir(), "discover");
        if (!dir.exists()) dir.mkdirs();
        if (!pruned) {
            pruned = true;
            prune(dir);
        }
        // A TMDB path, or a whole address (Games: RAWG's art, at 640 px from its resize service: enough for both cards).
        boolean whole = path.startsWith("https://");
        // A whole address: 1280 px for the big sizes (the Games hero's sharp art), 640 for cards.
        int px = "w1280".equals(size) ? 1280 : 640;
        File f = new File(dir, whole ? "web" + px + "-" + Integer.toHexString(path.hashCode()) + ".jpg" : size + path.replace('/', '_'));
        final long t0 = android.os.SystemClock.uptimeMillis();
        boolean downloaded = !f.exists();
        if (!downloaded) f.setLastModified(System.currentTimeMillis());   // used: the last to go when the cache is pruned
        if (!f.exists()) {
            HttpURLConnection c = (HttpURLConnection) new URL(whole ? Games.sized(path, px) : IMG + size + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException e) {
                f.delete();
                throw e;
            } finally {
                c.disconnect();
            }
        }
        // Big originals (SteamGridDB's 3840 px heroes): decoded at no more than 2048 px wide (a power-of-two step),
        // never at full size in memory.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        final long t1 = android.os.SystemClock.uptimeMillis();
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;   // (its default is 0: the step below would divide by zero and every image fail)
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 2048 && opts.inSampleSize < 16) opts.inSampleSize *= 2;
        if (bounds.outWidth > 2048 && opts.inSampleSize == 1) opts.inSampleSize = 2;
        Bitmap decoded = BitmapFactory.decodeFile(f.getPath(), opts);
        if (whole) Startup.games("img " + (downloaded ? "download " + (t1 - t0) + " ms " + f.length() / 1024 + " KB, " : "cached, ")
                + "decode " + (android.os.SystemClock.uptimeMillis() - t1) + " ms " + bounds.outWidth + "x" + bounds.outHeight
                + " /" + opts.inSampleSize + " " + Startup.noQuery(new URL(path)));
        return decoded;
    }

    private final java.util.Set<String> failedLogos = new java.util.HashSet<>();
    private final Map<String, Bitmap> logos = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 12; }
    };

    /**
     * A game's logo (a transparent PNG, Games' featured games), at most 1024 px wide, or null while it's loading
     * (listeners hear when it's ready). Kept with its transparency (not a card: no shading, no RGB_565).
     */
    Bitmap logo(String url) {
        if (url == null || url.isEmpty()) return null;
        String key = "logo:" + url;
        synchronized (logos) {
            Bitmap b = logos.get(url);
            if (b != null || failedLogos.contains(url)) return b;
        }
        synchronized (cards) {
            if (!pending.add(key)) return null;
        }
        images.execute(() -> {
            Bitmap out = null;
            try {
                Bitmap src = image(url, "logo");
                if (src != null && src.getWidth() > 1024) {
                    out = Bitmap.createScaledBitmap(src, 1024, Math.max(1, Math.round(1024f * src.getHeight() / src.getWidth())), true);
                    src.recycle();
                } else {
                    out = src;
                }
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("games logo: " + e);
            }
            synchronized (cards) {
                pending.remove(key);
            }
            synchronized (logos) {
                if (out != null) logos.put(url, out);
                else failedLogos.add(url);   // not again (the typed name stays)
            }
            if (out != null) changed();
        });
        return null;
    }

    /** Cut like CSS object-fit: cover (centred, a little above the middle, where faces are), rounded, shaded below. */
    private static Bitmap compose(Bitmap src, int w, int h, float radius) {
        float sw = src.getWidth(), sh = src.getHeight(), want = (float) w / h;
        float cw = sw, ch = sh, cx = 0, cy = 0;
        if (sw / sh > want) {
            cw = sh * want;
            cx = (sw - cw) / 2;
        } else {
            ch = sw / want;
            cy = Math.max(0, Math.min(sh - ch, 0.4f * sh - ch / 2));
        }
        // RGB_565: no transparency needed (square corners), half the memory of ARGB (each title has a tall and a wide
        // card now); the shading is dithered so it doesn't band.
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setRectToRect(new RectF(cx, cy, cx + cw, cy + ch), new RectF(0, 0, w, h), android.graphics.Matrix.ScaleToFit.FILL);
        shader.setLocalMatrix(m);
        p.setShader(shader);
        // Square corners: the home screen rounds the card as it draws it (a card's width animates, so its corners
        // can't be baked in).
        RectF r = new RectF(0, 0, w, h);
        c.drawRect(r, p);
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        shade.setShader(new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP));
        c.drawRect(r, shade);
        src.recycle();
        return out;
    }
}
