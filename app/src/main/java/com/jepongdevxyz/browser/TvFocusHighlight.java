package com.jepongdevxyz.browser;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;

/**
 * Bold focus highlight for TV: draws an animated orange border around
 * the currently focused view so it's always clear what's selected.
 */
public final class TvFocusHighlight {
  private TvFocusHighlight() {}

  private static HighlightView highlightView;
  private static ViewTreeObserver.OnGlobalFocusChangeListener focusListener;
  private static ViewTreeObserver.OnGlobalLayoutListener layoutListener;
  private static Activity boundActivity;
  private static final java.util.Set<View> wiredViews = java.util.Collections.newSetFromMap(
    new java.util.WeakHashMap<View, Boolean>());

  /** Enable the bold focus highlight for TV. Call from TvNavigation.applyMode(). */
  public static void enable(Activity activity) {
    try {
      if (boundActivity == activity && highlightView != null) return;
      disable();
      boundActivity = activity;
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

      highlightView = new HighlightView(activity);
      highlightView.setVisibility(View.GONE);
      decor.addView(highlightView, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

      // Track focus changes across the whole window.
      focusListener = (oldFocus, newFocus) -> {
        if (newFocus != null && newFocus != highlightView) {
          highlightView.moveTo(newFocus);
        } else {
          highlightView.hide();
        }
      };
      decor.getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);

      // Make clickable views focusable for D-pad (catches popups/dialogs).
      // Throttle: only run on layout, not on every frame.
      layoutListener = () -> {
        makeClickableFocusable(decor);
        // fixAllWindows uses reflection - only run occasionally, not on every layout
      };
      decor.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
      makeClickableFocusable(decor);
      // Run fixAllWindows once after a delay (for popups), not on every layout
      decor.postDelayed(() -> fixAllWindows(), 500);
      // Ensure there's an initial focus for D-pad navigation (Netflix-style:
      // focus starts on the first focusable view so D-pad works immediately)
      decor.postDelayed(() -> {
        try {
          if (boundActivity != null && boundActivity.getCurrentFocus() == null) {
            View first = decor.focusSearch(View.FOCUS_DOWN);
            if (first != null) first.requestFocus();
          }
        } catch (Exception ignored) { }
      }, 800);
    } catch (Exception ignored) { }
  }

  /** Disable and clean up. */
  public static void disable() {
    try {
      if (boundActivity != null) {
        ViewGroup decor = (ViewGroup) boundActivity.getWindow().getDecorView();
        if (focusListener != null) {
          decor.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
        }
        if (layoutListener != null) {
          decor.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        }
        if (highlightView != null) {
          decor.removeView(highlightView);
        }
      }
    } catch (Exception ignored) { }
    highlightView = null;
    focusListener = null;
    layoutListener = null;
    boundActivity = null;
  }

  /**
   * Apply focusable fix to ALL windows (including PopupWindow menus).
   * Uses WindowManagerGlobal to find every active window's decor view.
   */
  public static void fixAllWindows() {
    try {
      Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
      Object wmg = wmgClass.getMethod("getInstance").invoke(null);
      // getWindowViews is hidden but works via reflection on Android 11
      java.util.List<View> views;
      try {
        views = (java.util.List<View>) wmgClass.getMethod("getWindowViews").invoke(wmg);
      } catch (Exception e) {
        // Fallback: try getViews
        views = (java.util.List<View>) wmgClass.getMethod("getViews").invoke(wmg);
      }
      if (views != null) {
        for (View v : views) {
          makeClickableFocusable(v);
          // Also wire focus highlight listener for popup windows
          wireFocusListener(v);
        }
      }
    } catch (Exception ignored) { }
  }

  /** Attach focus listener to a window's decor for highlight tracking. */
  private static void wireFocusListener(View decor) {
    try {
      if (decor == null || highlightView == null) return;
      // Avoid double-wiring
      if (!wiredViews.add(decor)) return;
      decor.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
        if (newF != null && newF != highlightView) {
          // For popup windows, show highlight in activity decor (coordinates are screen-based)
          highlightView.moveTo(newF);
        }
      });
    } catch (Exception ignored) { }
  }

  /**
   * Recursively make all clickable views focusable so D-pad can reach them.
   * This fixes menus and buttons that weren't marked focusable.
   */
  public static void makeClickableFocusable(View view) {
    try {
      if (view == null) return;
      // Make ALL potentially interactive views focusable for D-pad:
      // clickable, long-clickable, or has click listener
      if (view != highlightView && !view.isFocusable()) {
        boolean interactive = view.isClickable() || view.isLongClickable()
            || view.hasOnClickListeners();
        if (interactive) {
          view.setFocusable(true);
          view.setFocusableInTouchMode(false);
        }
      }
      if (view instanceof ViewGroup) {
        ViewGroup vg = (ViewGroup) view;
        for (int i = 0; i < vg.getChildCount(); i++) {
          makeClickableFocusable(vg.getChildAt(i));
        }
      }
    } catch (Exception ignored) { }
  }

  /** Overlay view that draws a bold animated border around the focused view. */
  private static final class HighlightView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private ValueAnimator animator;
    private float animLeft, animTop, animRight, animBottom;
    private boolean hasTarget = false;

    HighlightView(Activity ctx) {
      super(ctx);
      setWillNotDraw(false);
      paint.setStyle(Paint.Style.STROKE);
      paint.setColor(Color.parseColor("#FF9800")); // Bold orange
      paint.setStrokeWidth(dp(4));
      paint.setAntiAlias(true);
      // Hardware acceleration for smooth 60fps (no shadow layer - too slow)
    }

    private int dp(int v) {
      return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Animate the highlight to wrap the given view. */
    void moveTo(View target) {
      try {
        int[] loc = new int[2];
        target.getLocationOnScreen(loc);
        int[] rootLoc = new int[2];
        ((View) getParent()).getLocationOnScreen(rootLoc);
        float pad = dp(6);
        float nl = loc[0] - rootLoc[0] - pad;
        float nt = loc[1] - rootLoc[1] - pad;
        float nr = loc[0] - rootLoc[0] + target.getWidth() + pad;
        float nb = loc[1] - rootLoc[1] + target.getHeight() + pad;

        if (!hasTarget) {
          animLeft = nl; animTop = nt; animRight = nr; animBottom = nb;
          hasTarget = true;
          setVisibility(VISIBLE);
          invalidate();
          return;
        }
        // Instant move for Netflix-like snappiness (no animation lag).
        // Cancel any running animation.
        if (animator != null) animator.cancel();
        animLeft = nl; animTop = nt; animRight = nr; animBottom = nb;
        setVisibility(VISIBLE);
        invalidate();
      } catch (Exception ignored) { }
    }

    void hide() {
      hasTarget = false;
      if (animator != null) animator.cancel();
      setVisibility(GONE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (!hasTarget || getVisibility() != VISIBLE) return;
      rect.set(animLeft, animTop, animRight, animBottom);
      float radius = dp(8);
      // Single bold border - fast and clear
      canvas.drawRoundRect(rect, radius, radius, paint);
    }
  }
}
