package com.jepongdevxyz.browser;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;
import com.jepongdevxyz.browser.util.TvUtil;

/**
 * TV navigation helper: cursor speed, nav mode, D-pad/air-mouse handling.
 * Keeps MainActivity lean — all TV logic lives here.
 *
 * TV input is wired via a Window.Callback wrapper (no MainActivity overrides needed).
 */
public final class TvNavigation {
  private TvNavigation() {}

  /** Apply cursor speed and nav mode from settings. Wires TV input via window callback. */
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
      // Wire D-pad/air-mouse via window callback — no MainActivity override needed.
      if (TvUtil.isTv(a)) {
        wrapWindowCallback(a);
      }
    } catch (Exception ignored) { }
  }

  /** Wrap the window callback to intercept TV remote input. */
  private static void wrapWindowCallback(MainActivity a) {
    try {
      Window window = a.getWindow();
      Window.Callback current = window.getCallback();
      if (current == null || current instanceof TvWindowCallback) return;
      window.setCallback(new TvWindowCallback(a, current));
    } catch (Exception ignored) { }
  }

  /** Window.Callback wrapper that routes D-pad/air-mouse to TvCursor. */
  private static final class TvWindowCallback extends Window.CallbackWrapper {
    private final MainActivity activity;
    TvWindowCallback(MainActivity a, Window.Callback wrapped) {
      super(wrapped);
      activity = a;
    }
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
      try {
        if (TvCursor.onKey(event.getKeyCode(), event, activity)) return true;
      } catch (Exception ignored) { }
      return super.dispatchKeyEvent(event);
    }
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
      try {
        if (TvCursor.onMotion(event, activity)) return true;
      } catch (Exception ignored) { }
      return super.dispatchGenericMotionEvent(event);
    }
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
