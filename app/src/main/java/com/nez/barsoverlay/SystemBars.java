package com.nez.barsoverlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import de.robv.android.xposed.XposedBridge;

/**
 * "Screenshot" status bar dan nav bar bawaan: view aslinya digambar ke bitmap transparan
 * (tanpa wallpaper/latar), jadi bentuk ikonnya persis sama dengan sistem.
 * Semua method dipanggil dari main thread.
 */
public final class SystemBars {

    public static final int TYPE_STATUS_BAR = 2000;
    public static final int TYPE_NAVIGATION_BAR = 2019;

    public static final class Shot {
        public Bitmap bmp;
        public int x, y;
    }

    public static volatile Shot status;
    public static volatile Shot nav;

    private SystemBars() {}

    /** Bit 1 = status bar ada, bit 2 = nav bar ada. */
    public static int state() {
        return (status != null ? 1 : 0) | (nav != null ? 2 : 0);
    }

    /** Ambil ulang kalau bar sedang tampil penuh. Mengembalikan true kalau ada yang berubah. */
    public static boolean captureAll() {
        boolean changed = false;
        try {
            Shot s = grab(TYPE_STATUS_BAR, status);
            if (s != null && s != status) { status = s; changed = true; }
            Shot n = grab(TYPE_NAVIGATION_BAR, nav);
            if (n != null && n != nav) { nav = n; changed = true; }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return changed;
    }

    private static Shot grab(int type, Shot old) {
        View v = StatusBarFader.findWindow(type);
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.99f) return null; // sedang di-fade
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        if (!hasContent(b)) {
            b.recycle();
            return null;
        }
        if (old != null && old.bmp != null && old.bmp.getWidth() == b.getWidth()
                && old.bmp.getHeight() == b.getHeight() && old.bmp.sameAs(b)) {
            b.recycle();
            return old; // tidak berubah
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Shot s = new Shot();
        s.bmp = b;
        s.x = loc[0];
        s.y = loc[1];
        return s;
    }

    private static boolean hasContent(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i += 3) {
            if ((px[i] >>> 24) > 16) return true;
        }
        return false;
    }
}
