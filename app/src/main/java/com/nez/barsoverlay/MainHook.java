package com.nez.barsoverlay;

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

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Semua jalan di System UI (Shell transitions), jadi buka, tutup, dan swipe-up
 * lewat satu pintu yang sama, apa pun launcher-nya.
 *  - saat transisi aplikasi siap: bar ditempel ke leash jendela aplikasi (ikut animasi),
 *    status bar bawaan di-fade keluar
 *  - saat selesai (buka): bar tetap 2 detik lalu fade out sambil status bar bawaan fade in
 *  - saat selesai (tutup): bar dilepas, status bar bawaan fade in
 * Warna konten bar mengikuti status bar aplikasi: latar terang -> hitam, gelap -> putih.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final long LINGER_MS = 2000;
    private static final long FADE_MS = 300;

    private static Handler sH;

    private static Handler h() {
        if (sH == null) sH = new Handler(Looper.getMainLooper());
        return sH;
    }

    // transisi aplikasi yang sedang berjalan
    private static volatile Object pendingKey;
    private static volatile boolean pendingOpening;

    private static final Runnable RELEASE = new Runnable() {
        @Override public void run() {
            BarOverlay.release();
            warm();
        }
    };

    private static final Runnable LINGER_END = new Runnable() {
        @Override public void run() {
            LingerOverlay.fadeOutAndRemove(FADE_MS);
            StatusBarFader.show(FADE_MS);
        }
    };

    private static final Runnable FALLBACK = new Runnable() {
        @Override public void run() { endTransition(false); }
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
                protected void beforeHookedMethod(final MethodHookParam p) {
                    final Object key = p.args.length > 0 ? p.args[0] : null;
                    h().post(new Runnable() {
                        @Override public void run() { onFinished(key); }
                    });
                }
            });
            h().postDelayed(WARM, 4000);
            XposedBridge.log("BarsOverlay: aktif di SystemUI");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------------ transisi siap

    private static final class Pick {
        SurfaceControl leash;
        boolean dark;       // konten hitam?
        boolean opening;
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

        boolean viaTx = tx != null && BarOverlay.attachTo(tx, pick.leash, pick.dark);
        XposedBridge.log("BarsOverlay: transisi opening=" + pick.opening + " konten="
                + (pick.dark ? "hitam" : "putih") + " warm=" + BarOverlay.isWarm() + " viaTx=" + viaTx);

        pendingKey = key;
        pendingOpening = pick.opening;

        h().post(new Runnable() {
            @Override public void run() {
                h().removeCallbacks(LINGER_END);
                h().removeCallbacks(RELEASE);
                LingerOverlay.remove();
                StatusBarFader.hide();
                if (ctx != null && pick.opening) LingerOverlay.prepare(ctx, pick.dark);
                if (!BarOverlay.isWarm() && ctx != null) {
                    // belum siap: buat sekarang lalu tempel (agak telat)
                    int[] wh = screen(ctx);
                    BarOverlay.prewarm(ctx, wh[0], wh[1]);
                }
                // tempel ulang sebagai jaring pengaman kalau transaksi awal tidak terpakai
                BarOverlay.attachNow(pick.leash, pick.dark);
                h().removeCallbacks(FALLBACK);
                h().postDelayed(FALLBACK, 15000);
            }
        });
    }

    // ------------------------------------------------------------------ transisi selesai

    private static void onFinished(Object key) {
        Object pk = pendingKey;
        if (pk == null) return;
        boolean match = key != null && (key == pk || key.equals(pk));
        if (!match && key instanceof IBinder) return; // transisi lain
        endTransition(pendingOpening);
    }

    private static void endTransition(boolean opening) {
        pendingKey = null;
        h().removeCallbacks(FALLBACK);
        h().removeCallbacks(RELEASE);
        h().postDelayed(RELEASE, 60);
        if (opening) {
            LingerOverlay.showNow();
            h().removeCallbacks(LINGER_END);
            h().postDelayed(LINGER_END, LINGER_MS);
        } else {
            LingerOverlay.remove();
            StatusBarFader.show(180);
        }
    }

    // ------------------------------------------------------------------ baca TransitionInfo

    private static Pick pick(Object info, Context ctx) {
        try {
            List<?> changes = (List<?>) XposedHelpers.callMethod(info, "getChanges");
            Pick best = null;
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
                    pk.dark = darkContentFor(ti, ctx);
                    best = pk;
                    bestScore = score;
                }
            }
            if (best != null) best.opening = opening;
            return best;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return null;
        }
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

    /** true = konten bar hitam (latar terang), false = putih (latar gelap). */
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
        int[] wh = screen(ctx);
        BarOverlay.prewarm(ctx, wh[0], wh[1]);
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
