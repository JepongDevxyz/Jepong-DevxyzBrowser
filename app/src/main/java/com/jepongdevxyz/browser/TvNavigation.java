package com.jepongdevxyz.browser;

import android.view.KeyEvent;
import android.view.MotionEvent;
import com.jepongdevxyz.browser.util.TvUtil;

/**
 * TV navigation helper: cursor speed, nav mode, D-pad/air-mouse handling.
 * Keeps MainActivity lean — all TV logic lives here.
 */
public final class TvNavigation {
  private TvNavigation() {}

  /** Apply cursor speed and nav mode from settings. */
  public static void applyMode(MainActivity a) {
    try {
      String mode = a.prefs.getString("tv_nav_mode", "dpad");
      float[] speeds = {12f, 24f, 40f};
      int si = a.prefs.getInt("tv_cursor_speed", 1);
      TvCursor.setSpeed(speeds[Math.max(0, Math.min(2, si))]);
      if ("cursor".equals(mode)) {
        TvCursor.enable(a);
      } else {
        TvCursor.disable();
      }
    } catch (Exception ignored) { }
  }

  /** Handle D-pad keys on TV. Returns true if consumed. */
  public static boolean onKey(MainActivity a, int keyCode, KeyEvent event) {
    try {
      if (TvUtil.isTv(a)) {
        return TvCursor.onKey(keyCode, event, a);
      }
    } catch (Exception ignored) { }
    return false;
  }

  /** Handle air-mouse motion on TV. Returns true if consumed. */
  public static boolean onMotion(MainActivity a, MotionEvent event) {
    try {
      if (TvUtil.isTv(a)) {
        return TvCursor.onMotion(event, a);
      }
    } catch (Exception ignored) { }
    return false;
  }

  /** Clean up TV cursor. */
  public static void onDestroy() {
    try { TvCursor.disable(); } catch (Exception ignored) { }
  }
}
