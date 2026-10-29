package io.ibtzy.tvlauncher;

/**
 * Home's focus rules (plain Java, tested in tools/tests/HomeNavTest). Home's cards are Continue Watching's first
 * (0 .. cont-1), then the bento's. Every row and section is entered at its first item.
 */
public final class HomeNav {
    private HomeNav() {}

    /**
     * Coming into Home's cards from the tabs (fromTop) or from the dock: the first card of the section you arrive in,
     * the bento's large card from above, Continue Watching's first from below. With only one section: its first.
     */
    public static int sectionFirst(int count, int cont, boolean fromTop) {
        if (cont <= 0 || cont >= count) return 0;
        return fromTop ? cont : 0;
    }

    /** An arrow from card `from` pointing at card `next` (-1: none): into the other section, its first card instead. */
    public static int across(int from, int next, int cont) {
        if (next < 0 || cont <= 0 || (next < cont) == (from < cont)) return next;
        return next < cont ? 0 : cont;
    }
}
