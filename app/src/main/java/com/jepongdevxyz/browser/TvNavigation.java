package com.jepongdevxyz.browser;

import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
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
      String mode = a.prefs.getString("tv_nav_mode", "cursor");
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
        // Bold focus highlight so it's clear what's selected + make menus focusable.
        TvFocusHighlight.enable(a);
        // Don't auto-show keyboard on TV home — user navigates with D-pad first.
        hideKeyboardOnTv(a);
      } else {
        TvFocusHighlight.disable();
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

  /** Window.Callback that routes D-pad/air-mouse to TvCursor, delegates the rest. */
  private static final class TvWindowCallback implements Window.Callback {
    private final MainActivity activity;
    private final Window.Callback wrapped;
    TvWindowCallback(MainActivity a, Window.Callback w) {
      activity = a;
      wrapped = w;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
      try {
        int kc = event.getKeyCode();
        // BACK button: explicit handling for TV remote (browser history navigation).
        if (kc == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN
            && event.getRepeatCount() == 0) {
          return onTvBack(activity);
        }
        if (TvCursor.onKey(kc, event, activity)) return true;
      } catch (Exception ignored) { }
      return wrapped.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
      try {
        if (TvCursor.onMotion(event, activity)) return true;
      } catch (Exception ignored) { }
      return wrapped.dispatchGenericMotionEvent(event);
    }
    // --- pure delegation below ---
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent e) { return wrapped.dispatchKeyShortcutEvent(e); }
    @Override public boolean dispatchTouchEvent(MotionEvent e) { return wrapped.dispatchTouchEvent(e); }
    @Override public boolean dispatchTrackballEvent(MotionEvent e) { return wrapped.dispatchTrackballEvent(e); }
    @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent e) { return wrapped.dispatchPopulateAccessibilityEvent(e); }
    @Override public View onCreatePanelView(int id) { return wrapped.onCreatePanelView(id); }
    @Override public boolean onCreatePanelMenu(int id, Menu m) { return wrapped.onCreatePanelMenu(id, m); }
    @Override public boolean onPreparePanel(int id, View v, Menu m) { return wrapped.onPreparePanel(id, v, m); }
    @Override public boolean onMenuOpened(int id, Menu m) { return wrapped.onMenuOpened(id, m); }
    @Override public boolean onMenuItemSelected(int id, MenuItem i) { return wrapped.onMenuItemSelected(id, i); }
    @Override public void onWindowAttributesChanged(WindowManager.LayoutParams p) { wrapped.onWindowAttributesChanged(p); }
    @Override public void onContentChanged() { wrapped.onContentChanged(); }
    @Override public void onWindowFocusChanged(boolean f) { wrapped.onWindowFocusChanged(f); }
    @Override public void onAttachedToWindow() { wrapped.onAttachedToWindow(); }
    @Override public void onDetachedFromWindow() { wrapped.onDetachedFromWindow(); }
    @Override public void onPanelClosed(int id, Menu m) { wrapped.onPanelClosed(id, m); }
    @Override public boolean onSearchRequested() { return wrapped.onSearchRequested(); }
    @Override public boolean onSearchRequested(SearchEvent e) { return wrapped.onSearchRequested(e); }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c) { return wrapped.onWindowStartingActionMode(c); }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c, int t) { return wrapped.onWindowStartingActionMode(c, t); }
    @Override public void onActionModeStarted(ActionMode m) { wrapped.onActionModeStarted(m); }
    @Override public void onActionModeFinished(ActionMode m) { wrapped.onActionModeFinished(m); }
  }

  /**
   * Handle TV remote BACK: dismiss popups first, else browser back navigation.
   * Returns true if consumed.
   */
  public static boolean onTvBack(MainActivity a) {
    try {
      // Let the activity's back dispatcher handle it (goBack/showHome/finish logic).
      a.getOnBackPressedDispatcher().onBackPressed();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** Hide soft keyboard on TV — D-pad navigation doesn't need it popping up. */
  private static void hideKeyboardOnTv(MainActivity a) {
    try {
      android.view.inputmethod.InputMethodManager imm =
        (android.view.inputmethod.InputMethodManager) a.getSystemService(
          android.content.Context.INPUT_METHOD_SERVICE);
      android.view.View focus = a.getCurrentFocus();
      if (focus != null && imm != null) {
        imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
      }
      // Prevent EditTexts from auto-grabbing focus on TV (which pops the keyboard).
      android.view.View decor = a.getWindow().getDecorView();
      decor.setFocusableInTouchMode(true);
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
    try { TvFocusHighlight.disable(); } catch (Exception ignored) { }
  }
}
