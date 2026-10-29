package io.ibtzy.tvlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** For You's Movies V1 texts, without Android: a focused card's genres and rating, its description, row headings. */
public final class MoviesLogic {
    private MoviesLogic() {
    }

    /** At most 2 of TMDB's genre names, short ("Science Fiction" → "Sci-fi"), each once: "Sci-fi · Thriller". */
    public static String genres(String[] names) {
        List<String> out = new ArrayList<>();
        if (names != null) for (String n : names) {
            String s = shortGenre(n);
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
            if (out.size() == 2) break;
        }
        return String.join(" · ", out);
    }

    private static String shortGenre(String name) {
        String n = name == null ? "" : name.trim();
        switch (n) {
            case "Science Fiction":
            case "Sci-Fi & Fantasy": return "Sci-fi";
            case "Action & Adventure": return "Action";
            case "War & Politics": return "War";
            case "TV Movie": return "";
            default: return n;
        }
    }

    /** TMDB's 0–10 average with one decimal ("7.6"); "" when there's none or it isn't a real score. */
    public static String rating(double vote) {
        if (Double.isNaN(vote) || vote <= 0 || vote > 10) return "";
        return String.format(Locale.ROOT, "%.1f", Math.round(vote * 10) / 10.0);
    }

    /** The overview as one paragraph; "" when missing. */
    public static String description(String overview) {
        return overview == null ? "" : overview.trim().replaceAll("\\s+", " ");
    }

    /** A row's heading in two parts: its first word (upright) and the rest (italic). */
    public static String[] heading(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        int space = n.indexOf(' ');
        return space < 0 ? new String[] {n, ""} : new String[] {n.substring(0, space), n.substring(space + 1)};
    }
}
