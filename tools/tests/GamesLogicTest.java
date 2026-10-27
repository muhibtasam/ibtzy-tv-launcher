import io.freemytv.GamesLogic;

/** Tests for the Games section's pure logic (no Android): run by tools/tests/run.sh. */
public class GamesLogicTest {
    private static int failed;

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL " + name); }
    }

    private static boolean near(float a, float b) { return Math.abs(a - b) < 0.01f; }

    public static void main(String[] args) {
        // The rail loops: moving past either end comes round to the other.
        check("wrap forward past the end", GamesLogic.wrap(10, 10) == 0);
        check("wrap backward before the start", GamesLogic.wrap(-1, 10) == 9);
        check("wrap far negative", GamesLogic.wrap(-21, 10) == 9);
        check("wrap of an empty row", GamesLogic.wrap(5, 0) == 0);

        // Rail geometry (normal 220, wide 533, gap 24): the selected card sits at the margin, wide.
        check("selected at margin", near(GamesLogic.railX(0, 220, 533, 24), 0));
        check("selected is wide", near(GamesLogic.railW(0, 220, 533), 533));
        check("next card right after the wide one", near(GamesLogic.railX(1, 220, 533, 24), 557));
        check("third card", near(GamesLogic.railX(2, 220, 533, 24), 801));
        check("previous card peeks left of the margin", near(GamesLogic.railX(-1, 220, 533, 24), -244));
        check("previous card is normal width", near(GamesLogic.railW(-1, 220, 533), 220));
        // Mid-move (halfway to the next): no overlap and no gap jump between neighbours.
        float d = -0.5f;
        float rightEdge = GamesLogic.railX(d, 220, 533, 24) + GamesLogic.railW(d, 220, 533);
        check("halfway: neighbours stay one gap apart", near(GamesLogic.railX(d + 1, 220, 533, 24) - rightEdge, 24));
        d = 0.25f;
        rightEdge = GamesLogic.railX(d, 220, 533, 24) + GamesLogic.railW(d, 220, 533);
        check("quarter: neighbours stay one gap apart", near(GamesLogic.railX(d + 1, 220, 533, 24) - rightEdge, 24));

        // Upcoming's release calendar: month columns in date order, a year-only date (RAWG's Dec 31) is "date TBA".
        String[] dates = {"2026-11-19", "2026-11-27", "2026-11-30", "2026-12-03", "", "2026-12-31", "2027-01-09", "2026-12-31"};
        int[][] cal = GamesLogic.calendar(dates, 3, 2);
        check("calendar: three columns", cal.length == 3);
        check("calendar: a month keeps its first two", java.util.Arrays.equals(cal[0], new int[] {0, 1}));
        check("calendar: next month", java.util.Arrays.equals(cal[1], new int[] {3}));
        check("calendar: year-only dates group as TBA", java.util.Arrays.equals(cal[2], new int[] {5, 7}));
        check("calendar: TBA key", GamesLogic.calendarKey("2026-12-31").equals("2026 TBA"));
        check("calendar: month key", GamesLogic.calendarKey("2026-11-19").equals("2026-11"));
        check("calendar: no date", GamesLogic.calendar(new String[] {"", "soon"}, 3, 2).length == 0);
        check("calendar: empty", GamesLogic.calendar(new String[0], 3, 2).length == 0);

        // Untrusted API data.
        check("score: missing is 0", GamesLogic.score(-1) == 0);
        check("score: NaN is 0", GamesLogic.score(Double.NaN) == 0);
        check("score: rounded", GamesLogic.score(86.6) == 87);
        check("score: clamped", GamesLogic.score(250) == 100);
        check("https: kept", GamesLogic.https("https://img.example.com/a.jpg").equals("https://img.example.com/a.jpg"));
        check("https: http refused", GamesLogic.https("http://x.com/a.jpg").isEmpty());
        check("https: javascript refused", GamesLogic.https("javascript:alert(1)").isEmpty());
        check("https: whitespace refused", GamesLogic.https("https://x.com/a b.jpg").isEmpty());
        check("https: null", GamesLogic.https(null).isEmpty());
        check("date: ISO kept", GamesLogic.isoDate("2026-10-13T00:00:00.000Z").equals("2026-10-13"));
        check("date: junk dropped", GamesLogic.isoDate("soon").isEmpty());
        check("clean: control chars and tags gone, spaces collapsed",
                GamesLogic.clean("A\u0000 <b>great</b>\n\n game\t", 100).equals("A great game"));
        check("clean: cut at a word with an ellipsis", GamesLogic.clean("one two three four", 12).equals("one two…"));
        check("clean: HTML entities decoded", GamesLogic.clean("Rock &amp; roll &quot;now&quot; &#39;go&#39;", 100).equals("Rock & roll \"now\" 'go'"));
        // A store's search result is the same game only when the names match (trademark signs and case aside).
        check("same name: trademark signs ignored", GamesLogic.sameName("Call of Duty®: Modern Warfare® 4", "Call of Duty: Modern Warfare 4"));
        check("same name: case ignored", GamesLogic.sameName("REPLACED", "Replaced"));
        check("same name: a longer title is another game", !GamesLogic.sameName("The Farmer Was Replaced", "Replaced"));
        check("same name: null", !GamesLogic.sameName(null, "x"));
        check("same name: empty never matches", !GamesLogic.sameName("®", "™"));
        // A description's summary: no store or social promo, whole sentences up to the room (3 lines on the TV).
        check("summary: promo and section headers removed",
                GamesLogic.summary("JOIN OUR DISCORD About the GameThe realm of Embervale is lost. In their greed for magical power, your ancestors unleashed a pestilence. Enshrouded is a game of survival.", 120)
                        .equals("The realm of Embervale is lost. In their greed for magical power, your ancestors unleashed a pestilence."));
        check("summary: promo sentences dropped",
                GamesLogic.summary("A great game. Wishlist now on Steam! Follow us on Twitter for news. It has dragons.", 200).equals("A great game. It has dragons."));
        check("summary: one long sentence cut at a word, no ellipsis", GamesLogic.summary("one two three four five six", 14).equals("one two three."));
        check("summary: always ends with a full stop", GamesLogic.summary("A survival game with dragons", 100).equals("A survival game with dragons."));
        check("summary: trailing comma becomes a full stop", GamesLogic.summary("Build, fight, explore,", 100).equals("Build, fight, explore."));
        check("summary: ! and ? endings kept", GamesLogic.summary("Ready to fight?", 100).equals("Ready to fight?"));
        check("summary: only promo is empty", GamesLogic.summary("Join our Discord!", 100).isEmpty());
        check("summary: null", GamesLogic.summary(null, 100).isEmpty());
        check("clean: null", GamesLogic.clean(null, 10).isEmpty());

        if (failed > 0) { System.out.println(failed + " failed"); System.exit(1); }
        System.out.println("GamesLogicTest: all passed");
    }
}
