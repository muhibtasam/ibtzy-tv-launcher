import io.ibtzy.tvlauncher.TabsLogic;

/** Tests for Tabs V1's timing and sizes (no Android): run by tools/tests/run.sh. */
public class TabsLogicTest {
    private static int failed;

    private static void near(String name, float got, float want) {
        if (Float.isNaN(got) || Math.abs(got - want) > 0.05f) { failed++; System.out.println("FAIL " + name + ": " + got + " (want " + want + ")"); }
    }

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] args) {
        // A tab's width: the 72 px tile stretching into its capsule as it becomes the selected one.
        near("tile", TabsLogic.tabWidth(0, 220), 72);
        near("capsule", TabsLogic.tabWidth(1, 220), 220);
        near("halfway", TabsLogic.tabWidth(0.5f, 220), 146);
        near("overshoot clamped", TabsLogic.tabWidth(1.2f, 220), 220);
        near("capsule never under a tile", TabsLogic.tabWidth(1, 40), 72);
        near("capsule width", TabsLogic.capsuleWidth(100), 8 + 56 + 16 + 100 + 32);

        // Popcorn (1.8 s): the left kernel rests until 55 %, is highest at 68 % (-2, -11), back at 100 %.
        float[] k = TabsLogic.kernel(0, 0.5f);
        near("kernel 1 rests x", k[0], 0); near("kernel 1 rests y", k[1], 0);
        k = TabsLogic.kernel(0, 0.68f * 1.8f);
        near("kernel 1 top x", k[0], -2); near("kernel 1 top y", k[1], -11);
        k = TabsLogic.kernel(0, 1.8f);
        near("kernel 1 back", k[1], 0);
        // The right kernel starts 0.5 s later: highest at 55 % of its own cycle (3, -12).
        k = TabsLogic.kernel(1, 0.5f + 0.55f * 1.8f);
        near("kernel 2 top x", k[0], 3); near("kernel 2 top y", k[1], -12);
        k = TabsLogic.kernel(1, 0.2f);
        near("kernel 2 waits for its delay", k[1], 0);
        k = TabsLogic.kernel(0, 0.68f * 1.8f + 1.8f * 3);
        near("loops", k[1], -11);

        // Controller (2.2 s): still until 62 %, -9° at 70 %, 7° at 78 %; the gold button dims to 0.25 at 72 %.
        near("still", TabsLogic.wiggle(0.3f)[0], 0);
        near("tilts left", TabsLogic.wiggle(0.70f * 2.2f)[0], -9);
        near("lifts", TabsLogic.wiggle(0.70f * 2.2f)[1], -2);
        near("tilts right", TabsLogic.wiggle(0.78f * 2.2f)[0], 7);
        near("button lit", TabsLogic.blink(0.1f), 1);
        near("button dim", TabsLogic.blink(0.72f * 2.2f), 0.25f);

        // Malformed time: at rest, never NaN.
        near("NaN kernel", TabsLogic.kernel(0, Float.NaN)[1], 0);
        near("negative time", TabsLogic.wiggle(-5)[0], 0);
        near("NaN blink", TabsLogic.blink(Float.NaN), 1);
        near("unknown kernel", TabsLogic.kernel(7, 1)[0], 0);

        // Playing: while focused; after a switch, for two turns of the slower icon only.
        check("plays when focused", TabsLogic.playing(true, 0, 100_000));
        check("plays just after a switch", TabsLogic.playing(false, 1000, 1000 + 3000));
        check("rests later", !TabsLogic.playing(false, 1000, 1000 + 5000));
        check("rests before any switch", !TabsLogic.playing(false, 0, 100_000));

        if (failed > 0) { System.out.println("TabsLogicTest: " + failed + " failed"); System.exit(1); }
        System.out.println("TabsLogicTest: all passed");
    }
}
