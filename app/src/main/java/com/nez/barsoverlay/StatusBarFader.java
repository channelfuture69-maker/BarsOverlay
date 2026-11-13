package com.nez.barsoverlay;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Dipakai di proses System UI: status bar bawaan di-fade keluar saat animasi app, lalu balik. */
public final class StatusBarFader {

    private static Handler sH;

    /** Kurva mulus (ease in-out) untuk fade masuk. */
    private static final Interpolator SMOOTH = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator LINEAR = new LinearInterpolator();

    private static Handler h() {
        if (sH == null) sH = new Handler(Looper.getMainLooper());
        return sH;
    }

    /** Pengaman: kalau lupa dimunculkan lagi, balikkan setelah 15 detik. */
    private static final Runnable SAFETY = new Runnable() {
        @Override public void run() { fade(1f, 180, SMOOTH); }
    };

    private StatusBarFader() {}

    /** Main thread. */
    public static void hide() {
        h().removeCallbacks(SAFETY);
        fade(0f, 120, LINEAR);
        h().postDelayed(SAFETY, 15000);
    }

    /** Main thread. */
    public static void show(long ms) {
        h().removeCallbacks(SAFETY);
        fade(1f, ms, SMOOTH);
    }

    private static void fade(float alpha, long ms, Interpolator ip) {
        try {
            View v = findWindow(WindowManager.LayoutParams.TYPE_STATUS_BAR);
            if (v != null) v.animate().cancel();
            if (v != null) v.animate().alpha(alpha).setDuration(ms).setInterpolator(ip).start();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** View akar jendela System UI dengan tipe tertentu (status bar / navigation bar). */
    @SuppressWarnings("unchecked")
    public static View findWindow(int type) {
        try {
            Object g = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.view.WindowManagerGlobal", null), "getInstance");
            Object lock = XposedHelpers.getObjectField(g, "mLock");
            synchronized (lock) {
                List<View> views = (List<View>) XposedHelpers.getObjectField(g, "mViews");
                List<WindowManager.LayoutParams> ps =
                        (List<WindowManager.LayoutParams>) XposedHelpers.getObjectField(g, "mParams");
                for (int i = 0; i < views.size(); i++) {
                    if (ps.get(i).type == type) return views.get(i);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return null;
    }
}
