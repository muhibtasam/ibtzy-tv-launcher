import io.ibtzy.tvlauncher.HomeNav;

/** Tests for Home's focus rules (no Android): run by tools/tests/run.sh. */
public class HomeNavTest {
    private static int failed;

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] args) {
        // 4 Continue cards (0..3) and 10 bento cards (4..13): from the tabs, the bento's first; from the dock, Continue's.
        check("from tabs: bento first", HomeNav.sectionFirst(14, 4, true) == 4);
        check("from dock: continue first", HomeNav.sectionFirst(14, 4, false) == 0);
        // No Continue row: the bento's first either way.
        check("no continue, from tabs", HomeNav.sectionFirst(10, 0, true) == 0);
        check("no continue, from dock", HomeNav.sectionFirst(10, 0, false) == 0);
        // Only Continue cards (picks not loaded yet): its first either way.
        check("only continue, from tabs", HomeNav.sectionFirst(4, 4, true) == 0);
        check("only continue, from dock", HomeNav.sectionFirst(4, 4, false) == 0);
        // Home without Continue Watching: 11 tiles filling 8 columns × 3 rows exactly (no gap, no overlap), one 2×2.
        int[][] full = HomeNav.BENTO_FULL;
        check("full bento: 11 tiles", full.length == 11);
        int[] cells = new int[24];
        int big = 0;
        boolean inside = true;
        for (int[] b : full) {
            if (b[2] == 2 && b[3] == 2) big++;
            for (int x = b[0]; x < b[0] + b[2]; x++) for (int y = b[1]; y < b[1] + b[3]; y++) {
                if (x < 0 || x >= 8 || y < 0 || y >= 3) inside = false; else cells[y * 8 + x]++;
            }
        }
        boolean exact = true;
        for (int n : cells) exact &= n == 1;
        check("full bento: inside the grid", inside);
        check("full bento: every cell once", exact);
        check("full bento: one large card", big == 1);
        check("full bento: the large card first", full[0][2] == 2 && full[0][3] == 2);
        // Nothing at all.
        check("empty", HomeNav.sectionFirst(0, 0, true) == 0);

        // Moving between sections lands on the other section's first card; within one, the arrow's own target.
        check("bento → continue: its first", HomeNav.across(9, 2, 4) == 0);
        check("continue → bento: its first", HomeNav.across(2, 9, 4) == 4);
        check("within bento: as it is", HomeNav.across(5, 8, 4) == 8);
        check("within continue: as it is", HomeNav.across(1, 2, 4) == 2);
        check("no target: none", HomeNav.across(5, -1, 4) == -1);
        check("no continue row: as it is", HomeNav.across(3, 7, 0) == 7);

        if (failed > 0) { System.out.println("HomeNavTest: " + failed + " failed"); System.exit(1); }
        System.out.println("HomeNavTest: all passed");
    }
}
