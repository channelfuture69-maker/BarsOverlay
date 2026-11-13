package com.nez.barsoverlay;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Dipakai di proses System UI: status bar bawaan di-fade keluar saat animasi app, lalu balik. */
public final class StatusBarFader {

    private static Handler sH;

    private static Handler h() {
        if (sH == null) sH = new Handler(Looper.getMainLooper());
        return sH;
    }

    private static final Runnable IN = new Runnable() {
        @Override public void run() { fade(1f, 180); }
    };

    private StatusBarFader() {}

    public static void fadeOut() {
        h().post(new Runnable() {
            @Override public void run() {
                h().removeCallbacks(IN);
                fade(0f, 120);
                h().postDelayed(IN, 3000); // cadangan kalau sinyal selesai tidak tertangkap
            }
        });
    }

    public static void fadeIn() {
        h().post(new Runnable() {
            @Override public void run() {
                h().removeCallbacks(IN);
                fade(1f, 180);
            }
        });
    }

    private static void fade(float alpha, long ms) {
        try {
            View v = findBar();
            if (v != null) v.animate().alpha(alpha).setDuration(ms).start();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static View findBar() {
        Object g = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.view.WindowManagerGlobal", null), "getInstance");
        Object lock = XposedHelpers.getObjectField(g, "mLock");
        synchronized (lock) {
            List<View> views = (List<View>) XposedHelpers.getObjectField(g, "mViews");
            List<WindowManager.LayoutParams> ps =
                    (List<WindowManager.LayoutParams>) XposedHelpers.getObjectField(g, "mParams");
            for (int i = 0; i < views.size(); i++) {
                if (ps.get(i).type == WindowManager.LayoutParams.TYPE_STATUS_BAR) return views.get(i);
            }
        }
        return null;
    }
}
