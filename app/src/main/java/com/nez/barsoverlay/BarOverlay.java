package com.nez.barsoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
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
import android.widget.TextClock;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;

/**
 * Jam + baterai + pill digambar di SurfaceControlViewHost lalu surface-nya di-reparent
 * ke leash jendela aplikasi yang beranimasi (ikut bergerak/mengecil/terpotong sudut).
 * Ada dua versi siap pakai: konten gelap (untuk latar terang) dan konten putih (latar gelap).
 */
public final class BarOverlay {

    private static final class Slot {
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

    /**
     * View bar penuh layar. Kalau foto status bar asli sudah ada, pakai itu (sama persis dengan sistem);
     * kalau belum, jatuh ke gambar buatan sendiri. nativeLook=true: warna asli sistem apa adanya.
     */
    public static View build(Context c, boolean darkContent, boolean nativeLook) {
        if (BarSnapshot.ready()) {
            try {
                FrameLayout f = BarSnapshot.view(c, darkContent, nativeLook);
                if (!BarSnapshot.hasBottom()) {
                    float d = c.getResources().getDisplayMetrics().density;
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
                return f;
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
        return buildFake(c, darkContent);
    }

    /** Cadangan: jam kiri atas, baterai kanan atas, pill bawah (digambar sendiri). */
    private static View buildFake(Context c, boolean darkContent) {
        float d = c.getResources().getDisplayMetrics().density;
        FrameLayout f = new FrameLayout(c);

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
        return f;
    }

    private static Slot make(Context c, boolean darkContent, int w, int h) {
        Slot s = new Slot();
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        Display disp = dm.getDisplay(Display.DEFAULT_DISPLAY);
        s.host = new SurfaceControlViewHost(c, disp, new Binder());
        s.host.setView(build(c, darkContent, false), w, h);
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
