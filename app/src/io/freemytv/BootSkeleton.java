package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The home screen's own loading skeleton (HomeView.drawBoot) while the TV's storage is still locked at power-on
 * (Android starts the home screen then because it is direct-boot aware; before, it showed its black placeholder for
 * those seconds). Nothing from the app's settings or caches can be read yet, so the home screen saves its skeleton's
 * shapes (HomeView.bootLayout: tabs, round buttons, cards, dock, in their real places) in device storage, readable at
 * boot; this draws them with the same colors and the same moving light. When the storage unlocks, HomeView takes over
 * with the same skeleton and fades it out. Without a saved layout (the first power-on): the same look, simplified.
 */
final class BootSkeleton extends View {
    private static final String PREFS = "boot-look";
    private static final int SHAPE = 0x1AFFFFFF, SLOT = 0xFF1C1C1E;   // HomeView.bootShape / drawSkeletonSlot
    private static final long SHIMMER_MS = 1600;                       // HomeView.drawSkeletonSlot's light
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Matrix shimmerAt = new Matrix();
    private float[][] shapes;
    private float layoutW, layoutH;
    private Shader background;
    private LinearGradient shimmer;

    BootSkeleton(Context c) {
        super(c);
        setBackgroundColor(0xFF0B0B0D);
        SharedPreferences p = prefs(c);
        String json = p == null ? null : p.getString("layout", null);
        if (json != null) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                org.json.JSONArray a = o.getJSONArray("shapes");
                layoutW = (float) o.getDouble("w");
                layoutH = (float) o.getDouble("h");
                shapes = new float[a.length()][];
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONArray s = a.getJSONArray(i);
                    shapes[i] = new float[s.length()];
                    for (int j = 0; j < s.length(); j++) shapes[i][j] = (float) s.getDouble(j);
                }
            } catch (Exception e) {
                shapes = null;
            }
        }
    }

    /** Saves the home screen's skeleton shapes for the next power-on (only when they changed). */
    static void remember(Context c, String layout) {
        SharedPreferences p = prefs(c);
        if (p == null || layout == null || layout.equals(p.getString("layout", null))) return;
        p.edit().putString("layout", layout).apply();
    }

    private static SharedPreferences prefs(Context c) {
        try {
            return c.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), k = w / 1920f;
        if (w == 0 || h == 0) return;
        if (background == null) background = gradient(Math.round(h), 0xFF1C1C1E, 0xFF0B0B0D);
        fill.setShader(background);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        float band = 520 * k;
        if (shimmer == null) shimmer = new LinearGradient(0, 0, band, 0, new int[] {0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        float phase = (SystemClock.uptimeMillis() % SHIMMER_MS) / (float) SHIMMER_MS;   // the same clock as HomeView's
        shimmerAt.setTranslate(-band + phase * (w + band), 0);
        shimmer.setLocalMatrix(shimmerAt);
        if (shapes != null && layoutW > 0) {
            float sx = w / layoutW, sy = h / layoutH;
            for (float[] s : shapes) {
                if (s.length < 7) continue;
                r.set(s[1] * sx, s[2] * sy, s[3] * sx, s[4] * sy);
                shape(c, s[0] == HomeView.BOOT_SLOT ? SLOT : SHAPE, s[5] * sx, s[6] > 0);
            }
        } else {
            // No saved layout yet: greeting, tabs, weather pill and the card grid's outline, in the same colors.
            r.set(80 * k, 42 * k, 380 * k, 88 * k);
            shape(c, SHAPE, 14 * k, true);
            float x = 80 * k;
            for (float tw : new float[] {70, 90, 60}) {
                r.set(x, 139 * k, x + tw * k, 165 * k);
                shape(c, SHAPE, 13 * k, true);
                x += (tw + 60) * k;
            }
            r.set(1510 * k, 130 * k, 1840 * k, 174 * k);
            shape(c, SHAPE, 22 * k, true);
            r.set(80 * k, 262 * k, 1840 * k, 900 * k);
            shape(c, SLOT, 20 * k, true);
        }
        postInvalidateOnAnimation();   // the light moves (a few seconds, until the storage unlocks)
    }

    /** Ui.gradient, the same dithered gradient as the home screen's background (copied: Ui isn't loaded this early). */
    private static Shader gradient(int height, int top, int bottom) {
        int n = Math.max(2, height);
        int[] px = new int[4 * n];
        for (int y = 0; y < n; y++) {
            float t = y / (float) (n - 1);
            for (int x = 0; x < 4; x++) {
                float d = (Ui.BAYER[(y & 3) * 4 + x] + 0.5f) / 16f;
                int c = 0;
                for (int s = 0; s < 32; s += 8) {
                    float a = (top >>> s) & 255, b = (bottom >>> s) & 255;
                    c |= Math.min(255, (int) (a + (b - a) * t + d)) << s;
                }
                px[y * 4 + x] = c;
            }
        }
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, 4, n, android.graphics.Bitmap.Config.ARGB_8888);
        return new android.graphics.BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
    }

    private void shape(Canvas c, int color, float radius, boolean light) {
        fill.setShader(null);
        fill.setColor(color);
        c.drawRoundRect(r, radius, radius, fill);
        if (light) {
            fill.setShader(shimmer);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, radius, radius, fill);
            fill.setShader(null);
        }
    }
}
