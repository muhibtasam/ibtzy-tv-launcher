package io.ibtzy.tvlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * A card's name in at most maxLines lines of width maxW (no Android: the caller passes the text measure). Breaks at
 * spaces, a word longer than a line by letters; the last line ends in "…" when the rest doesn't fit. Never wider than
 * maxW: the name always stays inside its card.
 */
public final class TitleWrap {
    private static final String ELLIPSIS = "…";

    private TitleWrap() {
    }

    public static String[] wrap(String text, float maxW, ToDoubleFunction<String> width, int maxLines) {
        String rest = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (rest.isEmpty() || maxW <= 0 || maxLines < 1) return new String[] {""};
        List<String> lines = new ArrayList<>();
        while (!rest.isEmpty()) {
            if (lines.size() == maxLines - 1) {   // the last line: all of the rest, or as much as fits and "…"
                lines.add(width.applyAsDouble(rest) <= maxW ? rest : ellipsize(rest, maxW, width));
                break;
            }
            int cut = fit(rest, maxW, width);
            String line = rest.substring(0, cut).trim();
            if (line.isEmpty()) break;
            lines.add(line);
            rest = rest.substring(cut).trim();
        }
        return lines.isEmpty() ? new String[] {""} : lines.toArray(new String[0]);
    }

    /** How many characters of s go on one line: up to the last space that fits, else as many letters as fit. */
    private static int fit(String s, float maxW, ToDoubleFunction<String> width) {
        if (width.applyAsDouble(s) <= maxW) return s.length();
        int letters = 0;
        while (letters < s.length() && width.applyAsDouble(s.substring(0, letters + 1)) <= maxW) letters++;
        int space = s.lastIndexOf(' ', letters);
        return space > 0 ? space : Math.max(letters, 0);
    }

    private static String ellipsize(String s, float maxW, ToDoubleFunction<String> width) {
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n).trim() + ELLIPSIS;
            if (width.applyAsDouble(t) <= maxW) return t;
        }
        return width.applyAsDouble(ELLIPSIS) <= maxW ? ELLIPSIS : "";
    }
}
