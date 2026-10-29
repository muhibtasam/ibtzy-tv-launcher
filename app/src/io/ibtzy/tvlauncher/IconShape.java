package io.ibtzy.tvlauncher;

/**
 * Round app icons, the analysis (plain Java, tested by tools/tests/run.sh): an icon's background colour (its edge),
 * and where its logo is (centred on a circle of that colour).
 * HomeApps.roundIcon draws the result. Pixels are ARGB, row by row.
 */
public final class IconShape {
    private IconShape() {}

    /** The colour most of the edge has (transparent: alpha 0). */
    public static int background(int[] px, int w, int h) {
        java.util.HashMap<Integer, int[]> count = new java.util.HashMap<>();
        int best = 0, bestN = -1;
        for (int i = 0; i < 2 * (w + h); i++) {
            int x, y;
            if (i < w) { x = i; y = 0; }
            else if (i < 2 * w) { x = i - w; y = h - 1; }
            else if (i < 2 * w + h) { x = 0; y = i - 2 * w; }
            else { x = w - 1; y = i - 2 * w - h; }
            int p = px[y * w + x];
            int key = (p >>> 24) < 32 ? 0 : (p & 0xF0F0F0) | 0xFF000000;   // close colours together
            int[] c = count.get(key);
            if (c == null) count.put(key, c = new int[] {0, (p >>> 24) < 32 ? 0 : p});
            if (++c[0] > bestN) { bestN = c[0]; best = c[1]; }
        }
        return best;
    }

    private static boolean content(int p, int bg) {
        if ((bg >>> 24) < 32) return (p >>> 24) >= 64;
        if ((p >>> 24) < 200) return false;   // an icon's anti-aliased rounded corners aren't its logo
        int d = Math.max(Math.abs(((p >> 16) & 255) - ((bg >> 16) & 255)),
                Math.max(Math.abs(((p >> 8) & 255) - ((bg >> 8) & 255)), Math.abs((p & 255) - (bg & 255))));
        return d > 40;
    }

    /** The logo's bounds {left, top, right, bottom} (right and bottom exclusive), or null when there's none. */
    public static int[] contentBounds(int[] px, int w, int h, int bg) {
        int l = w, t = h, r = -1, b = -1;
        // Not the outer 6 %: icons drawn as a rounded square keep a light rim and soft corners there, not their logo.
        int m = Math.max(1, Math.round(0.06f * Math.min(w, h)));
        for (int y = m; y < h - m; y++) {
            for (int x = m; x < w - m; x++) {
                if (!content(px[y * w + x], bg)) continue;
                if (x < l) l = x;
                if (x > r) r = x;
                if (y < t) t = y;
                if (y > b) b = y;
            }
        }
        return r < 0 ? null : new int[] {l, t, r + 1, b + 1};
    }
}
