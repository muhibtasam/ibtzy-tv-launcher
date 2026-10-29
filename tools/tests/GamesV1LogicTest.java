import io.ibtzy.tvlauncher.GamesV1Logic;
import java.util.Arrays;
import java.util.TimeZone;

/** Tests for For You's Games V1 (Release Radar) logic (no Android): run by tools/tests/run.sh. */
public class GamesV1LogicTest {
    private static int failed;
    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Karachi");   // UTC+5, no daylight saving
    private static final long DAY = 86_400_000L, HOUR = 3_600_000L, MIN = 60_000L;

    private static void check(String name, Object got, Object want) {
        String g = got instanceof int[] ? Arrays.toString((int[]) got) : got instanceof float[] ? Arrays.toString((float[]) got)
                : got instanceof String[] ? Arrays.toString((String[]) got) : got instanceof long[] ? Arrays.toString((long[]) got) : String.valueOf(got);
        String w = want instanceof int[] ? Arrays.toString((int[]) want) : want instanceof float[] ? Arrays.toString((float[]) want)
                : want instanceof String[] ? Arrays.toString((String[]) want) : want instanceof long[] ? Arrays.toString((long[]) want) : String.valueOf(want);
        if (!g.equals(w)) { failed++; System.out.println("FAIL " + name + ": " + g + " (want " + w + ")"); }
    }

