package io.ibtzy.tvlauncher;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * For You's Games V1 (Release Radar), without Android: the countdown to the most anticipated game, the radar's pins and
 * months, and the posters' texts ("4 days ago", "PS5 · PC · Action · ★4.6"). Dates are RAWG's "YYYY-MM-DD", local midnight.
 */
public final class GamesV1Logic {
    private GamesV1Logic() {
    }

    private static final long DAY = 86_400_000L, HOUR = 3_600_000L, MIN = 60_000L;
    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] MONTH_NAMES = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** "2026-11-13" → its local midnight (ms); -1 when missing or malformed. */
    public static long releaseMs(String date, TimeZone tz) {
        if (date == null || date.length() < 10 || date.charAt(4) != '-' || date.charAt(7) != '-') return -1;
        try {
            int y = Integer.parseInt(date.substring(0, 4)), m = Integer.parseInt(date.substring(5, 7)), d = Integer.parseInt(date.substring(8, 10));
            if (m < 1 || m > 12 || d < 1) return -1;
            Calendar c = Calendar.getInstance(tz, Locale.US);
            c.clear();
            c.set(y, m - 1, 1);
            if (d > c.getActualMaximum(Calendar.DAY_OF_MONTH)) return -1;
            c.set(Calendar.DAY_OF_MONTH, d);
            return c.getTimeInMillis();
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {days, hours, minutes} left (rounded down); all 0 once it's out; null without a date. */
    public static int[] countdown(long releaseMs, long nowMs) {
        if (releaseMs < 0) return null;
        long left = Math.max(0, releaseMs - nowMs);
        return new int[] {(int) (left / DAY), (int) (left % DAY / HOUR), (int) (left % HOUR / MIN)};
    }

    /** The countdown's next change: ms until its minute turns (60 s when exactly on one). */
    public static long msToNextMinute(long nowMs, long releaseMs) {
        long left = releaseMs - nowMs, r = left % MIN;
        return r <= 0 ? MIN : r;
    }

    /** Two digits at least ("06"); never negative. */
    public static String two(int n) {
        n = Math.max(0, n);
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /** The game to count down to: the most added of those not out before today (ties: the sooner); -1: none. */
    public static int anticipated(long[] releaseMs, int[] added, long nowMs) {
        if (releaseMs == null || added == null) return -1;
        int best = -1;
        for (int i = 0; i < releaseMs.length && i < added.length; i++) {
            if (releaseMs[i] < 0 || releaseMs[i] + DAY <= nowMs) continue;
            if (best < 0 || added[i] > added[best] || added[i] == added[best] && releaseMs[i] < releaseMs[best]) best = i;
        }
        return best;
    }

    /**
     * The same, by English Wikipedia's views of each game's article (the last 30 days: how many look it up before it's
     * out) when any game still to come has some; RAWG's added count otherwise.
     */
    public static int anticipated(long[] releaseMs, int[] added, int[] views, long nowMs) {
        if (releaseMs == null || added == null) return -1;
        boolean byViews = false;
        for (int i = 0; views != null && i < views.length && i < releaseMs.length; i++)
            byViews |= views[i] > 0 && releaseMs[i] >= 0 && releaseMs[i] + DAY > nowMs;
        if (!byViews) return anticipated(releaseMs, added, nowMs);
        int[] score = new int[Math.min(releaseMs.length, added.length)];
        for (int i = 0; i < score.length; i++) score[i] = i < views.length ? Math.max(0, views[i]) : 0;
        return anticipated(releaseMs, score, nowMs);
    }

    /**
     * Which search result is the game's own Wikipedia article: its name exactly (punctuation aside), alone or with a
     * "(… video game)" / "(<year>)" note; a note naming another year, or not a game, doesn't count. "": none.
     */
    public static String wikiArticle(String[] titles, String game, int year) {
        String want = key(game);
        if (titles == null || want.isEmpty()) return "";
        String fallback = "";
        for (String t : titles) {
            if (t == null) continue;
            int open = t.lastIndexOf(" (");
            String name = open > 0 && t.endsWith(")") ? t.substring(0, open) : t;
            String note = open > 0 && t.endsWith(")") ? t.substring(open + 2, t.length() - 1).toLowerCase(Locale.ROOT) : "";
            if (!key(name).equals(want)) continue;
            if (note.isEmpty()) {
                if (fallback.isEmpty()) fallback = t;
                continue;
            }
            if (!note.contains("game")) continue;
            String digits = note.replaceAll("[^0-9]", "");
            if (digits.length() == 4 && !digits.equals(String.valueOf(year))) continue;
            if (digits.length() == 4) return t;   // its year: the best match
            if (fallback.isEmpty()) fallback = t;
        }
        return fallback;
    }

    /** Which IGDB result is the game: its exact name (punctuation aside), a release year within 3 of RAWG's; -1: none. */
    public static int igdbGame(String[] names, int[] years, String game, int year) {
        String want = key(game);
        if (names == null || want.isEmpty()) return -1;
        int best = -1;
        for (int i = 0; i < names.length; i++) {
            if (!key(names[i]).equals(want)) continue;
            int y = years != null && i < years.length ? years[i] : 0;
            if (y > 0 && year > 0 && Math.abs(y - year) > 3) continue;   // another game of the same name (early access: ≤ 3 years)
            if (y == year && year > 0) return i;
            if (best < 0) best = i;
        }
        return best;
    }

    /** The trailer to show: a launch trailer, else the newest named a trailer, else the first video; -1: none. */
    public static int igdbTrailer(String[] names) {
        if (names == null || names.length == 0) return -1;
        int last = -1;
        for (int i = 0; i < names.length; i++) {
            String n = names[i] == null ? "" : names[i].toLowerCase(Locale.ROOT);
            if (n.contains("launch") && n.contains("trailer")) return i;
            if (n.contains("trailer")) last = i;
        }
        return last >= 0 ? last : 0;
    }

    /** A wide artwork to show whole (4:3 or wider, 1280 px across at least); the first such, -1: none. */
    public static int igdbArtwork(int[] w, int[] h) {
        if (w == null || h == null) return -1;
        for (int i = 0; i < w.length && i < h.length; i++) if (w[i] >= 1280 && h[i] > 0 && w[i] >= 1.3f * h[i]) return i;
        return -1;
    }

    /** A Hype Track pin's size: 44 px, bigger the more people follow it (IGDB hypes), 100 at most. */
    public static int hypeSize(int hypes) {
        return Math.round(44 + Math.min(56, (float) Math.sqrt(Math.max(0, hypes)) * 1.6f));
    }

    /** The lit stretch's label from until(): "Today", "Tomorrow", "20 days"; "" for none. */
    public static String daysBadge(String until) {
        if (until == null || until.isEmpty()) return "";
        if (until.startsWith("in ")) return until.substring(3);
        return Character.toUpperCase(until.charAt(0)) + until.substring(1);
    }

    /** The top art's size: 16:9 (a trailer's picture whole, never cut), maxW wide at most, no taller than the room. */
    public static float[] artSize(float maxW, float roomH) {
        if (roomH <= 0 || maxW <= 0) return new float[] {0, 0};
        float h = Math.min(roomH, maxW * 9f / 16f);
        return new float[] {Math.round(h * 16f / 9f), Math.round(h)};
    }

    private static String key(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static long dayIndex(long ms, TimeZone tz) {
        return Math.floorDiv(ms + tz.getOffset(ms), DAY);
    }

    /** A poster's pill: "Out today", "Yesterday", "4 days ago", "Last week", "2 weeks ago"; "" when older or to come. */
    public static String ago(long releaseMs, long nowMs, TimeZone tz) {
        if (releaseMs < 0) return "";
        long days = dayIndex(nowMs, tz) - dayIndex(releaseMs, tz);
        if (days < 0 || days >= 35) return "";
        if (days == 0) return "Out today";
        if (days == 1) return "Yesterday";
        if (days < 7) return days + " days ago";
        if (days < 14) return "Last week";
        return days / 7 + " weeks ago";
    }

    /** A pin's card: "today", "tomorrow", "in 8 days"; "" when out or without a date. */
    public static String until(long releaseMs, long nowMs, TimeZone tz) {
        if (releaseMs < 0) return "";
        long days = dayIndex(releaseMs, tz) - dayIndex(nowMs, tz);
        if (days < 0) return "";
        return days == 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days";
    }

    /** "Nov 13"; "" without a date. */
    public static String shortDate(long ms, TimeZone tz) {
        if (ms < 0) return "";
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(ms);
        return MONTH_NAMES[c.get(Calendar.MONTH)] + " " + c.get(Calendar.DAY_OF_MONTH);
    }

    /** The radar's right end: the last game's date, 60 days from now at least. */
    public static long radarEnd(long[] releaseMs, long nowMs) {
        long end = nowMs + 60 * DAY;
        if (releaseMs != null) for (long r : releaseMs) end = Math.max(end, r);
        return end;
    }

    /**
     * Each pin's x on the line from x0 (now) to x1 (end), by its date; bad or past dates at x0. Pins never closer than
     * minGap (pushed right in date order, then back from x1 so none passes it).
     */
    public static float[] pinX(long[] releaseMs, long nowMs, long endMs, float x0, float x1, float minGap) {
        int n = releaseMs == null ? 0 : releaseMs.length;
        float[] xs = new float[n];
        Integer[] order = new Integer[n];
        double span = Math.max(1, endMs - nowMs);
        for (int i = 0; i < n; i++) {
            long r = releaseMs[i];
            xs[i] = r < 0 || r <= nowMs ? x0 : (float) (x0 + Math.min(1, (r - nowMs) / span) * (x1 - x0));
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> xs[a] != xs[b] ? Float.compare(xs[a], xs[b]) : Integer.compare(a, b));
        for (int j = 1; j < n; j++) xs[order[j]] = Math.max(xs[order[j]], xs[order[j - 1]] + minGap);
        for (int j = n - 1; j >= 0; j--) {
            float limit = j == n - 1 ? x1 : xs[order[j + 1]] - minGap;
            xs[order[j]] = Math.min(xs[order[j]], limit);
        }
        for (int j = 0; j < n; j++) xs[order[j]] = Math.round(xs[order[j]] * 1000) / 1000f;
        return xs;
    }

    /** The first day of each month after now, up to end (the radar's month marks). */
    public static long[] monthStarts(long nowMs, long endMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(nowMs);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            c.add(Calendar.MONTH, 1);
            if (c.getTimeInMillis() > endMs) break;
            out.add(c.getTimeInMillis());
        }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** "NOV". */
    public static String monthLabel(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(ms);
        return MONTHS[c.get(Calendar.MONTH)];
    }

    /** A focused poster's line: 2 platforms · genre · ★players' average (0–5, from 5 ratings): "PS5 · PC · Action · ★4.6". */
    public static String meta(String platforms, String genre, float rating, int ratings) {
        List<String> out = new ArrayList<>();
        if (platforms != null) for (String p : platforms.split("·")) {
            if (!p.trim().isEmpty() && out.size() < 2) out.add(p.trim());
        }
        if (genre != null && !genre.trim().isEmpty()) out.add(genre.trim());
        if (ratings >= 5 && rating > 0 && rating <= 5) out.add(String.format(Locale.US, "★%.1f", rating));
        return String.join(" · ", out);
    }

    /** "12.4K people want it" (RAWG's added count); "" under 50. */
    public static String wantIt(int added) {
        if (added < 50) return "";
        String n = added >= 1_000_000 ? String.format(Locale.US, "%.1fM", added / 1_000_000f).replace(".0M", "M")
                : added >= 1_000 ? String.format(Locale.US, "%.1fK", added / 1_000f).replace(".0K", "K") : String.valueOf(added);
        return n + " people want it";
    }

    /** The gold ring's score: OpenCritic's, else Metacritic's (1–100); 0: no ring. */
    public static int score(int openCritic, int meta) {
        if (openCritic > 0 && openCritic <= 100) return openCritic;
        return meta > 0 && meta <= 100 ? meta : 0;
    }
}
