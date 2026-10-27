package io.freemytv;

import java.util.regex.Pattern;

/**
 * The Games section's pure logic, kept free of Android so tools/tests/run.sh can test it on any JDK: the looping
 * rail's index and geometry, and checks on what the game APIs send (never trusted).
 */
public final class GamesLogic {
    private GamesLogic() {}

    /** A looping rail's item for step i (any int, negative too) in a row of n. */
    public static int wrap(int i, int n) {
        return n <= 0 ? 0 : Math.floorMod(i, n);
    }

    /**
     * Where a rail card starts, from the margin, at distance d (in cards) from the selected one: the selected card
     * (d 0) is wide at the margin, the next ones follow it, the previous one peeks in left of the margin. Continuous
     * in d, so as the rail's spring moves every card slides and none jumps.
     */
    public static float railX(float d, float normalW, float wideW, float gap) {
        float stride = normalW + gap;
        return d >= 0 ? d * stride + (wideW - normalW) * Math.min(d, 1) : d * stride;
    }

    /** A rail card's width at distance d: wide when selected, growing and shrinking with the move. */
    public static float railW(float d, float normalW, float wideW) {
        return normalW + (wideW - normalW) * Math.max(0, 1 - Math.abs(d));
    }

    /** A release's calendar column: "2026-11", or "2026 TBA" for a year-only date (RAWG lists those as Dec 31); "" none. */
    public static String calendarKey(String date) {
        if (isoDate(date).isEmpty()) return "";
        return date.startsWith("-12-31", 4) ? date.substring(0, 4) + " TBA" : date.substring(0, 7);
    }

    /**
     * Upcoming's release calendar: the games (indices into dates, which come soonest first) grouped into their month
     * columns, in order of first appearance; the first cols columns, at most per games each.
     */
    public static int[][] calendar(String[] dates, int cols, int per) {
        java.util.LinkedHashMap<String, java.util.List<Integer>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; dates != null && i < dates.length; i++) {
            String key = calendarKey(dates[i]);
            if (key.isEmpty()) continue;
            java.util.List<Integer> g = groups.get(key);
            if (g == null) {
                if (groups.size() >= cols) continue;
                groups.put(key, g = new java.util.ArrayList<>());
            }
            if (g.size() < per) g.add(i);
        }
        int[][] out = new int[groups.size()][];
        int j = 0;
        for (java.util.List<Integer> g : groups.values()) {
            out[j] = new int[g.size()];
            for (int i = 0; i < g.size(); i++) out[j][i] = g.get(i);
            j++;
        }
        return out;
    }

    private static final Pattern NOT_NAME = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** The same game's name in two stores ("Call of Duty®: Modern Warfare® 4" = "Call of Duty: Modern Warfare 4"). */
    public static boolean sameName(String a, String b) {
        if (a == null || b == null) return false;
        String x = NOT_NAME.matcher(a.toLowerCase(java.util.Locale.ROOT)).replaceAll("");
        String y = NOT_NAME.matcher(b.toLowerCase(java.util.Locale.ROOT)).replaceAll("");
        return !x.isEmpty() && x.equals(y);
    }

    private static final Pattern PROMO_PHRASE = Pattern.compile("(?i)join (our|the) discord|about (the|this) game|key features:?"),
            PROMO_SENTENCE = Pattern.compile("(?i)discord|wishlist|twitter|facebook|instagram|tiktok|youtube|newsletter"
                    + "|kickstarter|follow us|subscribe|https?://|www\\."),
            SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+"), LETTER = Pattern.compile("\\p{L}");

    /**
     * A description's summary for the rails: cleaned (see clean), without store and social promo ("JOIN OUR DISCORD",
     * "About the Game", "Wishlist now"), whole sentences up to max chars; one longer sentence is cut at a word.
     */
    public static String summary(String s, int max) {
        String t = SPACES.matcher(PROMO_PHRASE.matcher(clean(s, Integer.MAX_VALUE)).replaceAll(" ")).replaceAll(" ").trim();
        StringBuilder out = new StringBuilder();
        for (String sentence : SENTENCE_END.split(t)) {
            String x = sentence.trim();
            if (!LETTER.matcher(x).find() || PROMO_SENTENCE.matcher(x).find()) continue;
            if (out.length() + (out.length() > 0 ? 1 : 0) + x.length() > max) {
                if (out.length() == 0) {   // one long sentence: cut at a word (the owner's choice: never "…")
                    int cut = x.lastIndexOf(' ', max);
                    return fullStop(x.substring(0, cut > 0 ? cut : max));
                }
                break;
            }
            if (out.length() > 0) out.append(' ');
            out.append(x);
        }
        return fullStop(out.toString());
    }

    private static final Pattern TRAILING = Pattern.compile("[\\s,;:\u2014\u2013-]+$");

    /** Text that reads as a finished sentence: ends with . ! or ? (trailing commas and dashes become a full stop). */
    static String fullStop(String s) {
        String t = TRAILING.matcher(s).replaceAll("");
        if (t.isEmpty()) return "";
        char last = t.charAt(t.length() - 1);
        return last == '.' || last == '!' || last == '?' ? t : t + ".";
    }

    /** A score from an API: 0 when missing or not a number, else rounded into 0–100. */
    public static int score(double v) {
        if (Double.isNaN(v) || v <= 0) return 0;
        return (int) Math.min(100, Math.round(v));
    }

    /** An image address from an API: only https, no spaces, a sane length; else "". */
    public static String https(String url) {
        if (url == null || url.length() > 2048 || !url.startsWith("https://")) return "";
        for (int i = 0; i < url.length(); i++) if (url.charAt(i) <= ' ') return "";
        return url;
    }

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}.*");

    /** "2026-10-13" from an ISO date or time; else "". */
    public static String isoDate(String s) {
        return s != null && DATE.matcher(s).matches() ? s.substring(0, 10) : "";
    }

    private static final Pattern TAGS = Pattern.compile("<[^>]{0,200}>"), CONTROL = Pattern.compile("\\p{Cc}"),
            SPACES = Pattern.compile("\\s+");

    /** Text from an API, safe to draw: no tags or control characters, one space between words, at most max chars. */
    public static String clean(String s, int max) {
        if (s == null) return "";
        String t = SPACES.matcher(CONTROL.matcher(TAGS.matcher(s).replaceAll(" ")).replaceAll(" ")).replaceAll(" ").trim()
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        if (t.length() <= max) return t;
        int cut = t.lastIndexOf(' ', max - 1);
        return t.substring(0, cut > 0 ? cut : max - 1).trim() + "…";
    }
}
