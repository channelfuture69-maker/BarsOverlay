package com.nez.barsoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.BatteryManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;

/**
 * "Screenshot" status bar dan nav bar bawaan: view aslinya digambar ke bitmap transparan
 * (tanpa wallpaper/latar). Persen baterai disisipkan di kiri ikon baterai kalau bar asli
 * belum menampilkannya. Semua method dipanggil dari main thread.
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
        if (type == TYPE_STATUS_BAR) {
            Bitmap withPct = addPercent(ctx, v, raw);
            if (withPct != raw) {
                raw.recycle();
                b = withPct;
            }
        }
        if (old != null && old.bmp != null && old.bmp.getWidth() == b.getWidth()
                && old.bmp.getHeight() == b.getHeight() && old.bmp.sameAs(b)) {
            b.recycle();
            old.lightIcons = light;
            return old; // tidak berubah
        }
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

    /**
     * Warnai ikon abu-abu/putih/hitam jadi hitam atau putih sesuai latar aplikasi.
     * Warna menyala (kuning mode hemat daya, merah baterai rendah, hijau mengisi) dibiarkan.
     */
    public static Bitmap tinted(Bitmap src, boolean darkContent) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int tone = darkContent ? 0 : 255;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = p >>> 24;
            if (a == 0) continue;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (!chromatic(r, g, b)) {
                px[i] = (a << 24) | (tone << 16) | (tone << 8) | tone;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    // ------------------------------------------------------------------ persen baterai

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

    private static boolean hasPercentText(View root) {
        if (root.getVisibility() != View.VISIBLE) return false;
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && t.toString().endsWith("%")) return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasPercentText(g.getChildAt(i))) return true;
            }
        }
        return false;
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

    /** Sisipkan "xx%" di kiri ikon baterai (ikon sinyal/Wi-Fi digeser ke kiri). */
    private static Bitmap addPercent(Context ctx, View root, Bitmap src) {
        try {
            if (hasPercentText(root)) return src; // bar asli sudah menampilkan persen
            View bv = findByClass(root, "BatteryMeterView");
            if (bv == null || bv.getVisibility() != View.VISIBLE || bv.getWidth() <= 0) return src;

            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level < 0) return src;
            String text = (level * 100 / scale) + "%";

            float density = ctx.getResources().getDisplayMetrics().density;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFFFFFF); // abu/putih, nanti diwarnai sesuai latar
            View clock = findByClass(root, "Clock");
            if (clock instanceof TextView) {
                paint.setTextSize(((TextView) clock).getTextSize());
                paint.setTypeface(((TextView) clock).getTypeface());
            } else {
                paint.setTextSize(13f * density);
            }

            int w = src.getWidth(), h = src.getHeight();
            int bx = leftIn(root, bv);
            int by = topIn(root, bv);
            float tw = paint.measureText(text);
            int gap = Math.round(6f * density);
            int shift = Math.round(tw) + gap;

            // awal kelompok ikon (sinyal, Wi-Fi, baterai)
            int cl;
            Object parent = bv.getParent();
            if (parent instanceof View && ((View) parent).getWidth() > 0
                    && ((View) parent).getWidth() < w * 0.6f) {
                cl = Math.max(0, leftIn(root, (View) parent));
            } else {
                cl = Math.max(0, bx - Math.round(70f * density));
            }
            if (cl > bx) cl = bx;

            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawBitmap(src, new Rect(0, 0, cl, h), new Rect(0, 0, cl, h), null);
            c.drawBitmap(src, new Rect(cl, 0, bx, h), new Rect(cl - shift, 0, bx - shift, h), null);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float cy = by + bv.getHeight() / 2f;
            float baseline = cy - (fm.ascent + fm.descent) / 2f;
            c.drawText(text, bx - tw - gap / 2f, baseline, paint);
            c.drawBitmap(src, new Rect(bx, 0, w, h), new Rect(bx, 0, w, h), null);
            return out;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return src;
        }
    }
}
