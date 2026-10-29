import io.ibtzy.tvlauncher.TitleWrap;
import java.util.Arrays;

/** Tests for card titles wrapped to at most 2 lines (no Android): run by tools/tests/run.sh. */
public class TitleWrapTest {
    private static int failed;

    private static void check(String name, String[] got, String... want) {
        if (!Arrays.equals(got, want)) { failed++; System.out.println("FAIL " + name + ": " + Arrays.toString(got)); }
    }

    public static void main(String[] args) {
        // 1 unit per character: easy widths.
        java.util.function.ToDoubleFunction<String> w = String::length;
        check("fits on one line", TitleWrap.wrap("Lupin", 10, w, 2), "Lupin");
        check("wraps at a space", TitleWrap.wrap("Surviving Hurricane Otis", 12, w, 2), "Surviving", "Hurricane O…");
        check("second line fits whole", TitleWrap.wrap("The Apothecary Diaries", 14, w, 2), "The Apothecary", "Diaries");
        check("long last line ends in …", TitleWrap.wrap("Drive - The Pretenders", 9, w, 2), "Drive -", "The Pret…");
        check("one long word breaks by letters", TitleWrap.wrap("Supercalifragilistic", 8, w, 2), "Supercal", "ifragil…");
        check("one line allowed", TitleWrap.wrap("Gajab Raat Returns", 10, w, 1), "Gajab Raa…");
        check("empty", TitleWrap.wrap("", 10, w, 2), "");
        check("null", TitleWrap.wrap(null, 10, w, 2), "");
        check("extra spaces", TitleWrap.wrap("  BTS:   THE RETURN  ", 6, w, 2), "BTS:", "THE R…");
        check("no room at all", TitleWrap.wrap("Bleach", 0, w, 2), "");
        if (failed > 0) { System.out.println("TitleWrapTest: " + failed + " failed"); System.exit(1); }
        System.out.println("TitleWrapTest: all passed");
    }
}
