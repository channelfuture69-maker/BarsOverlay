package com.nez.barsoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;

/**
 * Jam + baterai + pill digambar di SurfaceControlViewHost lalu surface-nya di-reparent
 * ke leash jendela aplikasi yang beranimasi (ikut bergerak/mengecil/terpotong sudut).
 * Ada dua versi siap pakai: konten gelap (untuk latar terang) dan konten putih (latar gelap).
 */
public final class BarOverlay {

    /** Hasil build: view akar + dua gambar snapshot (kalau ada). */
    public static final class Built {
        public View root;
        public ImageView top, bottom;
        public int state; // bit snapshot yang dipakai saat dibuat
    }

    private static final class Slot {
        Built built;
        SurfaceControlViewHost host;
        SurfaceControlViewHost.SurfacePackage pkg;
        volatile SurfaceControl sc;
    }

    private static volatile Slot dark;   // konten hitam
    private static volatile Slot light;  // konten putih

    private BarOverlay() {}

    private static int dp(float d, float v) { return Math.round(v * d); }

    private static void style(TextView t, boolean darkContent) {
        t.setTextColor(darkContent ? Color.BLACK : Color.WHITE);
        t.setTextSize(15f);
        t.setShadowLayer(2f, 0f, 0f, darkContent ? 0x55FFFFFF : 0x99000000);
    }

    private static String battery(Context c) {
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return (l * 100 / s) + "%";
        } catch (Throwable t) {
            return "";
        }
    }

    private static ColorMatrixColorFilter mono(boolean darkContent) {
        float v = darkContent ? 0f : 255f;
        return new ColorMatrixColorFilter(new float[]{
                0, 0, 0, 0, v,
                0, 0, 0, 0, v,
                0, 0, 0, 0, v,
                0, 0, 0, 1, 0});
    }

    private static ImageView shotView(Context c, SystemBars.Shot shot, boolean darkContent, FrameLayout f) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setColorFilter(mono(darkContent));
        iv.setImageBitmap(shot.bmp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                shot.bmp.getWidth(), shot.bmp.getHeight(), Gravity.TOP | Gravity.START);
        lp.setMargins(shot.x, shot.y, 0, 0);
        f.addView(iv, lp);
        return iv;
    }

    /**
     * Bar penuh layar. Memakai snapshot status bar/nav bar bawaan (diwarnai hitam atau putih);
     * kalau snapshot belum ada, pakai gambar sendiri (jam, baterai, pill).
     */
    public static Built build(Context c, boolean darkContent) {
        float d = c.getResources().getDisplayMetrics().density;
        FrameLayout f = new FrameLayout(c);
        Built b = new Built();
        b.root = f;
        SystemBars.Shot st = SystemBars.status;
        SystemBars.Shot nv = SystemBars.nav;
        b.state = SystemBars.state();

        if (st != null) {
            b.top = shotView(c, st, darkContent, f);
        } else {
            TextClock clock = new TextClock(c);
            clock.setFormat24Hour("H:mm");
            clock.setFormat12Hour("h:mm");
            style(clock, darkContent);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            cp.setMargins(dp(d, 28), dp(d, 10), 0, 0);
            f.addView(clock, cp);

            TextView bat = new TextView(c);
            bat.setText(battery(c));
            style(bat, darkContent);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            bp.setMargins(0, dp(d, 10), dp(d, 28), 0);
            f.addView(bat, bp);
        }

        if (nv != null) {
            b.bottom = shotView(c, nv, darkContent, f);
        } else {
            View pill = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setColor(darkContent ? Color.BLACK : Color.WHITE);
            g.setStroke(1, darkContent ? 0x33FFFFFF : 0x33000000);
            g.setCornerRadius(dp(d, 5));
            pill.setBackground(g);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                    dp(d, 134), dp(d, 5), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            pp.setMargins(0, 0, 0, dp(d, 8));
            f.addView(pill, pp);
        }
        return b;
    }

    /** Bit snapshot yang dipakai versi siap-pakai saat ini (0 kalau belum ada). */
    public static int builtState() {
        Slot s = dark;
        return (s != null && s.built != null) ? s.built.state : -1;
    }

    /** Ganti gambar snapshot di versi siap-pakai dengan yang terbaru. Main thread. */
    public static void refreshShots() {
        apply(dark);
        apply(light);
    }

    private static void apply(Slot s) {
        if (s == null || s.built == null) return;
        SystemBars.Shot st = SystemBars.status, nv = SystemBars.nav;
        if (s.built.top != null && st != null) s.built.top.setImageBitmap(st.bmp);
        if (s.built.bottom != null && nv != null) s.built.bottom.setImageBitmap(nv.bmp);
    }

    private static Slot make(Context c, boolean darkContent, int w, int h) {
        Slot s = new Slot();
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        Display disp = dm.getDisplay(Display.DEFAULT_DISPLAY);
        s.host = new SurfaceControlViewHost(c, disp, new Binder());
        s.built = build(c, darkContent);
        s.host.setView(s.built.root, w, h);
        s.pkg = s.host.getSurfacePackage();
        s.sc = s.pkg.getSurfaceControl();
        return s;
    }

    private static void free(Slot s) {
        if (s == null) return;
        s.sc = null;
        try { if (s.pkg != null) s.pkg.release(); } catch (Throwable ignored) { }
        try { if (s.host != null) s.host.release(); } catch (Throwable ignored) { }
    }

    public static boolean isWarm() { return dark != null && light != null; }

    /** Siapkan kedua versi lebih dulu supaya frame pertama animasi sudah ada bar-nya. Main thread. */
    public static void prewarm(Context c, int w, int h) {
        release();
        try {
            dark = make(c, true, w, h);
            light = make(c, false, w, h);
        } catch (Throwable t) {
            XposedBridge.log(t);
            release();
        }
    }

    private static SurfaceControl scFor(boolean darkContent) {
        Slot s = darkContent ? dark : light;
        SurfaceControl sc = s != null ? s.sc : null;
        return (sc != null && sc.isValid()) ? sc : null;
    }

    /** Tempel lewat transaksi awal animasi (tx): muncul bersama frame pertama. Thread mana pun. */
    public static boolean attachTo(SurfaceControl.Transaction tx, SurfaceControl leash, boolean darkContent) {
        try {
            SurfaceControl sc = scFor(darkContent);
            if (sc == null || leash == null || !leash.isValid()) return false;
            tx.reparent(sc, leash).setLayer(sc, Integer.MAX_VALUE).setVisibility(sc, true);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
    }

    /** Tempel langsung dengan transaksi sendiri. */
    public static boolean attachNow(SurfaceControl leash, boolean darkContent) {
        try {
            SurfaceControl sc = scFor(darkContent);
            if (sc == null || leash == null || !leash.isValid()) return false;
            new SurfaceControl.Transaction()
                    .reparent(sc, leash)
                    .setLayer(sc, Integer.MAX_VALUE)
                    .setVisibility(sc, true)
                    .apply();
            return true;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
    }

    public static void release() {
        Slot a = dark, b = light;
        dark = null;
        light = null;
        free(a);
        free(b);
    }
}
