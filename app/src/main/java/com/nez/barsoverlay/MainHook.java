package com.nez.barsoverlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.SurfaceControl;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook generik pada RemoteTransition milik launcher. Saat animasi mulai, bar
 * ditempel ke leash jendela aplikasi (ikut animasi); saat selesai dilepas.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static Handler sH;

    private static Handler h() {
        if (sH == null) sH = new Handler(Looper.getMainLooper());
        return sH;
    }

    private static final Set<Class<?>> DONE = Collections.synchronizedSet(new HashSet<Class<?>>());
    /** Lepas bar yang tadi dipakai, lalu siapkan yang baru untuk animasi berikutnya. */
    private static final Runnable HIDE = new Runnable() {
        @Override public void run() {
            BarOverlay.release();
            warm();
        }
    };

    private static final Runnable WARM = new Runnable() {
        @Override public void run() {
            if (!BarOverlay.isWarm()) warm();
        }
    };

    private static void warm() {
        Context ctx = app();
        if (ctx == null) return;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        BarOverlay.prewarm(ctx, dm.widthPixels, dm.heightPixels);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            final ClassLoader cl = lpp.classLoader;
            final Class<?> irt = XposedHelpers.findClass("android.window.IRemoteTransition", cl);
            final Class<?> fcb = XposedHelpers.findClass("android.window.IRemoteTransitionFinishedCallback", cl);
            Class<?> rt = XposedHelpers.findClass("android.window.RemoteTransition", cl);

            XposedBridge.hookAllConstructors(rt, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    for (Object a : p.args) {
                        if (a != null && irt.isInstance(a)) hookRunner(a.getClass(), fcb);
                    }
                    h().removeCallbacks(WARM);
                    h().postDelayed(WARM, 500);
                }
            });
            XposedBridge.log("BarsOverlay: aktif di " + lpp.packageName);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void hookRunner(Class<?> c, final Class<?> fcb) {
        if (!DONE.add(c)) return;
        XposedBridge.hookAllMethods(c, "startAnimation", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Object info = null;
                SurfaceControl.Transaction tx = null;
                for (Object a : p.args) {
                    if (a == null) continue;
                    if (fcb.isInstance(a)) hookFinish(a.getClass());
                    else if (a instanceof SurfaceControl.Transaction) tx = (SurfaceControl.Transaction) a;
                    else if ("android.window.TransitionInfo".equals(a.getClass().getName())) info = a;
                }
                final SurfaceControl leash = info != null ? pickLeash(info) : null;
                if (leash == null) return;

                h().removeCallbacks(HIDE);
                // jalur utama: ikut transaksi awal animasi, jadi ada sejak frame pertama
                boolean ok = tx != null && BarOverlay.attachTo(tx, leash);
                if (!ok) {
                    // cadangan: belum siap, buat sekarang (bisa telat)
                    h().post(new Runnable() {
                        @Override public void run() {
                            Context ctx = app();
                            if (ctx == null) return;
                            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                            BarOverlay.attach(ctx, leash, dm.widthPixels, dm.heightPixels);
                        }
                    });
                }
                h().postDelayed(HIDE, 2500); // cadangan kalau callback finish tidak tertangkap
            }
        });
    }

    /** Ambil leash jendela aplikasi (bukan home/recents) dari TransitionInfo. */
    private static SurfaceControl pickLeash(Object info) {
        try {
            List<?> changes = (List<?>) XposedHelpers.callMethod(info, "getChanges");
            SurfaceControl best = null;
            int bestScore = -1;
            for (Object ch : changes) {
                Object ti = XposedHelpers.callMethod(ch, "getTaskInfo");
                if (ti == null) continue;
                int type = (Integer) XposedHelpers.callMethod(ti, "getActivityType");
                if (type == 2 || type == 3) continue; // HOME, RECENTS
                Object leash = XposedHelpers.callMethod(ch, "getLeash");
                if (!(leash instanceof SurfaceControl) || !((SurfaceControl) leash).isValid()) continue;
                int mode = (Integer) XposedHelpers.callMethod(ch, "getMode");
                int score = (mode == 1 || mode == 3) ? 2 : 1; // OPEN/TO_FRONT lebih diutamakan
                if (score > bestScore) {
                    best = (SurfaceControl) leash;
                    bestScore = score;
                }
            }
            return best;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return null;
        }
    }

    private static void hookFinish(Class<?> c) {
        if (!DONE.add(c)) return;
        XposedBridge.hookAllMethods(c, "onTransitionFinished", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                h().postDelayed(HIDE, 60);
            }
        });
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
