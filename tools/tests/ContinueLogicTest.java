import io.freemytv.ContinueLogic;
import io.freemytv.ContinueLogic.Entry;
import java.util.*;

/** Tests for Home's Continue row (no Android): run by tools/tests/run.sh. */
public class ContinueLogicTest {
    private static int failed;

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL " + name); }
    }

    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    private static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Karachi");
    // Tue 27 Oct 2026, 20:00 in Karachi (UTC+5)
    private static final long NOW = 1793113200000L;
    private static final Set<String> APPS = new HashSet<>(Arrays.asList("netflix", "crunchyroll"));

    private static Entry e(int id, String kind, String app, long at) {
        return new Entry(id, kind, app, at);
    }

    private static List<Integer> ids(List<Entry> in) {
        List<Entry> out = ContinueLogic.select(in, NOW, APPS::contains);
        List<Integer> r = new ArrayList<>();
        for (Entry x : out) r.add(x.id);
        return r;
    }

    public static void main(String[] args) {
        // Empty and null history: nothing to continue.
        check("empty", ids(new ArrayList<>()).isEmpty());
        check("null", ContinueLogic.select(null, NOW, APPS::contains).isEmpty());

        // Newest first is kept, at most four.
        List<Entry> many = new ArrayList<>();
        for (int i = 1; i <= 6; i++) many.add(e(i, "movie", "netflix", NOW - i * HOUR));
        check("max four, in order", ids(many).equals(Arrays.asList(1, 2, 3, 4)));

        // Older than 30 days: gone. Exactly 30 days: kept.
        check("30-day window", ids(Arrays.asList(e(1, "tv", "netflix", NOW - 30 * DAY), e(2, "tv", "netflix", NOW - 31 * DAY)))
                .equals(Collections.singletonList(1)));

        // The same title twice: once (the first, newest). Same id, other kind: a different title.
        check("no duplicates", ids(Arrays.asList(e(7, "tv", "netflix", NOW - HOUR), e(7, "tv", "netflix", NOW - 2 * HOUR),
                e(7, "movie", "netflix", NOW - 3 * HOUR))).equals(Arrays.asList(7, 7)));

        // Its app no longer installed, or none recorded: hidden.
        check("app missing", ids(Arrays.asList(e(1, "movie", "prime", NOW - HOUR), e(2, "movie", null, NOW - HOUR),
                e(3, "movie", "crunchyroll", NOW - HOUR))).equals(Collections.singletonList(3)));

        // Malformed entries: skipped, never a crash.
        check("malformed", ids(Arrays.asList(e(0, "movie", "netflix", NOW - HOUR), e(2, "game", "netflix", NOW - HOUR),
                e(3, null, "netflix", NOW - HOUR), e(4, "movie", "netflix", 0), e(5, "movie", "netflix", -9),
                e(6, "tv", "netflix", NOW - HOUR))).equals(Collections.singletonList(6)));
        check("null entry", ids(Arrays.asList(null, e(6, "tv", "netflix", NOW - HOUR))).equals(Collections.singletonList(6)));

        // The line: when it was opened, never a made-up progress.
        check("just now", ContinueLogic.opened(NOW - 20_000, NOW, ZONE).equals("Opened just now"));
        check("future clock", ContinueLogic.opened(NOW + HOUR, NOW, ZONE).equals("Opened just now"));
        check("minutes", ContinueLogic.opened(NOW - 5 * MIN, NOW, ZONE).equals("Opened 5 min ago"));
        check("hours", ContinueLogic.opened(NOW - 2 * HOUR, NOW, ZONE).equals("Opened 2 h ago"));
        check("earlier today", ContinueLogic.opened(NOW - 19 * HOUR, NOW, ZONE).equals("Opened 19 h ago"));
        check("yesterday", ContinueLogic.opened(NOW - 21 * HOUR, NOW, ZONE).equals("Opened yesterday"));
        check("this week", ContinueLogic.opened(NOW - 3 * DAY, NOW, ZONE).equals("Opened Sat"));
        check("older", ContinueLogic.opened(NOW - 12 * DAY, NOW, ZONE).equals("Opened Oct 15"));

        // With its season: "Season 2 · Opened yesterday".
        check("season line", ContinueLogic.line("tv", 2, NOW - 21 * HOUR, NOW, ZONE).equals("Season 2 · Opened yesterday"));
        check("movie line", ContinueLogic.line("movie", 0, NOW - 2 * HOUR, NOW, ZONE).equals("Opened 2 h ago"));

        System.out.println(failed == 0 ? "ContinueLogicTest: all passed" : "ContinueLogicTest: " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
