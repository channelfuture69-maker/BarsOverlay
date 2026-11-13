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
 * Launcher: bar (jam/baterai/pill) ditempel ke jendela aplikasi yang beranimasi.
 *   - jalur 1: RemoteTransition (buka/tutup lewat Shell transitions)
 *   - jalur 2: IRecentsAnimationRunner (tutup lewat gesture swipe-up/home)
 *   - jalur 3: IRemoteAnimationRunner (animasi remote versi lama)
 * System UI: status bar bawaan di-fade saat ada transisi aplikasi.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static Handler sH;

    private static Handler h() {
        if (sH == null) sH = new Handler(Looper.getMainLooper());
        return sH;
    }

    private static final Set<String> DONE = Collections.synchronizedSet(new HashSet<String>());

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

    private static final XC_MethodHook FINISH_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            h().removeCallbacks(HIDE);
            h().postDelayed(HIDE, 60);
        }
    };

    private static void warm() {
        Context ctx = app();
        if (ctx == null) return;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        BarOverlay.prewarm(ctx, dm.widthPixels, dm.heightPixels);
    }

    private static void warmLater() {
        h().removeCallbacks(WARM);
        h().postDelayed(WARM, 500);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        final ClassLoader cl = lpp.classLoader;
        if ("com.android.systemui".equals(lpp.packageName)) {
            hookSystemUi(cl);
            return;
        }
        try { hookTransitions(cl); } catch (Throwable t) { XposedBridge.log(t); }
        try { hookRecents(cl); } catch (Throwable t) { XposedBridge.log(t); }
        try { hookLegacy(cl); } catch (Throwable t) { XposedBridge.log(t); }
        XposedBridge.log("BarsOverlay: aktif di " + lpp.packageName);
    }

    // ------------------------------------------------------------------ helper hook

    /** Hook method bernama name pada kelas c dan induknya (sampai kelas framework). */
    private static void hookChain(Class<?> c, String name, XC_MethodHook cb) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.startsWith("android.") || n.startsWith("java.") || n.startsWith("com.android.internal.")) break;
            if (DONE.add(n + "#" + name)) XposedBridge.hookAllMethods(k, name, cb);
        }
    }

    /** Hook persis pada kelas c (untuk proxy framework seperti callback finish). */
    private static void hookExact(Class<?> c, String name, XC_MethodHook cb) {
        if (DONE.add(c.getName() + "#" + name)) XposedBridge.hookAllMethods(c, name, cb);
    }

    // ------------------------------------------------------------------ mulai bar

    private static void startBars(final SurfaceControl leash, SurfaceControl.Transaction tx,
                                  long fallbackMs, String path) {
        if (leash == null) {
            XposedBridge.log("BarsOverlay[" + path + "]: leash aplikasi tidak ketemu");
            return;
        }
        h().removeCallbacks(HIDE);
        boolean warm = BarOverlay.isWarm();
        boolean viaTx = tx != null && BarOverlay.attachTo(tx, leash);
        boolean now = tx == null && BarOverlay.attachNow(leash);
        XposedBridge.log("BarsOverlay[" + path + "]: warm=" + warm + " viaTx=" + viaTx + " now=" + now);

        // jaring pengaman: tempel ulang sedikit kemudian kalau transaksi awal tidak terpakai
        h().postDelayed(new Runnable() {
            @Override public void run() {
                if (BarOverlay.isWarm()) {
                    BarOverlay.attachNow(leash);
                } else {
                    Context ctx = app();
                    if (ctx == null) return;
                    DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                    BarOverlay.attach(ctx, leash, dm.widthPixels, dm.heightPixels);
                }
            }
        }, 40);
        h().postDelayed(HIDE, fallbackMs);
    }

    // ------------------------------------------------------------------ jalur 1: RemoteTransition

    private static void hookTransitions(ClassLoader cl) {
        final Class<?> irt = XposedHelpers.findClass("android.window.IRemoteTransition", cl);
        final Class<?> fcb = XposedHelpers.findClass("android.window.IRemoteTransitionFinishedCallback", cl);
        Class<?> rt = XposedHelpers.findClass("android.window.RemoteTransition", cl);

        final XC_MethodHook start = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Object info = null;
                SurfaceControl.Transaction tx = null;
                for (Object a : p.args) {
                    if (a == null) continue;
                    if (fcb.isInstance(a)) hookExact(a.getClass(), "onTransitionFinished", FINISH_HOOK);
                    else if (a instanceof SurfaceControl.Transaction) tx = (SurfaceControl.Transaction) a;
                    else if ("android.window.TransitionInfo".equals(a.getClass().getName())) info = a;
                }
                if (info == null) return;
                SurfaceControl leash = pickLeash(info);
                if (leash == null && !hasAppTask(info)) return; // bukan transisi aplikasi
                startBars(leash, tx, 2500, "transition");
            }
        };

        XposedBridge.hookAllConstructors(rt, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                for (Object a : p.args) {
                    if (a != null && irt.isInstance(a)) hookChain(a.getClass(), "startAnimation", start);
                }
                warmLater();
            }
        });
    }

    // ------------------------------------------------------------------ jalur 2: recents (gesture)

    private static void hookRecents(ClassLoader cl) {
        Class<?> stub = XposedHelpers.findClassIfExists("android.view.IRecentsAnimationRunner$Stub", cl);
        if (stub == null) return;

        final XC_MethodHook start = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                SurfaceControl leash = null;
                for (Object a : p.args) {
                    if (a == null) continue;
                    String n = a.getClass().getName();
                    if (n.contains("IRecentsAnimationController")) {
                        hookExact(a.getClass(), "finish", FINISH_HOOK);
                    } else if (leash == null && isTargetArray(a)) {
                        leash = pickTargetLeash((Object[]) a);
                    }
                }
                startBars(leash, null, 10000, "recents");
            }
        };

        XposedBridge.hookAllConstructors(stub, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                hookChain(p.thisObject.getClass(), "onAnimationStart", start);
                warmLater();
            }
        });
    }

    // ------------------------------------------------------------------ jalur 3: remote animation lama

    private static void hookLegacy(ClassLoader cl) {
        Class<?> stub = XposedHelpers.findClassIfExists("android.view.IRemoteAnimationRunner$Stub", cl);
        if (stub == null) return;

        final XC_MethodHook start = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                SurfaceControl leash = null;
                boolean sawTargets = false;
                for (Object a : p.args) {
                    if (a == null) continue;
                    if (a.getClass().getName().contains("FinishedCallback")) {
                        hookExact(a.getClass(), "onAnimationFinished", FINISH_HOOK);
                    } else if (!sawTargets && isTargetArray(a)) {
                        sawTargets = true; // array pertama = apps
                        leash = pickTargetLeash((Object[]) a);
                    }
                }
                if (leash != null) startBars(leash, null, 2500, "legacy");
            }
        };

        XposedBridge.hookAllConstructors(stub, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                hookChain(p.thisObject.getClass(), "onAnimationStart", start);
                warmLater();
            }
        });
    }

    // ------------------------------------------------------------------ cari leash

    private static boolean isTargetArray(Object a) {
        Class<?> k = a.getClass();
        return k.isArray() && "android.view.RemoteAnimationTarget".equals(k.getComponentType().getName());
    }

    private static SurfaceControl pickTargetLeash(Object[] targets) {
        try {
            for (Object t : targets) {
                if (t == null) continue;
                Object wc = XposedHelpers.getObjectField(t, "windowConfiguration");
                int type = (Integer) XposedHelpers.callMethod(wc, "getActivityType");
                if (type == 2 || type == 3) continue; // HOME, RECENTS
                Object leash = XposedHelpers.getObjectField(t, "leash");
                if (leash instanceof SurfaceControl && ((SurfaceControl) leash).isValid()) {
                    return (SurfaceControl) leash;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return null;
    }

    /** Ada task aplikasi (bukan home/recents) di transisi ini? */
    static boolean hasAppTask(Object info) {
        try {
            List<?> changes = (List<?>) XposedHelpers.callMethod(info, "getChanges");
            for (Object ch : changes) {
                Object ti = XposedHelpers.callMethod(ch, "getTaskInfo");
                if (ti == null) continue;
                int type = (Integer) XposedHelpers.callMethod(ti, "getActivityType");
                if (type != 2 && type != 3) return true;
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return false;
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
                if (type == 2 || type == 3) continue;
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

    // ------------------------------------------------------------------ System UI

    private static void hookSystemUi(ClassLoader cl) {
        try {
            Class<?> tr = XposedHelpers.findClass("com.android.wm.shell.transition.Transitions", cl);
            XposedBridge.hookAllMethods(tr, "onTransitionReady", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    for (Object a : p.args) {
                        if (a != null && "android.window.TransitionInfo".equals(a.getClass().getName())
                                && hasAppTask(a)) {
                            StatusBarFader.fadeOut();
                            return;
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(tr, "onFinish", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    StatusBarFader.fadeIn();
                }
            });
            XposedBridge.log("BarsOverlay: fader status bar aktif di SystemUI");
        } catch (Throwable t) {
            XposedBridge.log(t);
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
