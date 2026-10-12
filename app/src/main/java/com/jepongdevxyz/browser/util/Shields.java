package com.jepongdevxyz.browser.util;

import android.content.SharedPreferences;
import org.mozilla.geckoview.ContentBlocking;

/**
 * Brave-style Shields backed by GeckoView's real content-blocking engine.
 * <ul>
 *   <li>Block trackers &amp; ads: Aggressive / Standard / Allow — maps to ETP levels.</li>
 *   <li>Block fingerprinting: adds the FINGERPRINTING anti-tracking category.</li>
 *   <li>Block third-party cookies: isolates third-party cookies.</li>
 *   <li>Block scripts: applied LIVE per session via setAllowJavascript.</li>
 * </ul>
 * ContentBlocking is baked into the GeckoRuntime, so trackers/ads/cookies/
 * fingerprinting changes apply after an app restart. Script blocking applies immediately.
 */
public final class Shields {
  private Shields() {}

  /** 0 = Allow all, 1 = Standard, 2 = Aggressive. */
  public static int mode(SharedPreferences prefs) {
    int m = prefs.getInt("shields_mode", -1);
    if (m < 0) {
      // Migrate the old etp_level (0=Off, 1=Standard, 2=Strict).
      int etp = prefs.getInt("etp_level", 1);
      m = etp;
      prefs.edit().putInt("shields_mode", m).apply();
    }
    return m;
  }

  public static ContentBlocking.Settings build(SharedPreferences prefs) {
    int mode = mode(prefs);
    boolean fingerprinting = prefs.getBoolean("shields_fingerprinting", true);
    boolean blockCookies = prefs.getBoolean("shields_block_cookies", true);
    ContentBlocking.Settings.Builder b = new ContentBlocking.Settings.Builder();

    if (mode == 0) {
      b.enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
       .antiTracking(ContentBlocking.AntiTracking.NONE);
    } else if (mode == 2) {
      int at = ContentBlocking.AntiTracking.AD
          | ContentBlocking.AntiTracking.ANALYTIC
          | ContentBlocking.AntiTracking.SOCIAL
          | ContentBlocking.AntiTracking.CONTENT
          | ContentBlocking.AntiTracking.CRYPTOMINING
          | ContentBlocking.AntiTracking.FINGERPRINTING;
      b.enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
       .antiTracking(at)
       .strictSocialTrackingProtection(true)
       .queryParameterStrippingEnabled(true);
    } else {
      int at = ContentBlocking.AntiTracking.AD
          | ContentBlocking.AntiTracking.ANALYTIC
          | ContentBlocking.AntiTracking.SOCIAL;
      if (fingerprinting) at |= ContentBlocking.AntiTracking.FINGERPRINTING;
      b.enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
       .antiTracking(at);
    }
    b.cookieBehavior(blockCookies
        ? ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
        : ContentBlocking.CookieBehavior.ACCEPT_ALL);
    return b.build();
  }

  public static boolean blockScripts(SharedPreferences prefs) {
    return prefs.getBoolean("shields_block_scripts", false);
  }
}
