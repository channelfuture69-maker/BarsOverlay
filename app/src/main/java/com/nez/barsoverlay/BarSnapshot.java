package com.nez.barsoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayList;

import de.robv.android.xposed.XposedBridge;

/**
 * Foto status bar dan pill navigasi ASLI milik System UI (digambar ke bitmap transparan,
 * tanpa background), lalu ditempel sebagai gambar. Hasilnya sama persis dengan sistem.
 * Isi yang netral (putih/hitam) bisa dibalik supaya cocok dengan latar aplikasi;
 * ikon berwarna (baterai kuning, rekam layar merah, dll) dibiarkan.
 * capture() harus dipanggil dari main thread saat bar asli sedang tampil penuh.
 */
public final class BarSnapshot {

    private static volatile Bitmap top, bottom;
    private static volatile boolean topLight, bottomLight; // isi aslinya terang (putih)?

    private BarSnapshot() {}

    public static boolean ready() { return top != null; }

    public static boolean hasBottom() { return bottom != null; }

    /** Main thread. true kalau status bar berhasil difoto. */
    public static boolean capture(View statusBar, View navBar) {
        boolean ok = false;
        try {
            Bitmap t = snap(statusBar);
            if (t != null && hasContent(t)) {
                topLight = isLight(t);
                top = t;
                ok = true;
            }
            Bitmap b = snap(navBar);
            if (b != null && hasContent(b)) {
                bottomLight = isLight(b);
                bottom = b;
            }
            if (ok) {
                XposedBridge.log("BarsOverlay: snapshot top=" + top.getWidth() + "x" + top.getHeight()
                        + " bottom=" + (bottom != null ? bottom.getWidth() + "x" + bottom.getHeight() : "tidak ada"));
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return ok;
    }

    /**
     * View penuh layar: foto status bar di atas, foto pill di bawah.
     * nativeLook=true: pakai warna asli sistem. Kalau tidak: wantDark=true -> isi hitam, false -> putih.
     */
    public static FrameLayout view(Context c, boolean wantDark, boolean nativeLook) {
        FrameLayout f = new FrameLayout(c);
        Bitmap t = top, b = bottom;
        if (t != null) add(f, nativeLook ? t : variant(t, topLight, wantDark), Gravity.TOP | Gravity.START);
        if (b != null) add(f, nativeLook ? b : variant(b, bottomLight, wantDark), Gravity.BOTTOM | Gravity.START);
        return f;
    }

    private static void add(FrameLayout f, Bitmap bm, int gravity) {
        ImageView iv = new ImageView(f.getContext());
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setImageBitmap(bm);
        f.addView(iv, new FrameLayout.LayoutParams(bm.getWidth(), bm.getHeight(), gravity));
    }

    // ------------------------------------------------------------------ ambil gambar

    private static Bitmap snap(View root) {
        if (root == null) return null;
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0 || h > w / 2 || !root.isShown()) return null;
        // hanya saat tampil penuh (tidak sedang di-fade atau digeser keluar layar)
        if (root.getAlpha() < 0.99f || Math.abs(root.getTranslationY()) > 0.5f) return null;

        Bitmap b = draw(root, w, h, false);
        // background tidak transparan (pojok kiri atas pekat)? gambar ulang tanpa background
        if (b != null && (b.getPixel(0, 0) >>> 24) > 40) b = draw(root, w, h, true);
        return b;
    }

    private static Bitmap draw(View root, int w, int h, boolean stripBg) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        ArrayList<Object[]> saved = new ArrayList<Object[]>();
        if (stripBg) strip(root, w, h, saved);
        try {
            root.draw(new Canvas(b));
        } finally {
            for (Object[] s : saved) ((View) s[0]).setBackground((Drawable) s[1]);
        }
        return b;
    }

    /** Lepas background wadah selebar bar saja (bukan background ikon seperti chip baterai). */
    private static void strip(View v, int rw, int rh, ArrayList<Object[]> out) {
        if (!(v instanceof ViewGroup)) return;
        if (v.getWidth() >= rw * 0.9f && v.getHeight() >= rh * 0.9f) {
            Drawable bg = v.getBackground();
            if (bg != null) {
                out.add(new Object[]{v, bg});
                v.setBackground(null);
            }
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) strip(g.getChildAt(i), rw, rh, out);
    }

    // ------------------------------------------------------------------ analisa warna

    private static boolean hasContent(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int n = 0;
        for (int p : px) if ((p >>> 24) > 32 && ++n >= 20) return true;
        return false;
    }

    private static boolean isNeutral(int r, int g, int b) {
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 48;
    }

    private static boolean isLight(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        long sum = 0;
        int n = 0;
        for (int p : px) {
            if ((p >>> 24) < 128) continue;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
            if (!isNeutral(r, g, bl)) continue;
            sum += (r * 299 + g * 587 + bl * 114) / 1000;
            n++;
        }
        return n == 0 || sum / n > 128;
    }

    /** Balik warna piksel netral (putih <-> hitam). Piksel berwarna tetap. */
    private static Bitmap variant(Bitmap src, boolean srcLight, boolean wantDark) {
        if (wantDark != srcLight) return src; // sudah sesuai
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = p >>> 24;
            if (a == 0) continue;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (isNeutral(r, g, b)) px[i] = (a << 24) | ((255 - r) << 16) | ((255 - g) << 8) | (255 - b);
        }
        Bitmap o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        o.setPixels(px, 0, w, 0, 0, w, h);
        return o;
    }
}
