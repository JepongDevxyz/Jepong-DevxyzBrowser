package com.jepongdevxyz.browser;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * Virtual mouse cursor for Android TV. When enabled, D-pad arrows move the
 * cursor and OK/DPAD_CENTER clicks at the cursor position by synthesizing
 * touch events into the active view.
 */
public final class TvCursor {
  private TvCursor() {}

  private static ImageView cursorView;
  private static WindowManager wm;
  private static WindowManager.LayoutParams params;
  private static float x, y;
  private static float speed = 24f;
  private static boolean enabled;
  private static View targetView;
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static Runnable moveTask;
  private static int moveDx, moveDy;

  /** Navigation modes for TV. */
  public static final int MODE_DPAD = 0;
  public static final int MODE_CURSOR = 1;

  public static boolean isEnabled() { return enabled; }

  public static void setSpeed(float s) { speed = s; }

  /** Show the cursor overlay. Call from Activity (needs window token). */
  public static void enable(Context ctx, View target) {
    if (enabled) { targetView = target; return; }
    try {
      targetView = target;
      wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
      cursorView = new ImageView(ctx);
      cursorView.setImageResource(android.R.drawable.ic_menu_add);
      cursorView.setColorFilter(0xFF9D7BFF);
      android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
      x = dm.widthPixels / 2f;
      y = dm.heightPixels / 2f;
      params = new WindowManager.LayoutParams(
          72, 72,
          WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
              | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
              | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
          PixelFormat.TRANSLUCENT);
      params.gravity = Gravity.TOP | Gravity.START;
      params.x = (int) x;
      params.y = (int) y;
      wm.addView(cursorView, params);
      enabled = true;
    } catch (Exception ignored) { }
  }

  public static void disable() {
    enabled = false;
    stopMoving();
    try {
      if (wm != null && cursorView != null) wm.removeView(cursorView);
    } catch (Exception ignored) { }
    cursorView = null;
    wm = null;
    targetView = null;
  }

  /**
   * Handle motion events from air-mouse / motion remotes.
   * When the remote sends pointer movement, the cursor follows it.
   * Returns true if the event was a pointer movement we handled.
   */
  public static boolean onMotion(MotionEvent event, Context ctx) {
    if (!enabled || cursorView == null) return false;
    int action = event.getAction();
    // Air-mouse remotes send HOVER_MOVE or mouse SOURCE events
    if (action == MotionEvent.ACTION_HOVER_MOVE
        || action == MotionEvent.ACTION_MOVE) {
      int src = event.getSource();
      boolean isPointer = (src & android.view.InputDevice.SOURCE_MOUSE) != 0
          || (src & android.view.InputDevice.SOURCE_TOUCHPAD) != 0
          || action == MotionEvent.ACTION_HOVER_MOVE;
      if (isPointer) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        x = Math.max(0, Math.min(dm.widthPixels - 72, event.getRawX() - 36));
        y = Math.max(0, Math.min(dm.heightPixels - 72, event.getRawY() - 36));
        try {
          params.x = (int) x;
          params.y = (int) y;
          wm.updateViewLayout(cursorView, params);
        } catch (Exception ignored) { }
        // Stop D-pad auto-move; motion takes over
        stopMoving();
        return true;
      }
    }
    return false;
  }

  /** Handle D-pad key events. Returns true if consumed. */
  public static boolean onKey(int keyCode, KeyEvent event, Context ctx) {
    if (!enabled) return false;
    int action = event.getAction();
    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
      if (action == KeyEvent.ACTION_DOWN) clickAt(x, y);
      return true;
    }
    int dx = 0, dy = 0;
    switch (keyCode) {
      case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
      case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
      case KeyEvent.KEYCODE_DPAD_UP: dy = -1; break;
      case KeyEvent.KEYCODE_DPAD_DOWN: dy = 1; break;
      default: return false;
    }
    if (action == KeyEvent.ACTION_DOWN) {
      if (event.getRepeatCount() == 0) { moveDx = dx; moveDy = dy; startMoving(ctx); }
    } else if (action == KeyEvent.ACTION_UP) {
      stopMoving();
    }
    return true;
  }

  private static void startMoving(final Context ctx) {
    stopMoving();
    moveTask = new Runnable() {
      @Override public void run() {
        if (!enabled || cursorView == null) return;
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        x = Math.max(0, Math.min(dm.widthPixels - 72, x + moveDx * speed));
        y = Math.max(0, Math.min(dm.heightPixels - 72, y + moveDy * speed));
        try {
          params.x = (int) x;
          params.y = (int) y;
          wm.updateViewLayout(cursorView, params);
        } catch (Exception ignored) { }
        handler.postDelayed(this, 16);
      }
    };
    handler.post(moveTask);
  }

  private static void stopMoving() {
    if (moveTask != null) handler.removeCallbacks(moveTask);
    moveTask = null;
    moveDx = 0;
    moveDy = 0;
  }

  /** Synthesize a tap at the cursor position on the target view. */
  private static void clickAt(float cx, float cy) {
    if (targetView == null) return;
    try {
      long now = SystemClock.uptimeMillis();
      int[] loc = new int[2];
      targetView.getLocationOnScreen(loc);
      float vx = cx - loc[0] + 36;
      float vy = cy - loc[1] + 36;
      MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, vx, vy, 0);
      MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, vx, vy, 0);
      targetView.dispatchTouchEvent(down);
      targetView.dispatchTouchEvent(up);
      down.recycle();
      up.recycle();
    } catch (Exception ignored) { }
  }
}
