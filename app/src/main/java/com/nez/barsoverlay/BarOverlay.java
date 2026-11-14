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
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;

/**
 * Status bar dan nav bar ("screenshot" bar bawaan) ditempel ke leash jendela aplikasi
 * yang beranimasi. Masing-masing bar punya warna sendiri (hitam atau putih), jadi ada
 * empat versi siap pakai: status hitam/putih dan nav hitam/putih.
 */
public final class BarOverlay {

    public static final int STATUS = 0;
    public static final int NAV = 1;

    /** Satu bar: view + posisi dan ukurannya di layar. */
    public static final class Part {
        public View view;
        public ImageView iv;
        public int x, y, w, h;
        public boolean usesShot;
    }

    private static final class Slot {
        int kind;
        boolean dark;
        Part part;
        SurfaceControlViewHost host;
        SurfaceControlViewHost.SurfacePackage pkg;
        volatile SurfaceControl sc;
    }

    private static volatile Slot statDark, statLight, navDark, navLight;
    private static volatile Slot curStat, curNav; // yang sedang menempel

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

    /** Bangun satu bar. Pakai snapshot bar asli; kalau belum ada, gambar sendiri (cadangan). */
    public static Part build(Context c, int kind, boolean dark, int screenW, int screenH) {
        float d = c.getResources().getDisplayMetrics().density;
        SystemBars.Shot shot = kind == STATUS ? SystemBars.status : SystemBars.nav;
        Part p = new Part();
        if (shot != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageBitmap(SystemBars.tinted(shot.bmp, dark, shot.batt));
            p.view = iv;
            p.iv = iv;
            p.x = shot.x;
            p.y = shot.y;
            p.w = shot.bmp.getWidth();
            p.h = shot.bmp.getHeight();
            p.usesShot = true;
            return p;
        }
        FrameLayout f = new FrameLayout(c);
        p.view = f;
        p.x = 0;
        if (kind == STATUS) {
            p.w = screenW;
            p.h = dp(d, 40);
            p.y = 0;
            TextClock clock = new TextClock(c);
            clock.setFormat24Hour("H:mm");
            clock.setFormat12Hour("h:mm");
            style(clock, dark);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            cp.setMargins(dp(d, 28), dp(d, 10), 0, 0);
            f.addView(clock, cp);
            TextView bat = new TextView(c);
            bat.setText(battery(c));
            style(bat, dark);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            bp.setMargins(0, dp(d, 10), dp(d, 28), 0);
            f.addView(bat, bp);
        } else {
            p.w = screenW;
            p.h = dp(d, 24);
            p.y = screenH - p.h;
            View pill = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setColor(dark ? Color.BLACK : Color.WHITE);
            g.setStroke(1, dark ? 0x33FFFFFF : 0x33000000);
            g.setCornerRadius(dp(d, 5));
            pill.setBackground(g);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                    dp(d, 134), dp(d, 5), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            pp.setMargins(0, 0, 0, dp(d, 8));
            f.addView(pill, pp);
        }
        return p;
    }

