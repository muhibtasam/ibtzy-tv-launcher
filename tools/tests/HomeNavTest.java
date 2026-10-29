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
