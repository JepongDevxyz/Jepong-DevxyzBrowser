package com.jepongdevxyz.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;
import java.util.UUID;

/**
 * OpenVPN Connect-style VPN Settings. Every setting is synced to the VPN service
 * via SharedPreferences — all functional, no dead toggles.
 */
public final class VpnSettings {
  private VpnSettings() {}

  public static void show(MainActivity a) {
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll = new ScrollView(a);
    scroll.addView(root);
    int pad = a.dp(20);
    root.setPadding(pad, pad, pad, pad);

    TextView title = new TextView(a);
    title.setText("VPN Settings");
    title.setTextSize(20);
    title.setTypeface(title.getTypeface(), Typeface.BOLD);
    title.setTextColor(a.getColor(R.color.text));
    title.setPadding(0, 0, 0, a.dp(8));
    root.addView(title);

    // Device ID with copy
    root.addView(deviceIdRow(a));

    // VPN Protocol: ADAPTIVE / TCP / UDP
    root.addView(segmentRow(a, "VPN Protocol", "vpn_protocol",
        new String[]{"ADAPTIVE", "TCP", "UDP"},
        new String[]{"adaptive", "tcp", "udp"}, 0));

    // Connection Timeout
    root.addView(segmentRow(a, "Connection Timeout", "vpn_timeout",
        new String[]{"10 SEC", "30 SEC", "1 MIN", "2 MIN", "RETRY"},
        new String[]{"10", "30", "60", "120", "-1"}, 1));

    // Reconnect Attempts
    root.addView(segmentRow(a, "Reconnect Attempts", "vpn_reconnect_count",
        new String[]{"3", "5", "10", "UNLIMITED"},
        new String[]{"3", "5", "10", "-1"}, 0));

    root.addView(toggleRow(a, "Battery Saver",
        "Pause VPN when screen goes blank", "vpn_battery_saver", false));

    // Launch Options
    root.addView(optionRow(a, "Launch Options", "vpn_launch",
        new String[]{"Connect latest", "Restore connection", "None"},
        new String[]{"latest", "restore", "none"}, 0,
        "What to do when the app starts"));

    root.addView(toggleRow(a, "Seamless Tunnel",
        "Block internet while VPN is paused or reconnecting", "vpn_seamless", false));

    root.addView(toggleRow(a, "Captive Portal Detection",
        "Detect hotel/airport WiFi login pages", "vpn_captive", true));

    // Confirmation Dialogs
    root.addView(optionRow(a, "Confirmation Dialogs", "vpn_confirm",
        new String[]{"All", "None", "On disconnect", "On reconnect"},
        new String[]{"all", "none", "disconnect", "reconnect"}, 0, null));

    root.addView(toggleRow(a, "Notifications",
        "Show VPN status in notification bar", "vpn_notifications", true));

    // Advanced
    TextView adv = new TextView(a);
    adv.setText("ADVANCED SETTINGS");
    adv.setTextSize(12);
    adv.setTypeface(adv.getTypeface(), Typeface.BOLD);
    adv.setTextColor(a.getColor(R.color.muted));
    adv.setPadding(0, a.dp(20), 0, a.dp(4));
    root.addView(adv);

    root.addView(optionRow(a, "Security Level", "vpn_security",
        new String[]{"Preferred (Recommended)", "Legacy", "Insecure (Not Recommended)"},
        new String[]{"preferred", "legacy", "insecure"}, 0, null));

    root.addView(toggleRow(a, "Enforce TLS 1.3",
        "Require TLS 1.3 for the VPN handshake", "vpn_tls13", false));

    root.addView(toggleRow(a, "Allow LAN Access",
        "Let local devices work while VPN is on", "vpn_allow_lan", true));

    root.addView(toggleRow(a, "DNS Fallback",
        "Fall back to system DNS if VPN DNS fails", "vpn_dns_fallback", true));

    Ui.dialog(a).setView(scroll)
        .setPositiveButton("Done", null).show();
  }

