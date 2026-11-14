package com.nez.barsoverlay;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.SurfaceControl;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Membaca warna asli isi jendela aplikasi (leash) di strip atas/bawah lewat captureLayers,
 * supaya warna bar ditentukan dari latar yang benar-benar ada di belakangnya.
 * Dipanggil di System UI (punya izin READ_FRAME_BUFFER).
 */
public final class AppPixels {

    private static boolean logged;

    private AppPixels() {}

    /** Rata-rata terang (0..255) area crop pada leash; null kalau gagal. */
    public static Integer avgLum(SurfaceControl leash, Rect crop, boolean skipCenter, SurfaceControl[] exclude) {
        try {
            Class<?> host = XposedHelpers.findClassIfExists("android.window.ScreenCapture", null);
            Class<?> builder;
            if (host != null) {
                builder = XposedHelpers.findClass("android.window.ScreenCapture$LayerCaptureArgs$Builder", null);
            } else {
                host = SurfaceControl.class;
                builder = XposedHelpers.findClass("android.view.SurfaceControl$LayerCaptureArgs$Builder", null);
            }
            Object b = XposedHelpers.newInstance(builder, leash);
            XposedHelpers.callMethod(b, "setSourceCrop", crop);
            XposedHelpers.callMethod(b, "setFrameScale", 0.25f);
            if (exclude != null && exclude.length > 0) {
                try {
                    XposedHelpers.callMethod(b, "setExcludeLayers", (Object) exclude);
                } catch (Throwable ignored) {
                }
            }
            Object args = XposedHelpers.callMethod(b, "build");
            Object buf = XposedHelpers.callStaticMethod(host, "captureLayers", args);
            if (buf == null) return null;
            Bitmap hw = (Bitmap) XposedHelpers.callMethod(buf, "asBitmap");
            if (hw == null) return null;
            Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
            if (bmp == null) return null;

            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            bmp.recycle();
            double sum = 0;
            long n = 0;
            int from = (int) (w * 0.30f), to = (int) (w * 0.70f);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (skipCenter && x >= from && x < to) continue; // area lubang kamera
                    int p = px[y * w + x];
                    if ((p >>> 24) < 128) continue;
                    sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p);
                    n++;
                }
            }
            if (n < 20) return null;
            return (int) Math.round(sum / n);
        } catch (Throwable t) {
            if (!logged) {
                logged = true;
                XposedBridge.log("BarsOverlay: captureLayers gagal, pakai tebakan warna");
                XposedBridge.log(t);
            }
            return null;
        }
    }
}
