package com.nez.barsoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

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
        public Rect batt;          // area ikon baterai di dalam bitmap (hanya status bar)
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
            Shot s = grab(TYPE_STATUS_BAR, status);
            if (s != null && s != status) { status = s; changed = true; }
            else if (s != null) status = s;
            Shot n = grab(TYPE_NAVIGATION_BAR, nav);
            if (n != null && n != nav) { nav = n; changed = true; }
            else if (n != null) nav = n;
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return changed;
    }

    /**
     * Baca warna ikon bar asli saat ini (walau sedang di-fade). true = ikon putih.
     * Dipakai untuk mempelajari warna yang dipilih sistem untuk aplikasi yang sedang tampil.
     */
    public static Boolean sampleLight(int type) {
        try {
            View v = StatusBarFader.findWindow(type);
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
        boolean light = lightIcons(b);
        Rect batt = null;
        if (type == TYPE_STATUS_BAR) {
            View bv = findByClass(v, "BatteryMeterView");
            if (bv != null && bv.getWidth() > 0) {
                int l = leftIn(v, bv), t = topIn(v, bv);
                batt = new Rect(Math.max(0, l), Math.max(0, t),
                        Math.min(b.getWidth(), l + bv.getWidth()), Math.min(b.getHeight(), t + bv.getHeight()));
            }
        }
        if (old != null && old.bmp != null && old.bmp.getWidth() == b.getWidth()
                && old.bmp.getHeight() == b.getHeight() && old.bmp.sameAs(b)) {
            b.recycle();
            old.lightIcons = light;
            old.batt = batt;
            return old; // tidak berubah
        }
        int[] pct = percentiles(b, light);
        XposedBridge.log("BarsOverlay: snapshot " + (type == TYPE_STATUS_BAR ? "status" : "nav")
                + " " + b.getWidth() + "x" + b.getHeight() + " alpha-ikon-p95=" + pct[0]
                + " terang-p95=" + pct[1] + " ikon=" + (light ? "putih" : "gelap")
                + " baterai=" + (batt != null));
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Shot s = new Shot();
        s.bmp = b;
        s.x = loc[0];
        s.y = loc[1];
        s.lightIcons = light;
        s.batt = batt;
        return s;
    }

    // ------------------------------------------------------------------ cari view

    private static View findByClass(View root, String part) {
        if (root.getClass().getName().contains(part)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByClass(g.getChildAt(i), part);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static int leftIn(View root, View v) {
        int x = 0;
        for (View c = v; c != root && c != null; ) {
            x += c.getLeft();
            Object p = c.getParent();
            c = (p instanceof View) ? (View) p : null;
        }
        return x;
    }

    private static int topIn(View root, View v) {
        int y = 0;
        for (View c = v; c != root && c != null; ) {
            y += c.getTop();
            Object p = c.getParent();
            c = (p instanceof View) ? (View) p : null;
        }
        return y;
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

    private static int lum(int r, int g, int b) {
        return Math.round(0.299f * r + 0.587f * g + 0.114f * b);
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
            sum += lum(r, g, bl);
            n++;
        }
        return n == 0 || (sum / n) > 128;
    }

    /**
     * Persentil ke-95 dari ikon (abu-abu/putih/hitam) saja, supaya warna menyala dan chip
     * perekam layar yang selalu pekat tidak mengacaukan hitungan:
     * [0] alpha ikon (ikon terang kalau srcLight, gelap kalau tidak; alpha >= 64),
     * [1] terang ikon abu-abu pekat (alpha >= 200).
     */
    private static int[] percentiles(Bitmap b, boolean srcLight) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return percentiles(px, srcLight);
    }

    private static int[] percentiles(int[] px, boolean srcLight) {
        int[] ha = new int[256];
        int[] hb = new int[256];
        long na = 0, nb = 0;
        for (int p : px) {
            int a = p >>> 24;
            if (a < 64) continue;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
            if (chromatic(r, g, bl)) continue;
            int l = lum(r, g, bl);
            boolean iconPixel = srcLight ? l >= 140 : l <= 110;
            if (iconPixel) {
                ha[a]++;
                na++;
            }
            if (a >= 200) {
                hb[l]++;
                nb++;
            }
        }
        return new int[]{p95(ha, na, 255), p95(hb, nb, 255)};
    }

    private static int p95(int[] hist, long total, int dflt) {
        if (total == 0) return dflt;
        long target = (long) (total * 0.95), acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= target) return i;
        }
        return dflt;
    }

    /**
     * Sesuaikan snapshot dengan latar di belakang bar ini.
     *  - Warna ikon asli sama dengan yang diinginkan dan tidak ada yang kurang pekat:
     *    dikembalikan apa adanya (persis sistem).
     *  - Beda (putih -> hitam atau sebaliknya): piksel abu-abu/putih/hitam dibalik terangnya.
     *  - Warna menyala (kuning hemat daya, merah, hijau) tidak dibalik.
     *  - Angka persen di dalam baterai berwarna (hemat daya) tetap gelap, seperti sistem.
     *  - Gambar yang kurang pekat/terang dari aslinya dinormalkan supaya setara sistem.
     */
    public static Bitmap tinted(Bitmap src, boolean darkContent, boolean srcLight, Rect batt) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        int[] pc = percentiles(px, srcLight);
        float aScale = (pc[0] > 40 && pc[0] < 250) ? 255f / pc[0] : 1f;
        float gain = (srcLight && pc[1] > 100 && pc[1] < 250) ? Math.min(1.35f, 255f / pc[1]) : 1f;
        boolean invert = (!darkContent) != srcLight;

        // kotak ikon baterai sebenarnya + apakah isinya berwarna (hemat daya/mengisi)
        int bl = 0, bt = 0, br = 0, bb = 0;
        boolean coloredFill = false;
        if (batt != null) {
            bl = batt.right; bt = batt.bottom; br = batt.left; bb = batt.top;
            int chrom = 0;
            for (int y = batt.top; y < batt.bottom; y++) {
                for (int x = batt.left; x < batt.right; x++) {
                    int p = px[y * w + x];
                    if ((p >>> 24) <= 16) continue;
                    bl = Math.min(bl, x); br = Math.max(br, x);
                    bt = Math.min(bt, y); bb = Math.max(bb, y);
                    if ((p >>> 24) >= 128 && chromatic((p >> 16) & 255, (p >> 8) & 255, p & 255)) chrom++;
                }
            }
            coloredFill = chrom >= 20 && br > bl && bb > bt;
        }

        if (!invert && aScale == 1f && gain == 1f) return src;

        int band = 4;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = p >>> 24;
            if (a == 0) continue;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            boolean chrom = chromatic(r, g, b);

            boolean digit = false;
            if (coloredFill && !chrom) {
                int x = i % w, y = i / w;
                if (x >= bl + band && x <= br - band && y >= bt + band && y <= bb - band && lum(r, g, b) <= 140) {
                    digit = true; // angka di dalam baterai: tetap gelap
                }
            }

            if (!digit) {
                if (gain != 1f) {
                    r = Math.min(255, Math.round(r * gain));
                    g = Math.min(255, Math.round(g * gain));
                    b = Math.min(255, Math.round(b * gain));
                }
                if (invert && !chrom) {
                    r = 255 - r;
                    g = 255 - g;
                    b = 255 - b;
                }
            }
            int na = aScale == 1f ? a : Math.min(255, Math.round(a * aScale));
            px[i] = (na << 24) | (r << 16) | (g << 8) | b;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }
}
