package io.ibtzy.tvlauncher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trakt (trakt.tv): your watch history, for Explore. Signed in with Trakt's device code: the TV shows a short code,
 * you enter it at trakt.tv/activate. Only a Client ID (config.properties "trakt_client_id"): Trakt's newer apps have no
 * secret, and the device flow doesn't need one. The tokens live in a file shared by the settings and the home screen.
 * Trakt knows what is reported to it (Stremio, Trakt's own apps, marking titles watched): not what Netflix, Prime
 * Video or Crunchyroll play on the TV by themselves.
 */
final class Trakt {
    private Trakt() {}

    private static final String API = "https://api.trakt.tv";
    /** OAuth: Trakt's API host, or its newer sign-in host. */
    private static final String[] AUTH = {"https://api.trakt.tv", "https://auth.trakt.tv"};
    private static final String REDIRECT = "http://localhost/callback";

    static boolean configured() {
        return !Config.TRAKT_CLIENT_ID.trim().isEmpty();
    }

    private static String clientId() {
        return Config.TRAKT_CLIENT_ID.trim();
    }

    // MARK: - Tokens (a file: the settings and the home screen are separate processes)

    private static File tokenFile(Context c) { return new File(c.getFilesDir(), "trakt.json"); }
    private static File tasteFile(Context c) { return new File(c.getFilesDir(), "trakt-taste.json"); }

    static synchronized JSONObject tokens(Context c) {
        File f = tokenFile(c);
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static synchronized void saveTokens(Context c, JSONObject t) {
        write(tokenFile(c), t.toString());
    }

    static boolean signedIn(Context c) {
        return configured() && !tokens(c).optString("access_token").isEmpty();
    }

    static String user(Context c) {
        return tokens(c).optString("user");
    }

    static void signOut(Context c) {
        tokenFile(c).delete();
        tasteFile(c).delete();
        status = "";
        userCode = "";
    }

    // MARK: - Signing in (device code)

    static volatile String userCode = "", verifyUrl = "", status = "";
    static volatile boolean signingIn;
    private static volatile int attempt;

    /** Asks Trakt for a code, then waits (in the background) for it to be entered. `changed` runs on each step. */
    static void startSignIn(Context context, Runnable changed) {
        if (!configured() || signingIn) return;
        final Context c = context.getApplicationContext();
        final int mine = ++attempt;
        signingIn = true;
        userCode = "";
        status = Ui.tr("Getting a code…");
        changed.run();
        new Thread(() -> {
            try {
                JSONObject code = null;
                String base = null;
                IOException last = null;
                for (String host : AUTH) {
                    try {
                        code = post(host + "/oauth/device/code", new JSONObject().put("client_id", clientId()));
                        base = host;
                        break;
                    } catch (IOException e) {
                        last = e;
                    }
                }
                if (code == null) throw last != null ? last : new IOException("no code");
                userCode = code.optString("user_code");
                verifyUrl = code.optString("verification_url", "https://trakt.tv/activate").replaceFirst("^https?://", "");
                status = "";
                changed.run();
                long until = System.currentTimeMillis() + code.optLong("expires_in", 600) * 1000;
                int every = Math.max(1, code.optInt("interval", 5));
                String device = code.optString("device_code");
                while (mine == attempt && System.currentTimeMillis() < until) {
                    Thread.sleep(every * 1000L);
                    HttpURLConnection h = open(base + "/oauth/device/token", "POST", null);
                    send(h, new JSONObject().put("code", device).put("client_id", clientId()));
                    int http = h.getResponseCode();
                    if (http == 200) {
                        JSONObject t = new JSONObject(read(h.getInputStream()));
                        t.put("created", System.currentTimeMillis()).put("base", base);
                        saveTokens(c, t);
                        try {
                            JSONObject me = new JSONObject(getRaw(c, "/users/settings"));
                            t.put("user", me.optJSONObject("user") == null ? "" : me.optJSONObject("user").optString("username"));
                            saveTokens(c, t);
                        } catch (Exception ignored) {
                        }
                        userCode = "";
                        status = "";
                        changed.run();
                        return;
                    }
                    h.disconnect();
                    if (http == 400) continue;                 // not entered yet
                    if (http == 429) { every++; continue; }    // asked to slow down
                    status = http == 418 ? Ui.tr("Sign-in was declined on Trakt.")
                            : http == 410 ? Ui.tr("The code expired. Press OK for a new one.")
                            : Ui.trf("Trakt answered {code}. Press OK to try again.", "code", http);
                    userCode = "";
                    changed.run();
                    return;
                }
                if (mine == attempt) {
                    status = Ui.tr("The code expired. Press OK for a new one.");
                    userCode = "";
                    changed.run();
                }
            } catch (Exception e) {
                status = Ui.trf("Couldn't reach Trakt: {error}", "error", String.valueOf(e.getMessage()));
                userCode = "";
                changed.run();
            } finally {
                if (mine == attempt) signingIn = false;
            }
        }, "trakt-sign-in").start();
    }

    /** A valid access token (renewed a day before it ends, with the refresh token), or null. */
    private static String accessToken(Context c) throws Exception {
        JSONObject t = tokens(c);
        String access = t.optString("access_token");
        if (access.isEmpty()) return null;
        long created = t.optLong("created_at", 0) > 0 ? t.optLong("created_at") * 1000 : t.optLong("created");
        long ends = created + t.optLong("expires_in", 0) * 1000;
        if (t.optLong("expires_in", 0) > 0 && System.currentTimeMillis() < ends - 24 * 3600_000L) return access;
        String base = t.optString("base", AUTH[0]);
        JSONObject fresh = post(base + "/oauth/token", new JSONObject().put("refresh_token", t.optString("refresh_token"))
                .put("client_id", clientId()).put("redirect_uri", REDIRECT).put("grant_type", "refresh_token"));
        fresh.put("created", System.currentTimeMillis()).put("base", base).put("user", t.optString("user"));
        saveTokens(c, fresh);
        return fresh.optString("access_token");
    }

    // MARK: - Your taste, for Explore

    /** What Trakt says about you, in TMDB's terms: what you've watched, your shows, the genres you watch most. */
    static final class Taste {
        final Set<String> watchedMovies = new HashSet<>();       // "movie:603"
        final List<Integer> shows = new ArrayList<>();           // TMDB ids, most recently watched first
        final Map<Integer, Integer> genres = new HashMap<>();    // TMDB genre id → how often
        final Map<String, Integer> langs = new HashMap<>();      // original language ("hi", "en"…) → how often
        int seen;                                                // titles the genres came from
        boolean isEmpty() { return watchedMovies.isEmpty() && shows.isEmpty() && genres.isEmpty(); }
    }

    /** Trakt's genre slugs → TMDB's movie and TV genre ids. */
    private static final Map<String, int[]> GENRES = new HashMap<>();
    static {
        GENRES.put("action", new int[] {28, 10759});
        GENRES.put("adventure", new int[] {12, 10759});
        GENRES.put("animation", new int[] {16});
        GENRES.put("anime", new int[] {16});
        GENRES.put("comedy", new int[] {35});
        GENRES.put("crime", new int[] {80});
        GENRES.put("documentary", new int[] {99});
        GENRES.put("drama", new int[] {18});
        GENRES.put("family", new int[] {10751});
        GENRES.put("fantasy", new int[] {14, 10765});
        GENRES.put("history", new int[] {36});
        GENRES.put("horror", new int[] {27});
        GENRES.put("music", new int[] {10402});
        GENRES.put("mystery", new int[] {9648});
        GENRES.put("romance", new int[] {10749});
        GENRES.put("science-fiction", new int[] {878, 10765});
        GENRES.put("thriller", new int[] {53});
        GENRES.put("war", new int[] {10752, 10768});
        GENRES.put("western", new int[] {37});
        GENRES.put("reality", new int[] {10764});
        GENRES.put("children", new int[] {10762});
        GENRES.put("superhero", new int[] {28, 878});
    }

    /**
     * Reads your Trakt history again (in the background, with Explore's own lookups): watched movies, the shows you
     * watched in the last year, and the genres of your last 100 plays. Saved for the home screen to read.
     */
    static void refreshTaste(Context c) {
        if (!signedIn(c)) return;
        try {
            JSONObject out = new JSONObject();
            JSONArray movies = new JSONArray(), shows = new JSONArray();
            JSONArray watched = new JSONArray(getRaw(c, "/sync/watched/movies"));
            for (int i = 0; i < watched.length(); i++) {
                JSONObject m = watched.getJSONObject(i).optJSONObject("movie");
                int id = m == null || m.optJSONObject("ids") == null ? 0 : m.optJSONObject("ids").optInt("tmdb");
                if (id > 0) movies.put(id);
            }
            JSONArray watchedShows = new JSONArray(getRaw(c, "/sync/watched/shows?extended=noseasons"));
            List<JSONObject> recent = new ArrayList<>();
            String yearAgo = Recs.day(-365);
            for (int i = 0; i < watchedShows.length(); i++) {
                JSONObject o = watchedShows.getJSONObject(i);
                if (o.optString("last_watched_at", "").compareTo(yearAgo) >= 0) recent.add(o);
            }
            java.util.Collections.sort(recent, (a, b) -> b.optString("last_watched_at").compareTo(a.optString("last_watched_at")));
            for (JSONObject o : recent) {
                JSONObject s = o.optJSONObject("show");
                int id = s == null || s.optJSONObject("ids") == null ? 0 : s.optJSONObject("ids").optInt("tmdb");
                if (id > 0 && shows.length() < 25) shows.put(id);
            }
            JSONObject genres = new JSONObject(), langs = new JSONObject();
            JSONArray history = new JSONArray(getRaw(c, "/sync/history?limit=100&extended=full"));
            int seen = 0;
            for (int i = 0; i < history.length(); i++) {
                JSONObject h = history.getJSONObject(i);
                JSONObject item = h.optJSONObject("movie") != null ? h.optJSONObject("movie") : h.optJSONObject("show");
                String lang = item == null ? "" : item.optString("language", "");
                if (!lang.isEmpty() && !"null".equals(lang)) langs.put(lang, langs.optInt(lang) + 1);
                JSONArray g = item == null ? null : item.optJSONArray("genres");
                if (g == null) continue;
                seen++;
                for (int j = 0; j < g.length(); j++) {
                    int[] ids = GENRES.get(g.optString(j));
                    for (int k = 0; ids != null && k < ids.length; k++) {
                        String key = String.valueOf(ids[k]);
                        genres.put(key, genres.optInt(key) + 1);
                    }
                }
            }
            out.put("movies", movies).put("shows", shows).put("genres", genres).put("langs", langs).put("seen", seen).put("at", System.currentTimeMillis());
            write(tasteFile(c), out.toString());
            AppLog.w("trakt: " + movies.length() + " watched movies, " + shows.length() + " recent shows, " + seen + " plays read");
        } catch (Exception e) {
            AppLog.w("trakt: " + e.getMessage());
        }
    }

    /** The last Taste read (empty when not signed in or not read yet). */
    static Taste taste(Context c) {
        Taste t = new Taste();
        if (!signedIn(c)) return t;
        File f = tasteFile(c);
        if (!f.exists()) return t;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(read(in));
            JSONArray m = o.optJSONArray("movies"), s = o.optJSONArray("shows");
            for (int i = 0; m != null && i < m.length(); i++) t.watchedMovies.add("movie:" + m.optInt(i));
            for (int i = 0; s != null && i < s.length(); i++) t.shows.add(s.optInt(i));
            JSONObject g = o.optJSONObject("genres");
            if (g != null) {
                java.util.Iterator<String> keys = g.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    t.genres.put(Integer.parseInt(key), g.optInt(key));
                }
            }
            t.seen = o.optInt("seen");
            JSONObject l = o.optJSONObject("langs");
            if (l != null) {
                java.util.Iterator<String> keys = l.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    t.langs.put(key, l.optInt(key));
                }
            }
        } catch (Exception ignored) {
        }
        return t;
    }

    // MARK: - HTTP

    private static String getRaw(Context c, String path) throws Exception {
        String token = accessToken(c);
        HttpURLConnection h = open(API + path, "GET", token);
        try {
            int code = h.getResponseCode();
            if (code != 200) throw new IOException("Trakt returned HTTP " + code + " for " + path.replaceAll("\\?.*", ""));
            return read(h.getInputStream());
        } finally {
            h.disconnect();
        }
    }

    /** A public list (trending, trending searches): only the Client ID, no sign-in. */
    static JSONArray getPublic(String path) throws Exception {
        if (!configured()) throw new IOException("no trakt_client_id");
        HttpURLConnection h = open(API + path, "GET", null);
        try {
            int code = h.getResponseCode();
            if (code != 200) throw new IOException("Trakt returned HTTP " + code + " for " + path.replaceAll("\\?.*", ""));
            return new JSONArray(read(h.getInputStream()));
        } finally {
            h.disconnect();
        }
    }

    private static JSONObject post(String url, JSONObject body) throws Exception {
        HttpURLConnection h = open(url, "POST", null);
        try {
            send(h, body);
            int code = h.getResponseCode();
            if (code != 200 && code != 201) throw new IOException("Trakt returned HTTP " + code);
            return new JSONObject(read(h.getInputStream()));
        } finally {
            h.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method, String token) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        h.setRequestProperty("Content-Type", "application/json");
        h.setRequestProperty("trakt-api-version", "2");
        h.setRequestProperty("trakt-api-key", clientId());
        h.setRequestProperty("User-Agent", "ibtzyTVLauncher");
        if (token != null) h.setRequestProperty("Authorization", "Bearer " + token);
        return h;
    }

    private static void send(HttpURLConnection h, JSONObject body) throws IOException {
        h.setDoOutput(true);
        try (OutputStream out = h.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(File f, String text) {
        File part = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        part.renameTo(f);
    }
}
