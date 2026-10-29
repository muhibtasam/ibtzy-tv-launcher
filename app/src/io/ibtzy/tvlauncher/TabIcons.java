package io.ibtzy.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Tabs V1's icons, drawn as vectors from the sheet: the main tabs' line icons (home, For You's play with a sparkle,
 * apps) and the sub tabs' 3D popcorn and controller. The 3D icons' still parts are drawn once into a bitmap per size;
 * only the moving bits (two kernels; the controller's tilt and gold button) are drawn per frame, and only while playing.
 */
final class TabIcons {
    private TabIcons() {
    }

    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint BITMAP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Path PATH = new Path();
    private static final RectF R = new RectF();

    static {
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setStrokeCap(Paint.Cap.ROUND);
        STROKE.setStrokeJoin(Paint.Join.ROUND);
    }

    // MARK: - Main tabs: line icons in a 24-unit box, `size` px, centred on cx, cy

    /** 0 Home, 1 For You, 2 Apps. */
    static void tab(Canvas c, int which, float cx, float cy, float size, int color) {
        float u = size / 24f;
        c.save();
        c.translate(cx - size / 2, cy - size / 2);
        c.scale(u, u);
        STROKE.setColor(color);
        STROKE.setStrokeWidth(1.5f);
        PATH.reset();
        if (which == 0) {
            PATH.moveTo(3.5f, 10.5f);
            PATH.lineTo(12, 4);
            PATH.lineTo(20.5f, 10.5f);
            PATH.lineTo(20.5f, 20.5f);
            PATH.lineTo(15, 20.5f);
            PATH.lineTo(15, 15);
            PATH.lineTo(9, 15);
            PATH.lineTo(9, 20.5f);
            PATH.lineTo(3.5f, 20.5f);
            PATH.close();
            c.drawPath(PATH, STROKE);
        } else if (which == 1) {
            PATH.moveTo(8, 6.8f);
            PATH.lineTo(8, 17.2f);
            PATH.lineTo(17.5f, 12);
            PATH.close();
            c.drawPath(PATH, STROKE);
            c.drawLine(18.5f, 3, 18.5f, 6, STROKE);   // the sparkle
            c.drawLine(17, 4.5f, 20, 4.5f, STROKE);
        } else {
            for (float[] p : new float[][] {{4, 4}, {13.5f, 4}, {4, 13.5f}, {13.5f, 13.5f}}) {
                R.set(p[0], p[1], p[0] + 6.5f, p[1] + 6.5f);
                c.drawRoundRect(R, 2, 2, STROKE);
            }
        }
        c.restore();
    }

    // MARK: - Sub tabs: the 3D popcorn and controller, in the sheet's 64-unit drawing

    private static Bitmap popcornStill, padStill;
    private static int popcornPx, padPx;
    private static RadialGradient kernelShade;

    private static RadialGradient kernel() {
        if (kernelShade == null) kernelShade = new RadialGradient(-0.3f * 1, -0.4f, 1.5f,
                new int[] {0xFFFFFBF2, 0xFFF6E4C2, 0xFFD9B57A}, new float[] {0, 0.6f, 1}, Shader.TileMode.CLAMP);
        return kernelShade;
    }

    /** One kernel: a ball lit from the top left (the gradient made once, scaled to its radius). */
    private static void kernelBall(Canvas c, float x, float y, float r) {
        c.save();
        c.translate(x, y);
        c.scale(r, r);
        FILL.setShader(kernel());
        c.drawCircle(0, 0, 1, FILL);
        FILL.setShader(null);
        c.restore();
    }

    /** The popcorn at `size` px centred on cx, cy; `t` its time (seconds), NaN: at rest. */
    static void popcorn(Canvas c, float cx, float cy, float size, float t, int alpha) {
        int px = Math.max(1, Math.round(size));
        if (popcornStill == null || popcornPx != px) {
            popcornStill = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas b = new Canvas(popcornStill);
            b.scale(px / 64f, px / 64f);
            float[][] balls = {{19, 21, 7}, {44, 20, 7}, {31, 15, 8.5f}, {26, 21, 6}, {37, 21, 6.5f}};
            for (float[] k : balls) kernelBall(b, k[0], k[1], k[2]);
            Path bucket = new Path();
            bucket.moveTo(14, 27);
            bucket.lineTo(50, 27);
            bucket.lineTo(45.4f, 57.4f);
            bucket.quadTo(45, 60, 42.4f, 60);
            bucket.lineTo(21.6f, 60);
            bucket.quadTo(19, 60, 18.6f, 57.4f);
            bucket.close();
            FILL.setColor(0xFFF6EBDD);
            b.drawPath(bucket, FILL);
            b.save();
            b.clipPath(bucket);
            FILL.setColor(0xFFC4453A);
            for (float[] s : new float[][] {{18.5f, 24.5f, 27.1f, 21.6f}, {29.5f, 34.5f, 34, 30}, {39.5f, 45.5f, 41.9f, 36.9f}}) {
                Path stripe = new Path();
                stripe.moveTo(s[0], 27);
                stripe.lineTo(s[1], 27);
                stripe.lineTo(s[2], 60);
                stripe.lineTo(s[3], 60);
                stripe.close();
                b.drawPath(stripe, FILL);
            }
            FILL.setShader(new LinearGradient(14, 0, 50, 0, new int[] {0x59000000, 0x00000000, 0x1FFFFFFF, 0x66000000},
                    new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
            b.drawPath(bucket, FILL);
            FILL.setShader(null);
            b.restore();
            FILL.setColor(0xFFFFF8EC);
            R.set(12, 24, 52, 29);
            b.drawRoundRect(R, 2.5f, 2.5f, FILL);
            FILL.setColor(0x2E000000);
            R.set(12, 27, 52, 29);
            b.drawRoundRect(R, 1, 1, FILL);
            popcornPx = px;
        }
        float u = size / 64f, x0 = cx - size / 2, y0 = cy - size / 2;
        // The two popping kernels behind the bucket's rim: drawn first, the bucket over them.
        c.save();
        c.translate(x0, y0);
        c.scale(u, u);
        FILL.setAlpha(alpha);
        float[] a = TabsLogic.kernel(0, t), b2 = TabsLogic.kernel(1, t);
        kernelBall(c, 22 + a[0], 10 + a[1], 4);
        kernelBall(c, 41 + b2[0], 9 + b2[1], 4);
        FILL.setAlpha(255);
        c.restore();
        BITMAP.setAlpha(alpha);
        c.drawBitmap(popcornStill, x0, y0, BITMAP);
        BITMAP.setAlpha(255);
    }

    /** The white controller at `size` px centred on cx, cy; it rattles and its gold button blinks with `t`. */
    static void controller(Canvas c, float cx, float cy, float size, float t, int alpha) {
        int px = Math.max(1, Math.round(size));
        if (padStill == null || padPx != px) {
            padStill = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas b = new Canvas(padStill);
            b.scale(px / 64f, px / 64f);
            FILL.setColor(0x59000000);
            R.set(12, 55.5f, 52, 60.5f);
            b.drawOval(R, FILL);
            Path body = new Path();
            body.moveTo(18, 19);
            body.lineTo(46, 19);
            body.quadTo(56, 19, 57.7f, 28.4f);
            body.lineTo(60.8f, 42.8f);
            body.quadTo(61.5f, 52, 47.9f, 49.3f);
            body.lineTo(42.6f, 43);
            body.lineTo(21.4f, 43);
            body.lineTo(16.1f, 49.3f);
            body.quadTo(2.5f, 52, 3.2f, 42.8f);
            body.lineTo(6.3f, 28.4f);
            body.quadTo(8, 19, 18, 19);
            body.close();
            FILL.setColor(0xFF000000);   // opaque again after the shadow (the gradient keeps the paint's alpha)
            FILL.setShader(new LinearGradient(0, 19, 0, 52, new int[] {0xFFFFFFFF, 0xFFECE8E2, 0xFFBDB6AC},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
            b.drawPath(body, FILL);
            FILL.setShader(null);
            STROKE.setColor(0xE6FFFFFF);
            STROKE.setStrokeWidth(1.2f);
            Path shine = new Path();
            shine.moveTo(18, 19.8f);
            shine.lineTo(46, 19.8f);
            shine.quadTo(54, 19.8f, 56, 26.2f);
            b.drawPath(shine, STROKE);
            FILL.setColor(0xFF2E2B28);
            R.set(13.5f, 29, 23.5f, 32.6f);
            b.drawRoundRect(R, 1, 1, FILL);
            R.set(16.7f, 25.8f, 20.3f, 35.8f);
            b.drawRoundRect(R, 1, 1, FILL);
            for (float x : new float[] {25, 39}) {
                FILL.setShader(new RadialGradient(x - 0.4f, 37.5f, 3.6f, 0xFF4A4643, 0xFF0F0E0D, Shader.TileMode.CLAMP));
                b.drawCircle(x, 38, 3.6f, FILL);
                FILL.setShader(null);
                STROKE.setColor(0xFF9A938A);
                STROKE.setStrokeWidth(0.8f);
                b.drawCircle(x, 38, 3.6f, STROKE);
            }
            FILL.setColor(0xFF3A3633);
            b.drawCircle(46, 26.5f, 2.2f, FILL);
            b.drawCircle(41.5f, 30.8f, 2.2f, FILL);
            b.drawCircle(46, 35, 2.2f, FILL);
            padPx = px;
        }
        float u = size / 64f, x0 = cx - size / 2, y0 = cy - size / 2;
        float[] w = TabsLogic.wiggle(t);
        c.save();
        c.translate(0, w[1] * u);
        c.rotate(w[0], x0 + 32 * u, y0 + 44 * u);   // turns about its lower middle, as in the sheet
        BITMAP.setAlpha(alpha);
        c.drawBitmap(padStill, x0, y0, BITMAP);
        BITMAP.setAlpha(255);
        FILL.setColor(0xFFF0B860);
        FILL.setAlpha(Math.round(alpha * TabsLogic.blink(t)));
        c.drawCircle(x0 + 50.5f * u, y0 + 30.8f * u, 2.2f * u, FILL);
        FILL.setAlpha(255);
        c.restore();
    }
}
