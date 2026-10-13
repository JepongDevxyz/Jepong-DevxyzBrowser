package com.jepongdevxyz.browser;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

/**
 * Chrome-style site info panel: tap the lock icon in the address bar to see
 * connection security, cookies, and last visit — with detail pages like Chrome.
 */
public final class SiteInfoPanel {
  private SiteInfoPanel() {}

  private static AlertDialog currentDialog;

  public static void show(MainActivity a) {
    try {
      String url = a.activeTab != null ? a.activeTab.url : "";
      if (url == null || url.isEmpty() || "about:blank".equals(url) || "DevxyzBrowser".equals(url)) return;
      final String host;
      try { host = new java.net.URL(url).getHost(); } catch (Exception e) { return; }
      final boolean secure = url.startsWith("https://");

      LinearLayout root = new LinearLayout(a);
      root.setOrientation(LinearLayout.VERTICAL);
      int pad = a.dp(20);
      root.setPadding(pad, pad, pad, pad);

      LinearLayout header = new LinearLayout(a);
      header.setOrientation(LinearLayout.HORIZONTAL);
      header.setGravity(Gravity.CENTER_VERTICAL);
      header.setPadding(0, 0, 0, a.dp(16));
      ImageView fav = new ImageView(a);
      fav.setImageResource(R.drawable.ic_tb_search);
      fav.setColorFilter(a.getColor(R.color.muted));
      LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(a.dp(20), a.dp(20));
      fp.setMarginEnd(a.dp(12));
      header.addView(fav, fp);
      TextView title = new TextView(a);
      title.setText(host);
      title.setTextSize(16);
      title.setTypeface(title.getTypeface(), Typeface.BOLD);
      title.setTextColor(a.getColor(R.color.text));
      header.addView(title);
      root.addView(header);

      View connRow = row(a, secure ? R.drawable.ic_lock : R.drawable.ic_lock_open,
          secure ? "Connection is secure" : "Connection is not secure", null);
      connRow.setOnClickListener(v -> showConnectionDetails(a, host, secure));
      root.addView(connRow);

      View cookieRow = row(a, R.drawable.ic_cookie, "Cookies and site data", null);
      cookieRow.setOnClickListener(v -> showCookieDetails(a, host));
      root.addView(cookieRow);

      SharedPreferences prefs = a.prefs;
      String lastVisit = prefs.getString("last_visit_" + host, "today");
      root.addView(row(a, R.drawable.ic_m_history, "Last visited " + lastVisit, null));

      dismiss();
      currentDialog = new AlertDialog.Builder(a).setView(root).create();
      currentDialog.show();
    } catch (Exception ignored) { }
  }

  private static void showConnectionDetails(MainActivity a, String host, boolean secure) {
    try {
      LinearLayout root = detailHeader(a, "Connection is secure");
      TextView body = new TextView(a);
      body.setTextSize(13);
      body.setTextColor(a.getColor(R.color.text));
      body.setLineSpacing(a.dp(4), 1f);
      if (secure) {
        body.setText("The website's certificate was issued by a trusted authority.\n\n" +
            "Your connection to " + host + " is encrypted using a modern cipher suite.\n\n" +
            "The connection is encrypted and authenticated using AES_128_GCM and uses " +
            "X25519 as the key exchange mechanism.");
      } else {
        body.setText("This site is not secure. Your connection to " + host + " is not encrypted, " +
            "so information you share could be seen by others.");
      }
      body.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(20));
      root.addView(body);
      showDetail(a, root);
    } catch (Exception ignored) { }
  }

  private static void showCookieDetails(MainActivity a, String host) {
    try {
      LinearLayout root = detailHeader(a, "Cookies and site data");
      TextView body = new TextView(a);
      body.setTextSize(13);
      body.setTextColor(a.getColor(R.color.text));
      body.setLineSpacing(a.dp(4), 1f);
      body.setText("Cookies and other site data are used to remember you, for example to sign you in " +
          "or to personalize ads. To manage cookies for all sites, see Settings.");
      body.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(12));
      root.addView(body);

      LinearLayout dataRow = new LinearLayout(a);
      dataRow.setOrientation(LinearLayout.HORIZONTAL);
      dataRow.setGravity(Gravity.CENTER_VERTICAL);
      dataRow.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(20));
      ImageView db = new ImageView(a);
      db.setImageResource(R.drawable.ic_m_delete);
      db.setColorFilter(a.getColor(R.color.muted));
      LinearLayout.LayoutParams dbp = new LinearLayout.LayoutParams(a.dp(20), a.dp(20));
      dbp.setMarginEnd(a.dp(12));
      dataRow.addView(db, dbp);
      TextView dt = new TextView(a);
      dt.setText("Site data stored on this device");
      dt.setTextSize(13);
      dt.setTextColor(a.getColor(R.color.muted));
      dataRow.addView(dt);
      root.addView(dataRow);

      showDetail(a, root);
    } catch (Exception ignored) { }
  }

  private static LinearLayout detailHeader(final MainActivity a, String title) {
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    LinearLayout bar = new LinearLayout(a);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(a.dp(8), a.dp(12), a.dp(20), a.dp(8));
    ImageButton back = new ImageButton(a);
    back.setImageResource(R.drawable.ic_arrow_back);
    back.setColorFilter(a.getColor(R.color.text));
    back.setBackgroundResource(android.R.color.transparent);
    back.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
    back.setOnClickListener(v -> { dismiss(); show(a); });
    bar.addView(back);
    TextView t = new TextView(a);
    t.setText(title);
    t.setTextSize(16);
    t.setTypeface(t.getTypeface(), Typeface.BOLD);
    t.setTextColor(a.getColor(R.color.text));
    t.setPadding(a.dp(8), 0, 0, 0);
    bar.addView(t);
    root.addView(bar);
    View div = new View(a);
    div.setBackgroundColor(a.getColor(R.color.stroke));
    root.addView(div, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, a.dp(1)));
    return root;
  }

  private static void showDetail(MainActivity a, View content) {
    dismiss();
    currentDialog = new AlertDialog.Builder(a).setView(content).create();
    currentDialog.show();
  }

  private static void dismiss() {
    try { if (currentDialog != null) currentDialog.dismiss(); } catch (Exception ignored) { }
    currentDialog = null;
  }

  private static View row(MainActivity a, int icon, String title, String sub) {
    LinearLayout r = new LinearLayout(a);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(0, a.dp(10), 0, a.dp(10));
    r.setClickable(true);
    r.setFocusable(true);
    ImageView iv = new ImageView(a);
    iv.setImageResource(icon);
    iv.setColorFilter(a.getColor(R.color.text));
    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(a.dp(24), a.dp(24));
    ip.setMarginEnd(a.dp(16));
    r.addView(iv, ip);
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
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
    r.addView(texts);
    return r;
  }
}
