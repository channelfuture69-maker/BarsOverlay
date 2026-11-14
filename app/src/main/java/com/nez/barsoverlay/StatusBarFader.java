package com.nez.barsoverlay;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Proses System UI: status bar dan nav bar bawaan di-fade keluar saat animasi app, lalu balik. */
public final class StatusBarFader {

    private static Handler sH;
    private static volatile boolean hidden;

    private static Handler h() {
        if (sH == null) sH = new Handler(Looper.getMainLooper());
        return sH;
    }

    /** Pengaman: kalau lupa dimunculkan lagi, balikkan setelah 15 detik. */
    private static final Runnable SAFETY = new Runnable() {
        @Override public void run() { fade(1f, 180); hidden = false; }
    };

    private StatusBarFader() {}

    public static boolean isHidden() { return hidden; }

    /** Main thread. */
    public static void hide() {
        h().removeCallbacks(SAFETY);
        hidden = true;
        fade(0f, 120);
        h().postDelayed(SAFETY, 15000);
    }

    /** Main thread. */
    public static void show(long ms) {
        h().removeCallbacks(SAFETY);
        hidden = false;
        fade(1f, ms);
    }

    private static void fade(float alpha, long ms) {
        try {
            View s = findWindow(SystemBars.TYPE_STATUS_BAR);
            if (s != null) s.animate().alpha(alpha).setDuration(ms).start();
            View n = findWindow(SystemBars.TYPE_NAVIGATION_BAR);
            if (n != null) n.animate().alpha(alpha).setDuration(ms).start();
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
