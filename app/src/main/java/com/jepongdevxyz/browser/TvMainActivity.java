package com.jepongdevxyz.browser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.jepongdevxyz.browser.util.TvUtil;
import com.jepongdevxyz.browser.Ui;
import com.jepongdevxyz.browser.vpn.DevxyzVpnService;
import com.jepongdevxyz.browser.vpn.VpnGateApi;
import com.jepongdevxyz.browser.vpn.VpnProfileStore;
import java.util.ArrayList;
import java.util.List;

/**
 * D-pad friendly TV home for DevxyzBrowser. Shown automatically when a
 * television is detected (see TvUtil); phones keep the regular MainActivity.
 * Focuses on the VPN: big power button, status, and server picker — all
 * operable with a TV remote.
 */
public final class TvMainActivity extends AppCompatActivity {
  private TextView stateText, serverText, ipText;
  private Button powerBtn, serverBtn;
  private final List<VpnGateApi.VpnServer> servers = new ArrayList<>();

  private ActivityResultLauncher<Intent> vpnPermission;
  private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
    @Override public void onReceive(Context context, Intent intent) {
      if (!DevxyzVpnService.ACTION_STATE.equals(intent.getAction())) return;
      String detail = intent.getStringExtra("detail");
      boolean error = intent.getBooleanExtra("error", false);
      if (error && detail != null) Ui.toastLong(TvMainActivity.this, detail);
      refresh();
    }
  };

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    // Safety: never show the TV UI on a phone.
    if (!TvUtil.isTv(this)) {
      startActivity(new Intent(this, MainActivity.class));
      finish();
      return;
    }
    vpnPermission = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(),
      r -> { if (r.getResultCode() == RESULT_OK) beginConnect(); else Ui.toastLong(this, "VPN permission denied"); });

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER_HORIZONTAL);
    int pad = dp(48);
    root.setPadding(pad, dp(64), pad, dp(48));
    root.setBackgroundColor(getColor(R.color.bg));

    TextView title = new TextView(this);
    title.setText("DevxyzBrowser VPN");
    title.setTextSize(34);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setTextColor(getColor(R.color.text));
    title.setGravity(Gravity.CENTER);
    root.addView(title);

    stateText = new TextView(this);
    stateText.setTextSize(24);
    stateText.setGravity(Gravity.CENTER);
    stateText.setPadding(0, dp(24), 0, 0);
    root.addView(stateText);

    serverText = new TextView(this);
    serverText.setTextSize(19);
    serverText.setTextColor(getColor(R.color.muted));
    serverText.setGravity(Gravity.CENTER);
    serverText.setPadding(0, dp(8), 0, 0);
    root.addView(serverText);

    ipText = new TextView(this);
    ipText.setTextSize(16);
    ipText.setTextColor(getColor(R.color.muted));
    ipText.setGravity(Gravity.CENTER);
    ipText.setPadding(0, dp(4), 0, dp(32));
    root.addView(ipText);

    powerBtn = tvButton("Connect");
    powerBtn.setOnClickListener(v -> toggleVpn());
    root.addView(powerBtn);

    serverBtn = tvButton("Choose Server");
    serverBtn.setOnClickListener(v ->
      startActivity(new Intent(this, TvServerActivity.class)));
    LinearLayout.LayoutParams sbp = (LinearLayout.LayoutParams) serverBtn.getLayoutParams();
    sbp.topMargin = dp(16);
    root.addView(serverBtn);

    TextView hint = new TextView(this);
    hint.setText("Use the remote's D-pad to navigate • OK to select");
    hint.setTextSize(14);
    hint.setTextColor(getColor(R.color.muted));
    hint.setGravity(Gravity.CENTER);
    hint.setPadding(0, dp(32), 0, 0);
    root.addView(hint);

    setContentView(root);
    powerBtn.requestFocus();
    refresh();
    // Warm the server cache in the background so the picker opens fast.
    VpnGateApi.fetch(this, false, new VpnGateApi.Callback() {
      @Override public void onResult(List<VpnGateApi.VpnServer> s, boolean fromCache) {
        servers.clear();
        servers.addAll(s);
        refresh();
      }
      @Override public void onError(String message) { }
    });
  }

  private Button tvButton(String text) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(22);
    b.setAllCaps(false);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(420), dp(76));
    lp.gravity = Gravity.CENTER_HORIZONTAL;
    b.setLayoutParams(lp);
    b.setFocusable(true);
    b.setFocusableInTouchMode(true);
    // Visible focus ring for D-pad navigation.
    b.setOnFocusChangeListener((v, hasFocus) -> {
      v.setScaleX(hasFocus ? 1.06f : 1f);
      v.setScaleY(hasFocus ? 1.06f : 1f);
      ((Button) v).setTextColor(getColor(hasFocus ? R.color.orange : R.color.text));
    });
    return b;
  }

  private void toggleVpn() {
    if (DevxyzVpnService.isConnected()) {
      Intent stop = new Intent(this, DevxyzVpnService.class)
        .setAction(DevxyzVpnService.ACTION_DISCONNECT);
      startService(stop);
      return;
    }
    if (!VpnProfileStore.hasProfile(this)) {
      startActivity(new Intent(this, TvServerActivity.class));
      Ui.toastLong(this, "Pick a server first");
      return;
    }
    Intent permission = VpnService.prepare(this);
    if (permission != null) vpnPermission.launch(permission);
    else beginConnect();
  }

  private void beginConnect() {
    Intent connect = new Intent(this, DevxyzVpnService.class)
      .setAction(DevxyzVpnService.ACTION_CONNECT);
    try {
      ContextCompat.startForegroundService(this, connect);
    } catch (Exception e) {
      Ui.toastLong(this, "Could not start VPN: " + e.getMessage());
    }
    refresh();
  }

  private VpnGateApi.VpnServer findServer(String id) {
    for (VpnGateApi.VpnServer s : servers) {
      if (s.id().equals(id)) return s;
    }
    return null;
  }

  private void refresh() {
    if (stateText == null) return;
    boolean connected = DevxyzVpnService.isConnected();
    stateText.setText(connected ? "Connected" : "Disconnected");
    stateText.setTextColor(getColor(connected ? R.color.green : R.color.muted));
    powerBtn.setText(connected ? "Disconnect" : "Connect");
    String serverId = VpnProfileStore.getActiveServerId(this);
    if (!serverId.isEmpty()) {
      VpnGateApi.VpnServer s = findServer(serverId);
      serverText.setText(s != null ? s.flag() + " " + s.country + " • " + s.hostname : serverId);
    } else if (VpnProfileStore.hasProfile(this)) {
      serverText.setText("Custom .ovpn profile");
    } else {
      serverText.setText("No server selected");
    }
    ipText.setText(connected ? "Your connection is secure" : "You're not protected");
  }

  @Override protected void onResume() {
    super.onResume();
    registerReceiver(vpnStateReceiver, new IntentFilter(DevxyzVpnService.ACTION_STATE),
      Context.RECEIVER_NOT_EXPORTED);
    refresh();
  }

  @Override protected void onPause() {
    super.onPause();
    try { unregisterReceiver(vpnStateReceiver); } catch (Exception ignored) { }
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }
}