    private static Slot make(Context c, int kind, boolean dark, int w, int h) {
        Slot s = new Slot();
        s.kind = kind;
        s.dark = dark;
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        Display disp = dm.getDisplay(Display.DEFAULT_DISPLAY);
        s.part = build(c, kind, dark, w, h);
        s.host = new SurfaceControlViewHost(c, disp, new Binder());
        s.host.setView(s.part.view, s.part.w, s.part.h);
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

    public static boolean isWarm() {
        return statDark != null && statLight != null && navDark != null && navLight != null;
    }

    /** Bit snapshot yang dipakai versi siap-pakai saat ini (-1 kalau belum siap). */
    public static int builtState() {
        Slot a = statDark, b = navDark;
        if (a == null || b == null) return -1;
        return (a.part.usesShot ? 1 : 0) | (b.part.usesShot ? 2 : 0);
    }

    /** Siapkan keempat versi lebih dulu supaya frame pertama animasi sudah ada bar-nya. Main thread. */
    public static void prewarm(Context c, int w, int h) {
        release();
        try {
            statDark = make(c, STATUS, true, w, h);
            statLight = make(c, STATUS, false, w, h);
            navDark = make(c, NAV, true, w, h);
            navLight = make(c, NAV, false, w, h);
        } catch (Throwable t) {
            XposedBridge.log(t);
            release();
        }
    }

    /** Ganti gambar snapshot di versi siap-pakai dengan yang terbaru. Main thread. */
    public static void refreshShots() {
        apply(statDark);
        apply(statLight);
        apply(navDark);
        apply(navLight);
    }

    private static void apply(Slot s) {
        if (s == null || s.part == null || s.part.iv == null) return;
        SystemBars.Shot sh = s.kind == STATUS ? SystemBars.status : SystemBars.nav;
        if (sh != null) {
            s.part.iv.setImageBitmap(SystemBars.tinted(sh.bmp, s.dark, sh.batt));
        }
    }

    private static SurfaceControl scOf(Slot s) {
        SurfaceControl sc = s != null ? s.sc : null;
        return (sc != null && sc.isValid()) ? sc : null;
    }

    private static boolean put(SurfaceControl.Transaction tx, SurfaceControl leash, Slot s) {
        SurfaceControl sc = scOf(s);
        if (sc == null) return false;
        if (s.kind == STATUS) curStat = s; else curNav = s;
        tx.reparent(sc, leash)
                .setPosition(sc, s.part.x, s.part.y)
                .setLayer(sc, Integer.MAX_VALUE)
                .setVisibility(sc, true);
        return true;
    }

    /** Tempel lewat transaksi awal animasi (tx): muncul bersama frame pertama. Thread mana pun. */
    public static boolean attachTo(SurfaceControl.Transaction tx, SurfaceControl leash,
                                   boolean darkStatus, boolean darkNav) {
        try {
            if (leash == null || !leash.isValid()) return false;
            boolean a = put(tx, leash, darkStatus ? statDark : statLight);
            boolean b = put(tx, leash, darkNav ? navDark : navLight);
            return a || b;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
    }

    /** Tempel langsung dengan transaksi sendiri. */
    public static boolean attachNow(SurfaceControl leash, boolean darkStatus, boolean darkNav) {
        try {
            if (leash == null || !leash.isValid()) return false;
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            boolean a = put(tx, leash, darkStatus ? statDark : statLight);
            boolean b = put(tx, leash, darkNav ? navDark : navLight);
            if (!a && !b) return false;
            tx.apply();
            return true;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
    }

    /** SurfaceControl bar yang sedang menempel (untuk dikecualikan saat membaca piksel aplikasi). */
    public static SurfaceControl[] currentScs() {
        SurfaceControl a = scOf(curStat), b = scOf(curNav);
        if (a != null && b != null) return new SurfaceControl[]{a, b};
        if (a != null) return new SurfaceControl[]{a};
        if (b != null) return new SurfaceControl[]{b};
        return new SurfaceControl[0];
    }

    /**
     * Pindahkan bar dari leash jendela aplikasi ke jendela bar asli (yang sedang disembunyikan),
     * seketika dan tanpa celah. Bar jadi anak jendela sistem, jadi pekat penuh seperti aslinya.
     * Mengembalikan bitmask jenis yang berhasil dipindah (1 = status bar, 2 = nav bar).
     * Boleh dipanggil dari thread mana pun.
     */
    public static int handoff(boolean darkStatus, boolean darkNav) {
        int mask = 0;
        try {
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            Slot s = darkStatus ? statDark : statLight;
            SurfaceControl ws = SystemBars.windowSc(SystemBars.TYPE_STATUS_BAR);
            SurfaceControl sc = scOf(s);
            if (s != null && s.part.usesShot && ws != null && sc != null) {
                tx.reparent(sc, ws).setPosition(sc, 0, 0).setLayer(sc, 1).setVisibility(sc, true);
                mask |= 1;
            }
            Slot n = darkNav ? navDark : navLight;
            SurfaceControl wn = SystemBars.windowSc(SystemBars.TYPE_NAVIGATION_BAR);
            SurfaceControl scn = scOf(n);
            if (n != null && n.part.usesShot && wn != null && scn != null) {
                tx.reparent(scn, wn).setPosition(scn, 0, 0).setLayer(scn, 1).setVisibility(scn, true);
                mask |= 2;
            }
            if (mask != 0) tx.apply();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return mask;
    }

    public static void release() {
        curStat = null;
        curNav = null;
        Slot[] all = {statDark, statLight, navDark, navLight};
        statDark = null;
        statLight = null;
        navDark = null;
        navLight = null;
        for (Slot s : all) free(s);
    }
}
