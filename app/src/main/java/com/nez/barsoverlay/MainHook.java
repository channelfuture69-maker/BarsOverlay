package com.nez.barsoverlay;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.SurfaceControl;
import android.view.WindowManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Semua jalan di System UI (Shell transitions): buka, tutup, dan swipe-up lewat satu pintu.
 *  - transisi siap: "screenshot" status bar + nav bar ditempel ke leash jendela aplikasi;
 *    bar asli disembunyikan seketika
 *  - selesai: bar salinan dipindah seketika jadi anak jendela bar asli (pekat penuh, tanpa
 *    celah). Tutup: bar asli langsung muncul. Buka: salinan bertahan 2 detik, lalu bar asli
 *    muncul seketika dan salinan dilepas
 * Warna status bar dan nav bar ditentukan terpisah dari piksel latar aplikasi yang asli:
 * latar terang -> hitam, latar gelap -> putih.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final long LINGER_MS = 2000;
    private static final int LIGHT_BG = 160; // terang rata-rata latar >= ini -> konten hitam

    private static Handler sH;

    private static Handler h() {
        if (sH == null) sH = new Handler(Looper.getMainLooper());
        return sH;
    }

    // transisi aplikasi yang sedang berjalan
    private static volatile Object pendingKey;
    private static volatile boolean pendingOpening;
    private static volatile SurfaceControl pendingLeash;
    private static volatile String pendingPkg;
    private static volatile boolean pendingDarkStatus, pendingDarkNav;

    /** Warna konten bar yang sudah dipelajari per aplikasi (true = hitam), terpisah status/nav. */
    private static final Map<String, Boolean> TONES_S = new ConcurrentHashMap<String, Boolean>();
    private static final Map<String, Boolean> TONES_N = new ConcurrentHashMap<String, Boolean>();

    private static final Runnable RELEASE = new Runnable() {
        @Override public void run() {
            BarOverlay.release();
            warm();
        }
    };

    /** Bar asli muncul seketika, salinan dilepas sesudahnya (jeda sebentar supaya tidak berkedip). */
    private static final Runnable LINGER_END = new Runnable() {
        @Override public void run() {
            StatusBarFader.showAll();
            h().removeCallbacks(RELEASE);
            h().postDelayed(RELEASE, 80);
        }
    };

    private static final Runnable FALLBACK = new Runnable() {
        @Override public void run() { endTransition(false, 0); }
    };

    /** Ambil "screenshot" bar bawaan secara berkala selama tidak ada animasi. */
    private static final Runnable SNAP = new Runnable() {
        @Override public void run() {
            try {
                Context ctx = app();
                if (ctx != null && pendingKey == null && !StatusBarFader.isHidden() && interactive(ctx)) {
                    if (SystemBars.captureAll(ctx)) BarOverlay.refreshShots();
                    // snapshot baru muncul pertama kali: bangun ulang supaya tidak pakai gambar cadangan
                    if (BarOverlay.isWarm() && BarOverlay.builtState() != SystemBars.state()) warm();
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            h().postDelayed(this, 1500);
        }
    };

    private static final Runnable WARM = new Runnable() {
        @Override public void run() {
            if (app() == null) {
                h().postDelayed(this, 2000);
            } else if (!BarOverlay.isWarm()) {
                warm();
            }
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.android.systemui".equals(lpp.packageName)) return; // semuanya di System UI
        try {
            Class<?> tr = XposedHelpers.findClass("com.android.wm.shell.transition.Transitions", lpp.classLoader);
            XposedBridge.hookAllMethods(tr, "onTransitionReady", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try { onReady(p.args); } catch (Throwable t) { XposedBridge.log(t); }
                }
            });
            XposedBridge.hookAllMethods(tr, "onFinish", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        onFinishHook(p.args.length > 0 ? p.args[0] : null);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
            h().postDelayed(WARM, 4000);
            h().postDelayed(SNAP, 5000);
            XposedBridge.log("BarsOverlay: aktif di SystemUI");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------------ transisi siap

    private static final class Pick {
        SurfaceControl leash;
        boolean darkStatus;  // konten status bar hitam?
        boolean darkNav;     // konten nav bar hitam?
        boolean opening;
        String pkg;
    }

    private static void onReady(Object[] args) {
        Object key = args.length > 0 ? args[0] : null;
        Object info = null;
        SurfaceControl.Transaction tx = null;
        for (Object a : args) {
            if (a == null) continue;
            if ("android.window.TransitionInfo".equals(a.getClass().getName())) info = a;
            else if (tx == null && a instanceof SurfaceControl.Transaction) tx = (SurfaceControl.Transaction) a;
        }
        if (info == null) return;
        final Context ctx = app();
        final Pick pick = pick(info, ctx);
        if (pick == null) return; // bukan transisi aplikasi layar penuh

        boolean viaTx = tx != null && BarOverlay.attachTo(tx, pick.leash, pick.darkStatus, pick.darkNav);
        XposedBridge.log("BarsOverlay: transisi opening=" + pick.opening + " pkg=" + pick.pkg
                + " status=" + (pick.darkStatus ? "hitam" : "putih") + " nav=" + (pick.darkNav ? "hitam" : "putih")
                + " warm=" + BarOverlay.isWarm() + " viaTx=" + viaTx);

        pendingKey = key;
        pendingOpening = pick.opening;
        pendingLeash = pick.leash;
        pendingPkg = pick.pkg;
        pendingDarkStatus = pick.darkStatus;
        pendingDarkNav = pick.darkNav;

        h().post(new Runnable() {
            @Override public void run() {
                h().removeCallbacks(LINGER_END);
                h().removeCallbacks(RELEASE);
                // ambil snapshot terbaru selagi bar asli masih tampil, baru disembunyikan seketika
                if (ctx != null && SystemBars.captureAll(ctx)) BarOverlay.refreshShots();
                StatusBarFader.hide();
                if (!BarOverlay.isWarm() && ctx != null) {
                    // belum siap: buat sekarang lalu tempel (agak telat)
                    int[] wh = screen(ctx);
                    BarOverlay.prewarm(ctx, wh[0], wh[1]);
                }
                // tempel ulang sebagai jaring pengaman kalau transaksi awal tidak terpakai
                BarOverlay.attachNow(pick.leash, pick.darkStatus, pick.darkNav);
                h().removeCallbacks(FALLBACK);
                h().postDelayed(FALLBACK, 15000);
            }
        });
    }

    // ------------------------------------------------------------------ transisi selesai

    /** Jalan di thread Shell saat animasi selesai, selagi leash aplikasi masih hidup. */
    private static void onFinishHook(Object key) {
        Object pk = pendingKey;
        if (pk == null) return;
        boolean match = key != null && (key == pk || key.equals(pk));
        if (!match && key instanceof IBinder) return; // transisi lain
        pendingKey = null;

        final boolean opening = pendingOpening;
        boolean ds = pendingDarkStatus, dn = pendingDarkNav;

        // baca ulang warna dari piksel aplikasi yang sudah tampil penuh (bar salinan dikecualikan)
        Context ctx = app();
        if (ctx != null) {
            Boolean[] px = pixelTones(pendingLeash, BarOverlay.currentScs(), ctx);
            if (px[0] != null) ds = px[0];
            if (px[1] != null) dn = px[1];
            learn(pendingPkg, px);
        }

        // serahkan bar dari leash ke jendela bar asli, seketika
        final int handed = BarOverlay.handoff(ds, dn);
        XposedBridge.log("BarsOverlay: selesai opening=" + opening + " status="
                + (ds ? "hitam" : "putih") + " nav=" + (dn ? "hitam" : "putih") + " handoff=" + handed);

        h().post(new Runnable() {
            @Override public void run() { endTransition(opening, handed); }
        });
    }

    private static void endTransition(boolean opening, int handed) {
        pendingKey = null;
        pendingLeash = null;
        h().removeCallbacks(FALLBACK);
        h().removeCallbacks(RELEASE);
        h().removeCallbacks(LINGER_END);
        if (!opening) {
            // tutup: bar asli langsung muncul, salinan dilepas sebentar lagi
            StatusBarFader.showAll();
            h().postDelayed(RELEASE, 80);
            return;
        }
        // buka: jenis yang gagal dipindah langsung diganti bar asli supaya tidak hilang
        if ((handed & 1) == 0) StatusBarFader.showType(SystemBars.TYPE_STATUS_BAR);
        if ((handed & 2) == 0) StatusBarFader.showType(SystemBars.TYPE_NAVIGATION_BAR);
        if (handed == 0) {
            StatusBarFader.showAll();
            h().postDelayed(RELEASE, 80);
        } else {
            h().postDelayed(LINGER_END, LINGER_MS);
        }
    }

    // ------------------------------------------------------------------ warna dari piksel

    /**
     * Warna konten bar dari piksel latar aplikasi: [0] status bar, [1] nav bar.
     * true = latar terang -> konten hitam. null = tidak terbaca.
     */
    private static Boolean[] pixelTones(SurfaceControl leash, SurfaceControl[] exclude, Context ctx) {
        Boolean[] r = new Boolean[2];
        if (leash == null || !leash.isValid() || ctx == null) return r;
        int[] wh = screen(ctx);
        int w = wh[0], hgt = wh[1];
        SystemBars.Shot st = SystemBars.status, nv = SystemBars.nav;
        int sy = st != null ? st.y : 0;
        int sh = st != null ? st.bmp.getHeight() : 90;
        Integer ls = AppPixels.avgLum(leash, new Rect(0, sy, w, Math.min(hgt, sy + sh)), true, exclude);
        int nh = Math.min(nv != null ? nv.bmp.getHeight() : 48, 48);
        Integer ln = AppPixels.avgLum(leash, new Rect(w / 4, hgt - nh, w * 3 / 4, hgt), false, exclude);
        r[0] = ls == null ? null : ls >= LIGHT_BG;
        r[1] = ln == null ? null : ln >= LIGHT_BG;
        return r;
    }

    private static void learn(String pkg, Boolean[] px) {
        if (pkg == null) return;
        if (px[0] != null) TONES_S.put(pkg, px[0]);
        if (px[1] != null) TONES_N.put(pkg, px[1]);
    }

    // ------------------------------------------------------------------ baca TransitionInfo

    private static Pick pick(Object info, Context ctx) {
        try {
            List<?> changes = (List<?>) XposedHelpers.callMethod(info, "getChanges");
            Pick best = null;
            Object bestTi = null;
            int bestScore = -1;
            boolean opening = false;
            for (Object ch : changes) {
                Object ti = XposedHelpers.callMethod(ch, "getTaskInfo");
                if (ti == null) continue;
                int type = (Integer) XposedHelpers.callMethod(ti, "getActivityType");
                if (type == 2 || type == 3) continue; // HOME, RECENTS
                Integer wm = callInt(ti, "getWindowingMode");
                if (wm != null && wm != 1) continue;  // hanya layar penuh
                Object leash = XposedHelpers.callMethod(ch, "getLeash");
                if (!(leash instanceof SurfaceControl) || !((SurfaceControl) leash).isValid()) continue;
                int mode = (Integer) XposedHelpers.callMethod(ch, "getMode");
                boolean open = mode == 1 || mode == 3; // OPEN, TO_FRONT
                opening |= open;
                int score = open ? 2 : 1;
                if (score > bestScore) {
                    Pick pk = new Pick();
                    pk.leash = (SurfaceControl) leash;
                    best = pk;
                    bestTi = ti;
                    bestScore = score;
                }
            }
            if (best == null) return null;
            best.opening = opening;
            best.pkg = pkgOf(bestTi);

            // urutan: piksel latar asli -> hasil belajar -> bar asli (tutup) -> tebakan
            Boolean[] px = pixelTones(best.leash, null, ctx); // bar salinan belum menempel di sini
            learn(best.pkg, px);
            best.darkStatus = px[0] != null ? px[0] : decideStatus(bestTi, ctx, best.pkg, opening);
            best.darkNav = px[1] != null ? px[1] : decideNav(bestTi, ctx, best.pkg, opening, best.darkStatus);
            return best;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return null;
        }
    }

    private static String pkgOf(Object ti) {
        try {
            Object c = XposedHelpers.getObjectField(ti, "topActivity");
            if (c == null) c = XposedHelpers.getObjectField(ti, "baseActivity");
            return c instanceof ComponentName ? ((ComponentName) c).getPackageName() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** true = status bar hitam. Dipakai kalau piksel tidak terbaca. */
    private static boolean decideStatus(Object ti, Context ctx, String pkg, boolean opening) {
        if (pkg != null) {
            Boolean learned = TONES_S.get(pkg);
            if (learned != null) return learned;
        }
        if (!opening) {
            SystemBars.Shot st = SystemBars.status;
            if (st != null) return !st.lightIcons;
        }
        return darkContentFor(ti, ctx);
    }

    /** true = nav bar hitam. Dipisah dari status bar: latar di bawah bisa beda dengan di atas. */
    private static boolean decideNav(Object ti, Context ctx, String pkg, boolean opening, boolean statusDark) {
        if (pkg != null) {
            Boolean learned = TONES_N.get(pkg);
            if (learned != null) return learned;
        }
        if (!opening) {
            SystemBars.Shot nv = SystemBars.nav;
            if (nv != null) return !nv.lightIcons;
        }
        return darkNavFor(ti, statusDark);
    }

    private static Integer callInt(Object o, String m) {
        try {
            return (Integer) XposedHelpers.callMethod(o, m);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isLight(int color) {
        double lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
        return lum > 150;
    }

    /** Tebakan status bar: true = konten hitam (latar terang), false = putih (latar gelap). */
    private static boolean darkContentFor(Object ti, Context ctx) {
        try {
            Object td = XposedHelpers.getObjectField(ti, "taskDescription");
            if (td != null) {
                Integer sb = callInt(td, "getStatusBarColor");
                if (sb != null && Color.alpha(sb) >= 200) return isLight(sb);
                Integer ap = callInt(td, "getSystemBarsAppearance");
                if (ap != null && (ap & 0x8) != 0) return true; // APPEARANCE_LIGHT_STATUS_BARS
                Integer bg = callInt(td, "getBackgroundColor");
                if (bg != null && Color.alpha(bg) >= 200) return isLight(bg);
                Integer pr = callInt(td, "getPrimaryColor");
                if (pr != null && Color.alpha(pr) >= 200) return isLight(pr);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        if (ctx != null) {
            int night = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night != Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    /** Tebakan nav bar: warna nav bar aplikasi -> flag terang -> warna latar -> samakan dengan status bar. */
    private static boolean darkNavFor(Object ti, boolean statusDark) {
        try {
            Object td = XposedHelpers.getObjectField(ti, "taskDescription");
            if (td != null) {
                Integer nb = callInt(td, "getNavigationBarColor");
                if (nb != null && Color.alpha(nb) >= 200) return isLight(nb);
                Integer ap = callInt(td, "getSystemBarsAppearance");
                if (ap != null && (ap & 0x10) != 0) return true; // APPEARANCE_LIGHT_NAVIGATION_BARS
                Integer bg = callInt(td, "getBackgroundColor");
                if (bg != null && Color.alpha(bg) >= 200) return isLight(bg);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return statusDark;
    }

    // ------------------------------------------------------------------ util

    private static int[] screen(Context ctx) {
        try {
            WindowManager wm = ctx.getSystemService(WindowManager.class);
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new int[]{b.width(), b.height()};
        } catch (Throwable t) {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return new int[]{dm.widthPixels, dm.heightPixels};
        }
    }

    private static void warm() {
        Context ctx = app();
        if (ctx == null) return;
        if (!StatusBarFader.isHidden()) SystemBars.captureAll(ctx);
        int[] wh = screen(ctx);
        BarOverlay.prewarm(ctx, wh[0], wh[1]);
        XposedBridge.log("BarsOverlay: siap, snapshot status=" + (SystemBars.status != null)
                + " nav=" + (SystemBars.nav != null));
    }

    private static boolean interactive(Context ctx) {
        try {
            return ctx.getSystemService(android.os.PowerManager.class).isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private static Context app() {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }
}