    public static void main(String[] args) {
        long nov13 = GamesV1Logic.releaseMs("2026-11-13", TZ);
        long oct29 = GamesV1Logic.releaseMs("2026-10-29", TZ);
        // Release dates: local midnight; malformed or missing: -1.
        check("midnight local", nov13, 1_762_974_000_000L + 365L * DAY);   // 2026-11-12T19:00Z
        check("empty date", GamesV1Logic.releaseMs("", TZ), -1L);
        check("null date", GamesV1Logic.releaseMs(null, TZ), -1L);
        check("year only", GamesV1Logic.releaseMs("2026", TZ), -1L);
        check("garbage", GamesV1Logic.releaseMs("soon-ish-xx", TZ), -1L);
        check("bad month", GamesV1Logic.releaseMs("2026-13-01", TZ), -1L);
        check("bad day", GamesV1Logic.releaseMs("2026-02-30", TZ), -1L);
        check("RAWG's TBA", GamesV1Logic.releaseMs("null", TZ), -1L);

        // Countdown: days, hours, minutes left; 0s once out; none for a bad date.
        long now = nov13 - 15 * DAY - 6 * HOUR - 42 * MIN - 30_000;
        check("countdown", GamesV1Logic.countdown(nov13, now), new int[] {15, 6, 42});
        check("last minute", GamesV1Logic.countdown(nov13, nov13 - 59_000), new int[] {0, 0, 0});
        check("out", GamesV1Logic.countdown(nov13, nov13 + HOUR), new int[] {0, 0, 0});
        check("no date", GamesV1Logic.countdown(-1, now), null);
        check("two digits", GamesV1Logic.two(6), "06");
        check("three digits", GamesV1Logic.two(123), "123");
        check("negative", GamesV1Logic.two(-4), "00");
        check("next minute", GamesV1Logic.msToNextMinute(nov13 - 15 * DAY - 30_000, nov13), 30_000L);
        check("next minute exact", GamesV1Logic.msToNextMinute(nov13 - 2 * MIN, nov13), 60_000L);

        // The most anticipated: the most added among games still to come (ties: the sooner); none: -1.
        long today = oct29 + 10 * HOUR;
        check("most added", GamesV1Logic.anticipated(new long[] {nov13, nov13 + DAY, nov13 + 2 * DAY}, new int[] {50, 900, 400}, today), 1);
        check("past skipped", GamesV1Logic.anticipated(new long[] {oct29 - DAY, nov13}, new int[] {9999, 3}, today), 1);
        check("out today still counts", GamesV1Logic.anticipated(new long[] {oct29}, new int[] {5}, today), 0);
        check("bad dates skipped", GamesV1Logic.anticipated(new long[] {-1, nov13}, new int[] {9999, 3}, today), 1);
        check("tie: the sooner", GamesV1Logic.anticipated(new long[] {nov13 + DAY, nov13}, new int[] {7, 7}, today), 1);
        check("empty", GamesV1Logic.anticipated(new long[0], new int[0], today), -1);
        check("none to come", GamesV1Logic.anticipated(new long[] {-1, oct29 - 5 * DAY}, new int[] {1, 2}, today), -1);
        check("null", GamesV1Logic.anticipated(null, null, today), -1);

        // How long ago / until (calendar days, local).
        check("out today", GamesV1Logic.ago(oct29, today, TZ), "Out today");
        check("yesterday", GamesV1Logic.ago(oct29 - DAY, today, TZ), "Yesterday");
        check("days ago", GamesV1Logic.ago(oct29 - 4 * DAY, today, TZ), "4 days ago");
        check("last week", GamesV1Logic.ago(oct29 - 9 * DAY, today, TZ), "Last week");
        check("weeks ago", GamesV1Logic.ago(oct29 - 20 * DAY, today, TZ), "2 weeks ago");
        check("long ago: nothing", GamesV1Logic.ago(oct29 - 60 * DAY, today, TZ), "");
        check("future: nothing", GamesV1Logic.ago(oct29 + DAY, today, TZ), "");
        check("no date: nothing", GamesV1Logic.ago(-1, today, TZ), "");
        check("until today", GamesV1Logic.until(oct29, today, TZ), "today");
        check("until tomorrow", GamesV1Logic.until(oct29 + DAY, today, TZ), "tomorrow");
        check("until days", GamesV1Logic.until(oct29 + 8 * DAY, today, TZ), "in 8 days");
        check("until past", GamesV1Logic.until(oct29 - DAY, today, TZ), "");
        check("until bad", GamesV1Logic.until(-1, today, TZ), "");
        check("short date", GamesV1Logic.shortDate(nov13, TZ), "Nov 13");
        check("short date none", GamesV1Logic.shortDate(-1, TZ), "");

        // The radar: from today to the last game (60 days at least), the pins never closer than minGap, inside it.
        check("radar end", GamesV1Logic.radarEnd(new long[] {nov13, -1, nov13 + 90 * DAY}, today), nov13 + 90 * DAY);
        check("radar end at least 60 days", GamesV1Logic.radarEnd(new long[] {oct29 + 3 * DAY}, today), today + 60 * DAY);
        check("radar end empty", GamesV1Logic.radarEnd(new long[0], today), today + 60 * DAY);
        float[] xs = GamesV1Logic.pinX(new long[] {today + 30 * DAY, today + 60 * DAY}, today, today + 60 * DAY, 100, 1100, 64);
        check("pins on the line", xs, new float[] {600, 1100});
        xs = GamesV1Logic.pinX(new long[] {today + 30 * DAY, today + 30 * DAY, today + 30 * DAY}, today, today + 60 * DAY, 100, 1100, 64);
        check("same day: spread", xs, new float[] {600, 664, 728});
        xs = GamesV1Logic.pinX(new long[] {today + 59 * DAY, today + 60 * DAY}, today, today + 60 * DAY, 100, 1100, 64);
        check("at the end: pushed back", xs, new float[] {1036, 1100});
        xs = GamesV1Logic.pinX(new long[] {-1, today - 3 * DAY, today + 60 * DAY}, today, today + 60 * DAY, 100, 1100, 64);
        check("bad and past: at today's end, in order", xs, new float[] {100, 164, 1100});
        check("no pins", GamesV1Logic.pinX(new long[0], today, today + DAY, 100, 1100, 64), new float[0]);
        check("one pin", GamesV1Logic.pinX(new long[] {today + 30 * DAY}, today, today + 60 * DAY, 100, 1100, 64), new float[] {600});

        // Month marks after today, up to the end.
        long[] months = GamesV1Logic.monthStarts(today, nov13 + 90 * DAY, TZ);
        check("month count", months.length, 4);
        check("first month", GamesV1Logic.monthLabel(months[0], TZ), "NOV");
        check("last month", GamesV1Logic.monthLabel(months[3], TZ), "FEB");
        check("no months", GamesV1Logic.monthStarts(today, today + 2 * DAY, TZ).length, 0);

        // The meta line: 2 platforms · genre · ★players' average (from 5 ratings).
        check("meta", GamesV1Logic.meta("PS5 · PC · Xbox Series", "Action", 4.62f, 120), "PS5 · PC · Action · ★4.6");
        check("few ratings", GamesV1Logic.meta("PS5", "Action", 4.62f, 3), "PS5 · Action");
        check("no genre", GamesV1Logic.meta("PC", "", 0, 0), "PC");
        check("nothing", GamesV1Logic.meta("", null, Float.NaN, 50), "");
        check("bad rating", GamesV1Logic.meta(null, "RPG", 7f, 50), "RPG");
        check("want it", GamesV1Logic.wantIt(12_400), "12.4K people want it");
        check("want it few", GamesV1Logic.wantIt(12), "");
        check("want it millions", GamesV1Logic.wantIt(2_000_000), "2M people want it");
        check("score", GamesV1Logic.score(92, 0), 92);
        check("score from meta", GamesV1Logic.score(0, 85), 85);
        check("score none", GamesV1Logic.score(0, 0), 0);
        check("score bad", GamesV1Logic.score(140, -2), 0);

        if (failed > 0) { System.out.println(failed + " failed"); System.exit(1); }
        System.out.println("GamesV1LogicTest: all passed");
    }
}