  private static View deviceIdRow(MainActivity a) {
    String id = a.prefs.getString("vpn_device_id", null);
    if (id == null) {
      id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
      a.prefs.edit().putString("vpn_device_id", id).apply();
    }
    final String deviceId = id;
    LinearLayout r = new LinearLayout(a);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(0, a.dp(12), 0, a.dp(12));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    TextView t = new TextView(a);
    t.setText("Device ID");
    t.setTextSize(14);
    t.setTextColor(a.getColor(R.color.text));
    texts.addView(t);
    TextView s = new TextView(a);
    s.setText(deviceId);
    s.setTextSize(12);
    s.setTextColor(a.getColor(R.color.muted));
    texts.addView(s);
    r.addView(texts, tp);
    TextView copy = new TextView(a);
    copy.setText("COPY");
    copy.setTextSize(13);
    copy.setTypeface(copy.getTypeface(), Typeface.BOLD);
    copy.setTextColor(a.getColor(R.color.purple));
    copy.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
    copy.setOnClickListener(v -> {
      ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
      cm.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId));
      Ui.toast(a, "Device ID copied");
    });
    r.addView(copy);
    return r;
  }

  private static View segmentRow(MainActivity a, String title, String key,
      String[] labels, String[] values, int def) {
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(0, a.dp(12), 0, a.dp(12));
    TextView t = new TextView(a);
    t.setText(title);
    t.setTextSize(14);
    t.setTextColor(a.getColor(R.color.text));
    t.setPadding(0, 0, 0, a.dp(8));
    root.addView(t);
    LinearLayout seg = new LinearLayout(a);
    seg.setOrientation(LinearLayout.HORIZONTAL);
    String cur = a.prefs.getString(key, values[def]);
    for (int i = 0; i < labels.length; i++) {
      final int idx = i;
      TextView btn = new TextView(a);
      btn.setText(labels[i]);
      btn.setTextSize(12);
      btn.setTypeface(btn.getTypeface(), Typeface.BOLD);
      btn.setGravity(Gravity.CENTER);
      btn.setPadding(a.dp(8), a.dp(10), a.dp(8), a.dp(10));
      LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) bp.setMarginStart(a.dp(4));
      boolean selected = values[i].equals(cur);
      btn.setBackgroundResource(selected ? R.drawable.chip_bg_active : R.drawable.chip_bg);
      btn.setTextColor(a.getColor(selected ? R.color.text : R.color.muted));
      btn.setOnClickListener(v -> {
        a.prefs.edit().putString(key, values[idx]).apply();
        // Refresh: rebuild segments
        for (int j = 0; j < seg.getChildCount(); j++) {
          TextView b = (TextView) seg.getChildAt(j);
          boolean sel = j == idx;
          b.setBackgroundResource(sel ? R.drawable.chip_bg_active : R.drawable.chip_bg);
          b.setTextColor(a.getColor(sel ? R.color.text : R.color.muted));
        }
        Ui.toast(a, title + ": " + labels[idx]);
      });
      seg.addView(btn, bp);
    }
    root.addView(seg);
    return root;
  }

  private static View optionRow(MainActivity a, String title, String key,
      String[] labels, String[] values, int def, String desc) {
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
    TextView curT = new TextView(a);
    curT.setTextSize(12);
    curT.setTextColor(a.getColor(R.color.muted));
    texts.addView(curT);
    if (desc != null) {
      TextView d = new TextView(a);
      d.setText(desc);
      d.setTextSize(11);
      d.setTextColor(a.getColor(R.color.muted));
      texts.addView(d);
    }
    r.addView(texts, tp);
    ImageView chev = new ImageView(a);
    chev.setImageResource(R.drawable.ic_chevron_right);
    chev.setColorFilter(a.getColor(R.color.muted));
    r.addView(chev, new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));

    Runnable refresh = () -> {
      String v = a.prefs.getString(key, values[def]);
      for (int i = 0; i < values.length; i++) {
        if (values[i].equals(v)) { curT.setText(labels[i]); break; }
      }
    };
    refresh.run();
    r.setOnClickListener(v -> {
      String cv = a.prefs.getString(key, values[def]);
      int checked = def;
      for (int i = 0; i < values.length; i++) {
        if (values[i].equals(cv)) checked = i;
      }
      Ui.dialog(a)
          .setTitle(title)
          .setSingleChoiceItems(labels, checked, (d, which) -> {
            a.prefs.edit().putString(key, values[which]).apply();
            refresh.run();
            d.dismiss();
          })
          .setNegativeButton("Cancel", null)
          .show();
    });
    return r;
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
    TextView s = new TextView(a);
    s.setText(sub);
    s.setTextSize(12);
    s.setTextColor(a.getColor(R.color.muted));
    texts.addView(s);
    r.addView(texts, tp);
    SwitchMaterial sw = new SwitchMaterial(a);
    sw.setChecked(a.prefs.getBoolean(key, def));
    sw.setOnCheckedChangeListener((b, c) -> {
      a.prefs.edit().putBoolean(key, c).apply();
      onSettingChanged(a, key, c);
    });
    r.addView(sw);
    r.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
    return r;
  }

  /** Apply setting changes live where possible. */
  private static void onSettingChanged(MainActivity a, String key, boolean val) {
    if ("vpn_battery_saver".equals(key)) {
      // Register/unregister screen-off receiver in VPN service on next connect
      Ui.toast(a, val ? "Battery saver ON — VPN pauses when screen is off"
                      : "Battery saver OFF");
    } else if ("vpn_seamless".equals(key)) {
      Ui.toast(a, val ? "Seamless tunnel ON — internet blocked during reconnect"
                      : "Seamless tunnel OFF");
    }
  }
}
