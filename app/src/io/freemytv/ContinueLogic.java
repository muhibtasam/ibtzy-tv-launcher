package io.freemytv;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Predicate;

/**
 * Home's Continue row, without Android: which of the titles opened from the cards it shows, and their line. The TV
 * lets no app read where you stopped (only its own home screen may), so the line says when you opened the title, never
 * a made-up progress; the app itself resumes where you left off.
 */
public final class ContinueLogic {
    /** Cards in the row, and how long a title stays in it after it was opened. */
    public static final int MAX = 4;
    public static final long WINDOW_MS = 30L * 24 * 3600_000L;

    private ContinueLogic() {
    }

    /** One title from the history (Recs.history, newest first). */
    public static final class Entry {
        public final int id;
        public final String kind, app;
        public final long at;

        public Entry(int id, String kind, String app, long at) {
            this.id = id;
            this.kind = kind;
            this.app = app;
            this.at = at;
        }
    }

    /**
     * The row: newest first, at most MAX, each title once, opened in the last 30 days, its app still installed. A
     * broken entry (no id, an unknown kind, no time) is skipped.
     */
    public static List<Entry> select(List<Entry> history, long now, Predicate<String> installed) {
        List<Entry> out = new ArrayList<>();
        if (history == null) return out;
        Set<String> seen = new HashSet<>();
        for (Entry e : history) {
            if (out.size() >= MAX) break;
            if (e == null || e.id <= 0 || e.at <= 0 || !("movie".equals(e.kind) || "tv".equals(e.kind))) continue;
            if (now - e.at > WINDOW_MS) continue;
            if (e.app == null || e.app.isEmpty() || !installed.test(e.app)) continue;
            if (seen.add(e.kind + ":" + e.id)) out.add(e);
        }
        return out;
    }

    /** The card's line: "Season 2 · Opened yesterday", or for a movie just when it was opened. */
    public static String line(String kind, int season, long at, long now, TimeZone zone) {
        String opened = opened(at, now, zone);
        return "tv".equals(kind) && season > 0 ? "Season " + season + " · " + opened : opened;
    }

    /** "Opened just now", "5 min ago", "2 h ago" (today), "yesterday", a weekday this week, else the date. */
    public static String opened(long at, long now, TimeZone zone) {
        long ago = now - at;
        if (ago < 60_000L) return "Opened just now";   // the TV's clock moved back too: no "in the future"
        if (ago < 3600_000L) return "Opened " + ago / 60_000L + " min ago";
        Calendar then = Calendar.getInstance(zone, Locale.US), today = Calendar.getInstance(zone, Locale.US);
        then.setTimeInMillis(at);
        today.setTimeInMillis(now);
        int days = dayNumber(today) - dayNumber(then);
        if (days == 0) return "Opened " + ago / 3600_000L + " h ago";
        if (days == 1) return "Opened yesterday";
        if (days < 7) return "Opened " + then.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US);
        return "Opened " + then.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.US) + " " + then.get(Calendar.DAY_OF_MONTH);
    }

    /** Days since 1970 in the calendar's own zone (whole local days, so midnight is where "yesterday" starts). */
    private static int dayNumber(Calendar c) {
        long local = c.getTimeInMillis() + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET);
        return (int) Math.floorDiv(local, 86_400_000L);
    }
}
