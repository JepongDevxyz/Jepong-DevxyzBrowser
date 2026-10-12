package com.jepongdevxyz.browser.util;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/** Detects Android TV / Google TV so the app can switch to the D-pad friendly UI. */
public final class TvUtil {
  private TvUtil() {}

  public static boolean isTv(Context ctx) {
    if (ctx == null) return false;
    try {
      UiModeManager umm = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
      if (umm != null && umm.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
        return true;
      }
    } catch (Exception ignored) { }
    try {
      if (ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true;
      if (ctx.getPackageManager().hasSystemFeature("android.software.leanback_only")) return true;
    } catch (Exception ignored) { }
    return false;
  }
}
