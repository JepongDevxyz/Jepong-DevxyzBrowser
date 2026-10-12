package com.jepongdevxyz.browser.util;

import android.app.Activity;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;

/**
 * Brave-style Appearance: theme, dynamic colors, menu/toolbar customization.
 * All settings apply with real handlers — no mocks.
 */
public final class Appearance {
  private Appearance() {}

  /** 0 = System default, 1 = Light, 2 = Dark. */
  public static void applyTheme(SharedPreferences prefs) {
    int t = prefs.getInt("app_theme", 0);
    int mode = t == 1 ? AppCompatDelegate.MODE_NIGHT_NO
        : t == 2 ? AppCompatDelegate.MODE_NIGHT_YES
        : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    AppCompatDelegate.setDefaultNightMode(mode);
  }

  public static String themeLabel(SharedPreferences prefs) {
    int t = prefs.getInt("app_theme", 0);
    return t == 1 ? "Light" : t == 2 ? "Dark" : "System default";
  }

  public static void applyDynamicColors(Activity activity, SharedPreferences prefs) {
    try {
      if (prefs.getBoolean("dynamic_colors", false)) {
        DynamicColors.applyToActivityIfAvailable(activity);
      }
    } catch (Exception ignored) { }
  }

  /** Menu item visibility for "Customize menu". Defaults to visible. */
  public static boolean menuVisible(SharedPreferences prefs, String key) {
    return prefs.getBoolean("menu_" + key, true);
  }

  /** Address bar position: false = Top (default), true = Bottom. */
  public static boolean addressBarBottom(SharedPreferences prefs) {
    return prefs.getBoolean("address_bar_bottom", false);
  }

  /** Toolbar shortcut action key, or "" for none. */
  public static String toolbarShortcut(SharedPreferences prefs) {
    return prefs.getString("toolbar_shortcut", "");
  }
}
