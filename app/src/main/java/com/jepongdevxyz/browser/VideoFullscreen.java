package com.jepongdevxyz.browser;

import android.app.Activity;
import android.view.View;

/** Handles HTML5 video fullscreen for GeckoView: landscape + immersive. */
public class VideoFullscreen {
  private static boolean isFs = false;

  public static boolean isFullscreen() { return isFs; }

  public static void set(Activity a, boolean fs) {
    isFs = fs;
    View d = a.getWindow().getDecorView();
    int v = fs ? View.GONE : View.VISIBLE;
    a.findViewById(R.id.toolbar).setVisibility(v);
    View ts = a.findViewById(R.id.tabStrip);
    if (ts != null) ts.setVisibility(v);
    d.setSystemUiVisibility(fs ? 5894 : 0); // immersive + fullscreen + hide nav
    a.setRequestedOrientation(fs ? 6 : 1); // sensorLandscape / portrait
  }
}
