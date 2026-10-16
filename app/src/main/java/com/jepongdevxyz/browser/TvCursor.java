package com.jepongdevxyz.browser;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Virtual mouse cursor for Android TV.
 *
 * Robust design: the cursor is a View added directly to the Activity's
 * decor view (no WindowManager overlay, no special permissions needed).
 *
 * - D-pad arrows move the cursor (fallback, always works)
 * - Air-mouse / motion remotes: cursor follows the pointer
 * - OK / DPAD_CENTER: synthesizes a tap at the cursor position
 */
public final class TvCursor {
  private TvCursor() {}

  /** Navigation modes for TV. */
  public static final int MODE_DPAD = 0;
  public static final int MODE_CURSOR = 1;

  private static CursorView cursorView;
  private static float x, y;
  private static float speed = 24f;
  private static boolean enabled;
  private static Activity activity;
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static Runnable moveTask;
  private static int moveDx, moveDy;

  public static boolean isEnabled() { return enabled; }
  public static void setSpeed(float s) { speed = s; }

  /** Custom view that draws a proper mouse pointer arrow. */
  private static final class CursorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    CursorView(Context ctx) {
      super(ctx);
      paint.setColor(Color.WHITE);
      paint.setStyle(Paint.Style.FILL);
      outline.setColor(0xFF9D7BFF);
      outline.setStyle(Paint.Style.STROKE);
      outline.setStrokeWidth(4f);
    }
    @Override protected void onDraw(Canvas c) {
      // Classic arrow pointer pointing up-left
      Path p = new Path();
      p.moveTo(4, 4);
      p.lineTo(4, 52);
      p.lineTo(18, 40);
      p.lineTo(26, 58);
      p.lineTo(34, 54);
      p.lineTo(26, 36);
      p.lineTo(40, 36);
      p.close();
      c.drawPath(p, paint);
      c.drawPath(p, outline);
    }
  }

  /** Show the cursor. Safe to call multiple times. */
  public static void enable(Activity act) {
    if (enabled && act == activity) return;
    disable();
    try {
      activity = act;
      android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
      x = dm.widthPixels / 2f;
      y = dm.heightPixels / 2f;
      cursorView = new CursorView(act);
      FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(64, 72);
      lp.leftMargin = (int) x;
      lp.topMargin = (int) y;
      // Add to decor view so it floats above everything, no permissions needed
      ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
      decor.addView(cursorView, lp);
      cursorView.bringToFront();
      enabled = true;
    } catch (Exception ignored) {
      enabled = false;
      cursorView = null;
      activity = null;
    }
  }

  public static void disable() {
    enabled = false;
    stopMoving();
    try {
      if (activity != null && cursorView != null) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        decor.removeView(cursorView);
      }
    } catch (Exception ignored) { }
    cursorView = null;
    activity = null;
  }

  private static void moveCursorTo(float nx, float ny) {
    if (!enabled || cursorView == null || activity == null) return;
    try {
      android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
      x = Math.max(0, Math.min(dm.widthPixels - 64, nx));
      y = Math.max(0, Math.min(dm.heightPixels - 72, ny));
      FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cursorView.getLayoutParams();
      lp.leftMargin = (int) x;
      lp.topMargin = (int) y;
      cursorView.setLayoutParams(lp);
    } catch (Exception ignored) { }
  }

  /**
   * Handle motion events from air-mouse / motion remotes.
   * When the remote sends pointer movement, the cursor follows it.
   */
  public static boolean onMotion(MotionEvent event, Context ctx) {
    if (!enabled) return false;
    int action = event.getAction();
    if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_MOVE) {
      int src = event.getSource();
      boolean isPointer = (src & android.view.InputDevice.SOURCE_MOUSE) != 0
          || (src & android.view.InputDevice.SOURCE_TOUCHPAD) != 0
          || action == MotionEvent.ACTION_HOVER_MOVE;
      if (isPointer) {
        stopMoving(); // motion takes over from D-pad auto-move
        moveCursorTo(event.getRawX(), event.getRawY());
        return true;
      }
    }
    return false;
  }

  /** Handle D-pad key events. Returns true if consumed. */
  public static boolean onKey(int keyCode, KeyEvent event, Context ctx) {
    if (!enabled) return false;
    int action = event.getAction();
    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
      if (action == KeyEvent.ACTION_DOWN) clickAt(x + 8, y + 8);
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
      // Free cursor movement like a mouse — always move the virtual cursor.
      // (Native focus navigation caused the cursor to snap/jump instead of moving freely.)
      if (event.getRepeatCount() == 0) { moveDx = dx; moveDy = dy; startMoving(); }
    } else if (action == KeyEvent.ACTION_UP) {
      stopMoving();
    }
    return true;
  }

  /** Try Android's native focus search — moves focus between focusable views. */
  private static boolean tryFocusMove(int dx, int dy) {
    try {
      if (activity == null) return false;
      View current = activity.getCurrentFocus();
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
      View root = current != null ? current : decor;
      int direction = dy < 0 ? View.FOCUS_UP : dy > 0 ? View.FOCUS_DOWN
          : dx < 0 ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
      View next = root.focusSearch(direction);
      if (next != null && next != root) {
        next.requestFocus();
        return true;
      }
      // If no focusable found, try from decor
      if (current == null) {
        View f = decor.focusSearch(direction);
        if (f != null && f != decor) { f.requestFocus(); return true; }
      }
    } catch (Exception ignored) { }
    return false;
  }

  /** Move cursor to the currently focused view's center. */
  private static void syncCursorToFocus() {
    try {
      if (activity == null) return;
      View focused = activity.getCurrentFocus();
      if (focused == null || focused == cursorView) return;
      int[] loc = new int[2];
      focused.getLocationOnScreen(loc);
      float cx = loc[0] + focused.getWidth() / 2f;
      float cy = loc[1] + focused.getHeight() / 2f;
      moveCursorTo(cx - 8, cy - 8);
    } catch (Exception ignored) { }
  }

  private static void startMoving() {
    stopMoving();
    moveTask = new Runnable() {
      @Override public void run() {
        if (!enabled) return;
        moveCursorTo(x + moveDx * speed, y + moveDy * speed);
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

  /**
   * Synthesize a tap at the given screen position.
   * Finds the deepest view at that point and dispatches touch events to it.
   * Works across the whole app: home, menus, settings, dialogs, panels, WebView.
   */
  private static void clickAt(float sx, float sy) {
    if (activity == null) return;
    try {
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
      View target = findDeepestAt(decor, sx, sy);
      if (target == null) target = decor;
      // Walk up to find a clickable/long-clickable ancestor if the deepest isn't clickable.
      // This fixes taps on icons inside non-clickable containers (extensions, VPN rows, etc.)
      View clickable = target;
      while (clickable != null && clickable != decor) {
        if (clickable.isClickable() || clickable.isLongClickable() || clickable.isFocusable()) break;
        Object parent = clickable.getParent();
        clickable = (parent instanceof View) ? (View) parent : null;
      }
      if (clickable != null) target = clickable;
      // Request focus first for proper TV behavior
      try { if (target.isFocusable()) target.requestFocus(); } catch (Exception ignored) {}
      // Convert screen coords to target-local coords
      int[] loc = new int[2];
      target.getLocationOnScreen(loc);
      float lx = sx - loc[0];
      float ly = sy - loc[1];
      long now = SystemClock.uptimeMillis();
      MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, lx, ly, 0);
      MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, lx, ly, 0);
      target.dispatchTouchEvent(down);
      target.dispatchTouchEvent(up);
      // Also send a click for views that listen for OnClickListener
      target.performClick();
      down.recycle();
      up.recycle();
    } catch (Exception ignored) { }
  }

  /** Find the deepest visible view containing the screen point. */
  private static View findDeepestAt(ViewGroup parent, float sx, float sy) {
    try {
      for (int i = parent.getChildCount() - 1; i >= 0; i--) {
        View child = parent.getChildAt(i);
        if (child == null || child.getVisibility() != View.VISIBLE || child == cursorView) continue;
        int[] loc = new int[2];
        child.getLocationOnScreen(loc);
        float l = loc[0], t = loc[1], r = l + child.getWidth(), b = t + child.getHeight();
        if (sx >= l && sx <= r && sy >= t && sy <= b) {
          if (child instanceof ViewGroup) {
            View deeper = findDeepestAt((ViewGroup) child, sx, sy);
            if (deeper != null) return deeper;
          }
          return child;
        }
      }
    } catch (Exception ignored) { }
    return null;
  }
}
