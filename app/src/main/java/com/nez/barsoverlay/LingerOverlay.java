package com.nez.barsoverlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.View;
import android.view.WindowManager;

import de.robv.android.xposed.XposedBridge;

/**
 * Dipakai di System UI: bar yang tetap tampil setelah aplikasi terbuka, lalu fade out.
 * Dipasang transparan sejak animasi mulai, dinyalakan seketika saat animasi selesai.
 * Semua method dipanggil dari main thread.
 */
public final class LingerOverlay {

    private static View view;
    private static WindowManager wm;

    private LingerOverlay() {}

    public static void prepare(Context c, boolean darkContent) {
        remove();
        try {
            wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            View v = BarOverlay.build(c, darkContent);
            v.setAlpha(0f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    -1, -1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("BarsLinger");
            wm.addView(v, lp);
            view = v;
        } catch (Throwable t) {
            XposedBridge.log(t);
            view = null;
        }
    }

    public static void showNow() {
        View v = view;
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(1f);
    }

    public static void fadeOutAndRemove(long ms) {
        final View v = view;
        if (v == null) return;
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(ms).withEndAction(new Runnable() {
            @Override public void run() {
                if (view == v) remove();
            }
        }).start();
    }

    public static void remove() {
        View v = view;
        view = null;
        if (v == null) return;
        try {
            v.animate().cancel();
            wm.removeView(v);
        } catch (Throwable ignored) {
        }
    }
}
