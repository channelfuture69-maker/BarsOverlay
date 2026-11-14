package com.nez.barsoverlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import de.robv.android.xposed.XposedBridge;

/**
 * Dipakai di System UI: bar yang tetap tampil setelah aplikasi terbuka sampai bar asli
 * muncul kembali. Dipasang transparan sejak animasi mulai, dinyalakan seketika saat
 * animasi selesai. Status bar dan nav bar masing-masing punya versi hitam dan putih,
 * jadi warnanya bisa diganti seketika. Semua method dipanggil dari main thread.
 */
public final class LingerOverlay {

    private static View view;
    private static View statDark, statLight, navDark, navLight;
    private static WindowManager wm;

    private LingerOverlay() {}

    private static View add(FrameLayout box, BarOverlay.Part p) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(p.w, p.h, Gravity.TOP | Gravity.START);
        lp.setMargins(p.x, p.y, 0, 0);
        box.addView(p.view, lp);
        return p.view;
    }

    public static void prepare(Context c, boolean darkStatus, boolean darkNav, int w, int h) {
        remove();
        try {
            wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            FrameLayout box = new FrameLayout(c);
            statDark = add(box, BarOverlay.build(c, BarOverlay.STATUS, true, w, h));
            statLight = add(box, BarOverlay.build(c, BarOverlay.STATUS, false, w, h));
            navDark = add(box, BarOverlay.build(c, BarOverlay.NAV, true, w, h));
            navLight = add(box, BarOverlay.build(c, BarOverlay.NAV, false, w, h));
            setTones(darkStatus, darkNav);
            box.setAlpha(0f);
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
            wm.addView(box, lp);
            view = box;
        } catch (Throwable t) {
            XposedBridge.log(t);
            view = null;
        }
    }

    /** Ganti warna seketika; null = biarkan. true = konten hitam. */
    public static void setTones(Boolean darkStatus, Boolean darkNav) {
        if (darkStatus != null) {
            if (statDark != null) statDark.setVisibility(darkStatus ? View.VISIBLE : View.INVISIBLE);
            if (statLight != null) statLight.setVisibility(darkStatus ? View.INVISIBLE : View.VISIBLE);
        }
        if (darkNav != null) {
            if (navDark != null) navDark.setVisibility(darkNav ? View.VISIBLE : View.INVISIBLE);
            if (navLight != null) navLight.setVisibility(darkNav ? View.INVISIBLE : View.VISIBLE);
        }
    }

    public static void showNow() {
        View v = view;
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(1f);
    }

    public static void remove() {
        View v = view;
        view = null;
        statDark = null;
        statLight = null;
        navDark = null;
        navLight = null;
        if (v == null) return;
        try {
            v.animate().cancel();
            wm.removeView(v);
        } catch (Throwable ignored) {
        }
    }
}
