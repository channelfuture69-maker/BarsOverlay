package com.nez.barsoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import de.robv.android.xposed.XposedBridge;

/**
 * "Screenshot" status bar dan nav bar bawaan: view aslinya digambar ke bitmap transparan
 * (tanpa wallpaper/latar). Semua method dipanggil dari main thread.
 */
public final class SystemBars {

    public static final int TYPE_STATUS_BAR = 2000;
    public static final int TYPE_NAVIGATION_BAR = 2019;

    public static final class Shot {
        public Bitmap bmp;
        public int x, y;
        public boolean lightIcons; // ikon bar asli berwarna terang (putih)?
    }

    public static volatile Shot status;
    public static volatile Shot nav;

    private SystemBars() {}

    /** Bit 1 = status bar ada, bit 2 = nav bar ada. */
    public static int state() {
        return (status != null ? 1 : 0) | (nav != null ? 2 : 0);
    }

    /** Ambil ulang kalau bar sedang tampil penuh. Mengembalikan true kalau ada yang berubah. */
    public static boolean captureAll(Context ctx) {
        boolean changed = false;
        try {
            Shot s = grab(ctx, TYPE_STATUS_BAR, status, false);
            if (s != null && s != status) { status = s; changed = true; }
            else if (s != null) status = s;
            Shot n = grab(ctx, TYPE_NAVIGATION_BAR, nav, false);
            if (n != null && n != nav) { nav = n; changed = true; }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return changed;
    }

    /**
     * Baca warna ikon status bar asli saat ini (walau sedang di-fade). true = ikon putih.
     * Dipakai untuk mempelajari warna yang dipilih sistem untuk aplikasi yang sedang tampil.
     */
    public static Boolean sampleLightIcons() {
        try {
            View v = StatusBarFader.findWindow(TYPE_STATUS_BAR);
            if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
            Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(b));
            if (!hasContent(b)) { b.recycle(); return null; }
            boolean light = lightIcons(b);
            b.recycle();
            return light;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return null;
        }
    }

    private static Shot grab(Context ctx, int type, Shot old, boolean force) {
        View v = StatusBarFader.findWindow(type);
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        if (!force && (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.99f)) return null; // sedang di-fade
        Bitmap raw = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(raw));
        if (!hasContent(raw)) {
            raw.recycle();
            return null;
        }
        boolean light = lightIcons(raw);
        Bitmap b = raw;
        if (old != null && old.bmp != null && old.bmp.getWidth() == b.getWidth()
                && old.bmp.getHeight() == b.getHeight() && old.bmp.sameAs(b)) {
            b.recycle();
            old.lightIcons = light;
            return old; // tidak berubah
        }
        XposedBridge.log("BarsOverlay: snapshot " + (type == TYPE_STATUS_BAR ? "status" : "nav")
                + " " + b.getWidth() + "x" + b.getHeight() + " alpha-maks=" + maxAlpha(b)
                + " ikon=" + (light ? "putih" : "gelap"));
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Shot s = new Shot();
        s.bmp = b;
        s.x = loc[0];
        s.y = loc[1];
        s.lightIcons = light;
        return s;
    }

    // ------------------------------------------------------------------ warna

    private static boolean hasContent(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i += 3) {
            if ((px[i] >>> 24) > 16) return true;
        }
        return false;
    }

    private static boolean chromatic(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min >= 60;
    }

    /** Rata-rata terang piksel abu-abu/putih/hitam: > 128 berarti ikon putih. */
    private static boolean lightIcons(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        double sum = 0;
        long n = 0;
        for (int p : px) {
            if ((p >>> 24) <= 128) continue;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
            if (chromatic(r, g, bl)) continue;
            sum += 0.299 * r + 0.587 * g + 0.114 * bl;
            n++;
        }
        return n == 0 || (sum / n) > 128;
    }

    private static int maxAlpha(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int m = 0;
        for (int p : px) m = Math.max(m, p >>> 24);
        return m;
    }

    /**
     * Sesuaikan snapshot dengan latar aplikasi.
     *  - Warna ikon asli sama dengan yang diinginkan: dikembalikan apa adanya (persis sistem).
     *  - Beda (putih -> hitam atau sebaliknya): piksel abu-abu/putih/hitam dibalik terangnya
     *    (255 - terang), jadi angka gelap di dalam baterai kuning ikut jadi terang. Alpha tetap.
     *  - Warna menyala (kuning hemat daya, merah, hijau) tidak diubah.
     *  - Kalau seluruh gambar kurang pekat (alpha maksimum < 250), pekatnya dinormalkan.
     */
    public static Bitmap tinted(Bitmap src, boolean darkContent, boolean srcLight) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int maxA = 0;
        for (int p : px) maxA = Math.max(maxA, p >>> 24);
        boolean invert = (!darkContent) != srcLight; // ingin terang tapi asli gelap, atau sebaliknya
        float scale = (maxA > 40 && maxA < 250) ? 255f / maxA : 1f;
        if (!invert && scale == 1f) return src;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = p >>> 24;
            if (a == 0) continue;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (invert && !chromatic(r, g, b)) {
                r = 255 - r;
                g = 255 - g;
                b = 255 - b;
            }
            int na = scale == 1f ? a : Math.min(255, Math.round(a * scale));
            px[i] = (na << 24) | (r << 16) | (g << 8) | b;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }
}
