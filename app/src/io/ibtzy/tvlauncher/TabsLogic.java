package io.ibtzy.tvlauncher;

/**
 * Tabs V1 without Android: a main tab's width as it becomes the selected capsule, and the sub tabs' icon animations
 * (the popcorn's kernels popping, 1.8 s; the controller's rattle and its gold button's blink, 2.2 s), as in the sheet's
 * keyframes. Times in seconds; offsets in the icons' 64-unit drawing.
 */
public final class TabsLogic {
    public static final float TILE = 72, ICON_TILE = 56, POP_S = 1.8f, PAD_S = 2.2f;

    private TabsLogic() {
    }

    /** The capsule's width for a name `textW` wide: 8 + the 56 icon tile + 16 + the name + 32. */
    public static float capsuleWidth(float textW) {
        return 8 + ICON_TILE + 16 + Math.max(0, textW) + 32;
    }

    /** A tab's width with selection `s` (0: the tile, 1: the capsule). */
    public static float tabWidth(float s, float capsule) {
        float c = Math.max(TILE, capsule), t = Math.max(0, Math.min(1, s));
        return TILE + (c - TILE) * t;
    }

    // Keyframes: {fraction, x, y}.
    private static final float[][] KERNEL_1 = {{0, 0, 0}, {0.55f, 0, 0}, {0.68f, -2, -11}, {0.82f, -3, -5}, {1, 0, 0}};
    private static final float[][] KERNEL_2 = {{0, 0, 0}, {0.40f, 0, 0}, {0.55f, 3, -12}, {0.72f, 4, -4}, {1, 0, 0}};
    // {fraction, degrees, y}
    private static final float[][] WIGGLE = {{0, 0, 0}, {0.62f, 0, 0}, {0.70f, -9, -2}, {0.78f, 7, 0}, {0.86f, -3, 0}, {0.93f, 1, 0}, {1, 0, 0}};
    private static final float[][] BLINK = {{0, 1, 0}, {0.64f, 1, 0}, {0.72f, 0.25f, 0}, {0.80f, 1, 0}, {1, 1, 0}};

    /** Kernel 0 (left) or 1 (right, 0.5 s later): its offset {x, y} at time t. */
    public static float[] kernel(int which, float t) {
        if (which == 0) return at(KERNEL_1, t, POP_S);
        if (which == 1) return at(KERNEL_2, t - 0.5f, POP_S);
        return new float[] {0, 0};
    }

    /** The controller's tilt {degrees, y}. */
    public static float[] wiggle(float t) {
        return at(WIGGLE, t, PAD_S);
    }

    /** The gold button's opacity. */
    public static float blink(float t) {
        return at(BLINK, t, PAD_S)[0];
    }

    /** The icons play while the sub tabs have the focus, and for two turns (of the slower one) after a switch. */
    public static boolean playing(boolean focused, long switchedAtMs, long nowMs) {
        if (focused) return true;
        return switchedAtMs > 0 && nowMs >= switchedAtMs && nowMs - switchedAtMs < (long) (2 * PAD_S * 1000);
    }

    private static float[] at(float[][] frames, float t, float cycle) {
        if (Float.isNaN(t) || Float.isInfinite(t) || t < 0) return new float[] {frames[0][1], frames[0][2]};
        float f = (t % cycle) / cycle;
        for (int i = 1; i < frames.length; i++) {
            if (f <= frames[i][0]) {
                float[] a = frames[i - 1], b = frames[i];
                float u = b[0] == a[0] ? 1 : (f - a[0]) / (b[0] - a[0]);
                u = u * u * (3 - 2 * u);   // eased within each step, like the sheet's curve
                return new float[] {a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u};
            }
        }
        float[] last = frames[frames.length - 1];
        return new float[] {last[1], last[2]};
    }
}
