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
 * Jam + baterai + pill digambar di dalam SurfaceControlViewHost, lalu surface-nya
 * di-reparent ke leash jendela aplikasi yang sedang beranimasi. Jadi ikut bergerak,
 * mengecil, dan terpotong sudut membulat bersama jendelanya (seperti iOS).
 * Semua method dipanggil dari main thread.
 */
public final class BarOverlay {

    private static SurfaceControlViewHost host;
    private static SurfaceControlViewHost.SurfacePackage pkg;

    private BarOverlay() {}

    private static int dp(float d, float v) { return Math.round(v * d); }

    private static void style(TextView t) {
        t.setTextColor(Color.WHITE);
        t.setTextSize(15f);
        t.setShadowLayer(3f, 0f, 0f, 0x99000000);
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

    private static View build(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        FrameLayout f = new FrameLayout(c);

        TextClock clock = new TextClock(c);
        clock.setFormat24Hour("H:mm");
        clock.setFormat12Hour("h:mm");
        style(clock);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cp.setMargins(dp(d, 28), dp(d, 10), 0, 0);
        f.addView(clock, cp);

        TextView bat = new TextView(c);
        bat.setText(battery(c));
        style(bat);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        bp.setMargins(0, dp(d, 10), dp(d, 28), 0);
        f.addView(bat, bp);

        View pill = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(1, 0x33000000);
        g.setCornerRadius(dp(d, 5));
        pill.setBackground(g);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                dp(d, 134), dp(d, 5), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pp.setMargins(0, 0, 0, dp(d, 8));
        f.addView(pill, pp);
        return f;
    }

    private static volatile SurfaceControl warmSc;

    public static boolean isWarm() { return warmSc != null; }

    /** Siapkan host + gambar bar lebih dulu supaya frame pertama animasi sudah ada bar-nya. */
    public static void prewarm(Context c, int w, int h) {
        release();
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display disp = dm.getDisplay(Display.DEFAULT_DISPLAY);
            host = new SurfaceControlViewHost(c, disp, new Binder());
            host.setView(build(c), w, h);
            pkg = host.getSurfacePackage();
            warmSc = pkg.getSurfaceControl();
        } catch (Throwable t) {
            XposedBridge.log(t);
            release();
        }
    }

    /**
     * Tempel bar siap-pakai ke leash lewat transaksi awal animasi (tx), jadi muncul
     * bersamaan dengan frame pertama. Boleh dipanggil dari thread mana pun.
     */
    public static boolean attachTo(SurfaceControl.Transaction tx, SurfaceControl leash) {
        try {
            SurfaceControl sc = warmSc;
            if (sc == null || !sc.isValid()) return false;
            tx.reparent(sc, leash)
                    .setLayer(sc, Integer.MAX_VALUE)
                    .setVisibility(sc, true);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
    }

    /** Tempel langsung dengan transaksi sendiri (tanpa menunggu transaksi animasi). */
    public static boolean attachNow(SurfaceControl leash) {
        try {
            SurfaceControl sc = warmSc;
            if (sc == null || !sc.isValid() || leash == null || !leash.isValid()) return false;
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

    /** Cadangan kalau belum ada yang siap: buat sekarang lalu tempel (agak telat). */
    public static void attach(Context c, SurfaceControl leash, int w, int h) {
        prewarm(c, w, h);
        try {
            SurfaceControl sc = warmSc;
            if (sc == null) return;
            new SurfaceControl.Transaction()
                    .reparent(sc, leash)
                    .setLayer(sc, Integer.MAX_VALUE)
                    .setVisibility(sc, true)
                    .apply();
        } catch (Throwable t) {
            XposedBridge.log(t);
            release();
        }
    }

    public static void release() {
        warmSc = null;
        try {
            if (pkg != null) pkg.release();
        } catch (Throwable ignored) {
        }
        try {
            if (host != null) host.release();
        } catch (Throwable ignored) {
        }
        pkg = null;
        host = null;
    }
}
