package com.jepongdevxyz.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * App-styled UI helpers: every toast and dialog in DevxyzBrowser goes through
 * here so nothing ever shows the default Android gray styling.
 */
public final class Ui {
    private Ui() {}

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static View currentToast;
    private static Runnable hideTask;

    /** Short app-styled toast (fox icon, dark navy pill, orange accent). */
    public static void toast(Activity a, CharSequence msg) {
        showToast(a, msg, 2000);
    }

    /** Long app-styled toast. */
    public static void toastLong(Activity a, CharSequence msg) {
        showToast(a, msg, 3500);
    }

    private static void showToast(Activity a, CharSequence msg, long ms) {
        if (a == null || msg == null) return;
        // Post to UI thread; never let a toast crash the app.
        H.post(() -> {
            try {
                if (a.isFinishing() || a.isDestroyed()) return;
                ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
                // Replace any toast still on screen.
                if (currentToast != null) {
                    try { decor.removeView(currentToast); } catch (Exception ignored) {}
                    currentToast = null;
                }
                if (hideTask != null) H.removeCallbacks(hideTask);

                View v = LayoutInflater.from(a).inflate(R.layout.app_toast, decor, false);
                ((TextView) v.findViewById(R.id.toast_text)).setText(msg);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                int side = dp(a, 24);
                lp.leftMargin = side;
                lp.rightMargin = side;
                lp.bottomMargin = dp(a, 96);
                decor.addView(v, lp);
                currentToast = v;

                v.setAlpha(0f);
                v.setTranslationY(dp(a, 16));
                v.animate().alpha(1f).translationY(0).setDuration(180).start();

                final View shown = v;
                hideTask = () -> {
                    try {
                        if (currentToast == shown) {
                            shown.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                                try { decor.removeView(shown); } catch (Exception ignored) {}
                                if (currentToast == shown) currentToast = null;
                            }).start();
                        }
                    } catch (Exception ignored) {}
                };
                H.postDelayed(hideTask, ms);
            } catch (Exception ignored) {}
        });
    }

    /** AlertDialog builder pre-themed to match DevxyzBrowser (dark navy, orange buttons). */
    public static AlertDialog.Builder dialog(Context ctx) {
        return new AlertDialog.Builder(ctx, R.style.Theme_DevxyzBrowser_Dialog);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
