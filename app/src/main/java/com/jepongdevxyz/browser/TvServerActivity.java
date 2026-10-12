package com.jepongdevxyz.browser;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.jepongdevxyz.browser.util.TvUtil;
import com.jepongdevxyz.browser.util.Ui;
import com.jepongdevxyz.browser.vpn.DevxyzVpnService;
import com.jepongdevxyz.browser.vpn.VpnGateApi;
import com.jepongdevxyz.browser.vpn.VpnProfileStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * D-pad friendly VPN server picker for TV. Big rows, favorites first,
 * sorted by ping. Press OK on a server to connect immediately.
 */
public final class TvServerActivity extends AppCompatActivity {
  private final List<VpnGateApi.VpnServer> servers = new ArrayList<>();
  private final Set<String> favorites = new HashSet<>();
  private ServerAdapter adapter;
  private TextView statusText;
  private ActivityResultLauncher<Intent> vpnPermission;
  private VpnGateApi.VpnServer pending;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    if (!TvUtil.isTv(this)) { finish(); return; }
    favorites.addAll(getSharedPreferences("devxyz", MODE_PRIVATE)
      .getStringSet("vpn_favorites", new HashSet<>()));
    vpnPermission = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(),
      r -> { if (r.getResultCode() == RESULT_OK) beginConnect(); else Ui.toastLong(this, "VPN permission denied"); });

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(48), dp(48), dp(48), dp(32));
    root.setBackgroundColor(getColor(R.color.bg));

    TextView title = new TextView(this);
    title.setText("Choose VPN Server");
    title.setTextSize(30);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setTextColor(getColor(R.color.text));
    title.setPadding(0, 0, 0, dp(8));
    root.addView(title);

    statusText = new TextView(this);
    statusText.setTextSize(16);
    statusText.setTextColor(getColor(R.color.muted));
    statusText.setPadding(0, 0, 0, dp(16));
    root.addView(statusText);

    ListView list = new ListView(this);
    list.setDividerHeight(dp(8));
    adapter = new ServerAdapter(this, servers);
    list.setAdapter(adapter);
    list.setOnItemClickListener((parent, view, position, id) -> connectTo(servers.get(position)));
    root.addView(list, new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT, 1));

    setContentView(root);
    loadServers(false);
  }

  private void loadServers(boolean force) {
    statusText.setText("Loading servers…");
    VpnGateApi.fetch(this, force, new VpnGateApi.Callback() {
      @Override public void onResult(List<VpnGateApi.VpnServer> s, boolean fromCache) {
        servers.clear();
        List<VpnGateApi.VpnServer> favs = new ArrayList<>();
        List<VpnGateApi.VpnServer> rest = new ArrayList<>();
        for (VpnGateApi.VpnServer v : s) {
          if (favorites.contains(v.id())) favs.add(v); else rest.add(v);
        }
        servers.addAll(favs);
        servers.addAll(rest);
        adapter.notifyDataSetChanged();
        statusText.setText(servers.size() + " servers" + (fromCache ? " (cached)" : "")
          + " • OK to connect • ★ = favorite");
      }
      @Override public void onError(String message) {
        statusText.setText(message);
      }
    });
  }

  private void connectTo(VpnGateApi.VpnServer s) {
    if (DevxyzVpnService.isConnected()) {
      Ui.toastLong(this, "Disconnect first to switch servers");
      return;
    }
    Ui.toast(this, "Preparing " + s.country + "…");
    pending = s;
    new Thread(() -> {
      try {
        byte[] decoded = android.util.Base64.decode(s.configBase64, android.util.Base64.DEFAULT);
        String config = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        VpnProfileStore.writeActiveProfile(this, config);
        VpnProfileStore.setActiveServerId(this, s.id());
        runOnUiThread(() -> {
          Intent permission = VpnService.prepare(this);
          if (permission != null) vpnPermission.launch(permission);
          else beginConnect();
        });
      } catch (Exception e) {
        runOnUiThread(() -> Ui.toastLong(this, "Could not prepare server: " + e.getMessage()));
      }
    }).start();
  }

  private void beginConnect() {
    if (pending == null) return;
    Ui.toastLong(this, "Connecting to " + pending.country + "…");
    Intent connect = new Intent(this, DevxyzVpnService.class)
      .setAction(DevxyzVpnService.ACTION_CONNECT);
    try {
      ContextCompat.startForegroundService(this, connect);
    } catch (Exception e) {
      Ui.toastLong(this, "Could not start VPN: " + e.getMessage());
      return;
    }
    finish();
  }

  private final class ServerAdapter extends ArrayAdapter<VpnGateApi.VpnServer> {
    ServerAdapter(Context ctx, List<VpnGateApi.VpnServer> items) {
      super(ctx, 0, items);
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
      VpnGateApi.VpnServer s = getItem(position);
      LinearLayout row = new LinearLayout(getContext());
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(20), dp(18), dp(20), dp(18));
      row.setBackgroundColor(getColor(R.color.panel2));

      TextView flag = new TextView(getContext());
      flag.setText(s.flag());
      flag.setTextSize(30);
      row.addView(flag);

      LinearLayout texts = new LinearLayout(getContext());
      texts.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1);
      tp.setMargins(dp(16), 0, dp(12), 0);
      TextView name = new TextView(getContext());
      name.setText((favorites.contains(s.id()) ? "★ " : "") + s.country);
      name.setTextSize(21);
      name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
      name.setTextColor(getColor(R.color.text));
      TextView sub = new TextView(getContext());
      sub.setText(s.hostname + " • " + s.pingMs + " ms • " + s.speedLabel());
      sub.setTextSize(15);
      sub.setTextColor(getColor(R.color.muted));
      texts.addView(name);
      texts.addView(sub);
      row.addView(texts, tp);
      return row;
    }
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }
}
