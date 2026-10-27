package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.RectF;
import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resting cards painted once into GPU bitmaps and drawn from them on every frame (Home's bento, For You's cards,
 * Apps' icons). Painting every card again on every frame of a focus move was most of a frame on the TV (Home: 85 ms →
 * 15 ms). A card is recorded as a Picture and rendered by the GPU (its artwork is a hardware bitmap, which a plain
 * Bitmap canvas can't draw; a RenderNode with its own layer drew nothing on this TV's Mali GPU). Painted again only
 * when its signature changes, at most one per frame (`again` asks for the next frame). Android 9+; older systems, or a
 * failure, draw directly.
 */
final class CardCache {
    interface Painter {
        void paint(Canvas c);
    }

    private static final class Plate {
        Bitmap bitmap;
        long sig, failed;
    }

    private final Map<Long, Plate> plates;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Runnable again;
    private int painted;

    /** `max`: how many cards are kept (the least recently drawn go first); `again`: asks for another frame. */
    CardCache(int max, Runnable again) {
        this.again = again;
        plates = new LinkedHashMap<Long, Plate>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Plate> eldest) {
                return size() > max;
            }
        };
    }

    /** At the start of the layer's drawing: this frame's one new card. */
    void frame() {
        painted = 0;
    }

    void clear() {
        plates.clear();
    }

    /**
     * Draws card `key` from its bitmap, painted by `painter` (screen coordinates, the card resting at `rest`, plus
     * `margin` around it for its shadow) when it's missing or its look changed. False: the caller draws it directly.
     */
    boolean draw(Canvas c, long key, long sig, RectF rest, int margin, Painter painter) {
        if (Build.VERSION.SDK_INT < 28 || !c.isHardwareAccelerated()) return false;
        Plate p = plates.get(key);
        if (p == null) plates.put(key, p = new Plate());
        if (p.failed == sig) return false;
        float ox = (float) Math.floor(rest.left) - margin, oy = (float) Math.floor(rest.top) - margin;
        if (p.bitmap == null || p.sig != sig) {
            if (painted > 0) {   // one per frame: drawn directly once more, the next frame paints it
                again.run();
                return false;
            }
            painted++;
            int w = (int) Math.ceil(rest.right - ox) + margin, h = (int) Math.ceil(rest.bottom - oy) + margin;
            try {
                Picture pic = new Picture();
                Canvas pc = pic.beginRecording(w, h);
                pc.translate(-ox, -oy);   // whole pixels: drawn back unscaled at a whole-pixel place, never resampled
                painter.paint(pc);
                pic.endRecording();
                p.bitmap = Bitmap.createBitmap(pic, w, h, Bitmap.Config.HARDWARE);
                p.sig = sig;
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("card cache: " + e);
                p.bitmap = null;
                p.failed = sig;   // drawn directly from now on (not tried again on every frame)
                return false;
            }
        }
        c.drawBitmap(p.bitmap, ox, oy, paint);
        return true;
    }

    /** The first GPU bitmap costs ~150 ms (the renderer behind it starts): made once early, off the critical moment. */
    static void warmUp() {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            Picture pic = new Picture();
            pic.beginRecording(1, 1);
            pic.endRecording();
            Bitmap.createBitmap(pic, 1, 1, Bitmap.Config.HARDWARE);
        } catch (RuntimeException ignored) {
        }
    }
}
