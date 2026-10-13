package com.jepongdevxyz.browser;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * Chrome-style Settings pages: Accessibility (zoom) and Site settings (permissions).
 */
public final class ChromeSettings {
  private ChromeSettings() {}

  /** Add Accessibility + Site settings rows to the main settings panel. */
  public static void addRows(MainActivity a, LinearLayout root) {
    LinearLayout accRow = row(a, "Accessibility", "Text scaling, zoom, simplified view");
    accRow.setOnClickListener(v -> showAccessibility(a));
    root.addView(accRow, Math.max(0, root.getChildCount() - 1));

    LinearLayout siteRow = row(a, "Site settings", "Permissions, cookies, JavaScript");
    siteRow.setOnClickListener(v -> showSiteSettings(a));
    root.addView(siteRow, Math.max(0, root.getChildCount() - 1));
  }

  /** Accessibility page like Chrome: zoom slider + toggles. */
  public static void showAccessibility(MainActivity a) {
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll = new ScrollView(a);
    scroll.addView(root);
    int pad = a.dp(20);
    root.setPadding(pad, pad, pad, pad);

    addHeader(a, root, "Accessibility");

    // Default zoom
    TextView zt = new TextView(a);
    zt.setText("Default zoom");
    zt.setTextSize(15);
    zt.setTypeface(zt.getTypeface(), Typeface.BOLD);
    zt.setTextColor(a.getColor(R.color.text));
    root.addView(zt);
    TextView zd = new TextView(a);
    zd.setText("Make text and images larger or smaller on sites you visit");
    zd.setTextSize(13);
    zd.setTextColor(a.getColor(R.color.muted));
    zd.setPadding(0, a.dp(2), 0, a.dp(8));
    root.addView(zd);

    int curZoom = a.prefs.getInt("access_zoom", 100);
    LinearLayout zoomRow = new LinearLayout(a);
    zoomRow.setOrientation(LinearLayout.HORIZONTAL);
    zoomRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView minus = new TextView(a);
    minus.setText("−");
    minus.setTextSize(22);
    minus.setTextColor(a.getColor(R.color.text));
    minus.setPadding(a.dp(8), 0, a.dp(8), 0);
    zoomRow.addView(minus);
    SeekBar sb = new SeekBar(a);
    LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    zoomRow.addView(sb, sbp);
    TextView plus = new TextView(a);
    plus.setText("+");
    plus.setTextSize(22);
    plus.setTextColor(a.getColor(R.color.text));
    plus.setPadding(a.dp(8), 0, a.dp(8), 0);
    zoomRow.addView(plus);
    TextView pct = new TextView(a);
    pct.setTextSize(13);
    pct.setTextColor(a.getColor(R.color.muted));
    pct.setPadding(a.dp(8), 0, 0, 0);
    zoomRow.addView(pct);
    // 50% - 200% in 25% steps
    String[] vals = {"50%", "75%", "100%", "125%", "150%", "175%", "200%"};
    int idx = 2;
    for (int i = 0; i < vals.length; i++) {
      if ((50 + i * 25) == curZoom) idx = i;
    }
    final int[] cur = {idx};
    sb.setMax(6);
    sb.setProgress(idx);
    pct.setText(vals[idx]);
    sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
        cur[0] = p;
        pct.setText(vals[p]);
        a.prefs.edit().putInt("access_zoom", 50 + p * 25).apply();
        a.applyTextZoom();
      }
      @Override public void onStartTrackingTouch(SeekBar s) {}
      @Override public void onStopTrackingTouch(SeekBar s) {}
    });
    minus.setOnClickListener(v -> { if (cur[0] > 0) sb.setProgress(cur[0] - 1); });
    plus.setOnClickListener(v -> { if (cur[0] < 6) sb.setProgress(cur[0] + 1); });
    root.addView(zoomRow);

    root.addView(toggleRow(a, "Show zoom option in main menu",
        "Make it easier to customize zoom for different sites", "access_zoom_menu", false));
    root.addView(toggleRow(a, "Force enable zoom",
        "Override a website's request to prevent zooming in", "access_force_zoom", false));
    root.addView(toggleRow(a, "Simplified view for web pages",
        "Get notified when an article can be shown in simplified view", "access_simplified", false));
    root.addView(toggleRow(a, "Navigate pages with a text cursor",
        "To turn caret browsing on or off, use the shortcut F7", "access_caret", false));

    new AlertDialog.Builder(a).setView(scroll)
        .setPositiveButton("Done", null).show();
  }

  /** Site settings page like Chrome: permissions list. */
  public static void showSiteSettings(MainActivity a) {
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll = new ScrollView(a);
    scroll.addView(root);
    int pad = a.dp(20);
    root.setPadding(pad, pad, pad, pad);

    addHeader(a, root, "Site settings");

    sectionTitle(a, root, "Permissions");
    root.addView(permRow(a, "Location", "site_perm_location", 0));
    root.addView(permRow(a, "Camera", "site_perm_camera", 1));
    root.addView(permRow(a, "Microphone", "site_perm_mic", 1));
    root.addView(permRow(a, "Notifications", "site_perm_notif", 1));

    sectionTitle(a, root, "Content");
    root.addView(permRow(a, "Cookies", "site_perm_cookies", 2));
    root.addView(permRow(a, "JavaScript", "site_perm_js", 2));
    root.addView(permRow(a, "Pop-ups and redirects", "site_perm_popup", 0));
    root.addView(permRow(a, "Sound", "site_perm_sound", 2));
    root.addView(permRow(a, "Intrusive ads", "site_perm_ads", 3));

    root.addView(toggleRow(a, "Automatically remove permissions",
        "To protect your data, remove permissions from sites you haven't visited recently",
        "site_auto_remove_perm", true));

    new AlertDialog.Builder(a).setView(scroll)
        .setPositiveButton("Done", null).show();
  }

  // 0=Not allowed, 1=Ask first, 2=Allowed, 3=Blocked on some sites
  private static View permRow(MainActivity a, String title, String key, int def) {
    LinearLayout r = new LinearLayout(a);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(0, a.dp(12), 0, a.dp(12));
    r.setClickable(true);
    r.setFocusable(true);
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    TextView t = new TextView(a);
    t.setText(title);
    t.setTextSize(14);
    t.setTextColor(a.getColor(R.color.text));
    texts.addView(t);
    TextView s = new TextView(a);
    s.setTextSize(12);
    s.setTextColor(a.getColor(R.color.muted));
    texts.addView(s);
    r.addView(texts, tp);
    ImageView chev = new ImageView(a);
    chev.setImageResource(R.drawable.ic_chevron_right);
    chev.setColorFilter(a.getColor(R.color.muted));
    r.addView(chev, new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));

    String[] opts = {"Not allowed", "Ask first", "Allowed", "Blocked on some sites"};
    Runnable refresh = () -> s.setText(opts[a.prefs.getInt(key, def)]);
    refresh.run();
    r.setOnClickListener(v -> {
      int cur = a.prefs.getInt(key, def);
      new AlertDialog.Builder(a)
          .setTitle(title)
          .setSingleChoiceItems(opts, cur, (d, which) -> {
            a.prefs.edit().putInt(key, which).apply();
            refresh.run();
            d.dismiss();
          })
          .setNegativeButton("Cancel", null)
          .show();
    });
    return r;
  }

  private static void addHeader(MainActivity a, LinearLayout root, String title) {
    LinearLayout bar = new LinearLayout(a);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(0, 0, 0, a.dp(16));
    TextView t = new TextView(a);
    t.setText(title);
    t.setTextSize(20);
    t.setTypeface(t.getTypeface(), Typeface.BOLD);
    t.setTextColor(a.getColor(R.color.text));
    bar.addView(t);
    root.addView(bar);
  }

  private static void sectionTitle(MainActivity a, LinearLayout root, String s) {
    TextView t = new TextView(a);
    t.setText(s);
    t.setTextSize(13);
    t.setTextColor(a.getColor(R.color.muted));
    t.setPadding(0, a.dp(16), 0, a.dp(4));
    root.addView(t);
  }

  private static View toggleRow(MainActivity a, String title, String sub, String key, boolean def) {
    LinearLayout r = new LinearLayout(a);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(0, a.dp(12), 0, a.dp(12));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    TextView t = new TextView(a);
    t.setText(title);
    t.setTextSize(14);
    t.setTextColor(a.getColor(R.color.text));
    texts.addView(t);
    if (sub != null) {
      TextView s = new TextView(a);
      s.setText(sub);
      s.setTextSize(12);
      s.setTextColor(a.getColor(R.color.muted));
      texts.addView(s);
    }
    r.addView(texts, tp);
    SwitchMaterial sw = new SwitchMaterial(a);
    sw.setChecked(a.prefs.getBoolean(key, def));
    sw.setOnCheckedChangeListener((b, c) -> a.prefs.edit().putBoolean(key, c).apply());
    r.addView(sw);
    r.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
    return r;
  }

  private static LinearLayout row(MainActivity a, String title, String sub) {
    LinearLayout r = new LinearLayout(a);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(a.dp(20), a.dp(14), a.dp(20), a.dp(14));
    r.setClickable(true);
    r.setFocusable(true);
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    TextView t = new TextView(a);
    t.setText(title);
    t.setTextSize(15);
    t.setTextColor(a.getColor(R.color.text));
    texts.addView(t);
    TextView s = new TextView(a);
    s.setText(sub);
    s.setTextSize(12);
    s.setTextColor(a.getColor(R.color.muted));
    texts.addView(s);
    r.addView(texts, tp);
    ImageView chev = new ImageView(a);
    chev.setImageResource(R.drawable.ic_chevron_right);
    chev.setColorFilter(a.getColor(R.color.muted));
    r.addView(chev, new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));
    return r;
  }
}
