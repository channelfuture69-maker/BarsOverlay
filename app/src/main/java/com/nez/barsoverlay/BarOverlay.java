package com.nez.barsoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextClock;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;

/** Jam + persen baterai di atas, pill di bawah. Hanya dipanggil dari main thread. */
public final class BarOverlay {

    private static View root;
    private static WindowManager wm;

    private BarOverlay() {}

    private static int dp(float d, float v) { return Math.round(v * d); }

    private static void style(TextView t) {
        t.setTextColor(Color.WHITE);
        t.setTextSize(15f);
        t.setShadowLayer(3f, 0f, 0f, 0x99000000);
    }

    private static String battery(Context c) {
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return (l * 100 / s) + "%";
        } catch (Throwable t) {
            return "";
        }
    }

    public static void show(Context c) {
        if (root != null || c == null) return;
        try {
            wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            float d = c.getResources().getDisplayMetrics().density;
            FrameLayout f = new FrameLayout(c);

            TextClock clock = new TextClock(c);
            clock.setFormat24Hour("H:mm");
            clock.setFormat12Hour("h:mm");
            style(clock);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            cp.setMargins(dp(d, 28), dp(d, 10), 0, 0);
            f.addView(clock, cp);

            TextView bat = new TextView(c);
            bat.setText(battery(c));
            style(bat);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            bp.setMargins(0, dp(d, 10), dp(d, 28), 0);
            f.addView(bat, bp);

            View pill = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.WHITE);
            g.setStroke(1, 0x33000000);
            g.setCornerRadius(dp(d, 5));
            pill.setBackground(g);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                    dp(d, 134), dp(d, 5), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            pp.setMargins(0, 0, 0, dp(d, 8));
            f.addView(pill, pp);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    -1, -1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            wm.addView(f, lp);
            root = f;
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    public static void hide() {
        if (root == null) return;
        try {
            wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
    }
}
