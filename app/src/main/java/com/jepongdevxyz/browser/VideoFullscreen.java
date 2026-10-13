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
    // TV stays landscape; phones toggle
    if (!isTv(a)) a.setRequestedOrientation(fs ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
  }

  /** True on Android TV / Google TV devices. */
  public static boolean isTv(Activity a) {
    android.app.UiModeManager u =
        (android.app.UiModeManager) a.getSystemService(android.content.Context.UI_MODE_SERVICE);
    return u != null
        && u.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
  }

  /** JS: auto-fullscreen any playing video (used on TV). */
  public static String autoFullscreenJs() {
    return "(function(){"
        + "function fs(v){"
        + "  var r=v.requestFullscreen||v.webkitRequestFullscreen||v.mozRequestFullScreen;"
        + "  if(r) try{r.call(v);}catch(e){}"
        + "}"
        + "document.addEventListener('play',function(e){"
        + "  var v=e.target;"
        + "  if(v&&v.tagName==='VIDEO') setTimeout(function(){fs(v);},300);"
        + "},true);"
        + "Array.prototype.forEach.call(document.querySelectorAll('video'),function(v){"
        + "  if(!v.paused) fs(v);"
        + "});"
        + "})();";
  }
}
