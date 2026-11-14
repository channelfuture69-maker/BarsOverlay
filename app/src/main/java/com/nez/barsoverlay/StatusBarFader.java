package com.nez.barsoverlay;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Proses System UI: status bar dan nav bar bawaan disembunyikan seketika saat animasi app
 * (tanpa fade, supaya tidak dobel/tebal) dan dimunculkan seketika lagi.
 */
public final class StatusBarFader {

    private static Handler sH;
    private static volatile boolean hidden;

    private static Handler h() {
        if (sH == null) sH = new Handler(Looper.getMainLooper());
        return sH;
    }

    /** Pengaman: kalau lupa dimunculkan lagi, balikkan setelah 15 detik. */
    private static final Runnable SAFETY = new Runnable() {
        @Override public void run() { set(SystemBars.TYPE_STATUS_BAR, 1f); set(SystemBars.TYPE_NAVIGATION_BAR, 1f); hidden = false; }
    };

    private StatusBarFader() {}

    public static boolean isHidden() { return hidden; }

    /** Main thread. */
    public static void hide() {
        h().removeCallbacks(SAFETY);
        hidden = true;
        set(SystemBars.TYPE_STATUS_BAR, 0f);
        set(SystemBars.TYPE_NAVIGATION_BAR, 0f);
        h().postDelayed(SAFETY, 15000);
    }

    /** Munculkan keduanya seketika. Main thread. */
    public static void showAll() {
        h().removeCallbacks(SAFETY);
        hidden = false;
        set(SystemBars.TYPE_STATUS_BAR, 1f);
        set(SystemBars.TYPE_NAVIGATION_BAR, 1f);
    }

    /** Munculkan salah satu seketika. Main thread. */
    public static void showType(int type) {
        set(type, 1f);
    }

    private static void set(int type, float alpha) {
        try {
            View v = findWindow(type);
            if (v != null) {
                v.animate().cancel();
                v.setAlpha(alpha);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

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
                    if (ps.get(i).type == type && views.get(i).getWidth() > 0) return views.get(i);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return null;
    }
}
