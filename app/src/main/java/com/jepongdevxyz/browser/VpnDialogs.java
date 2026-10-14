package com.jepongdevxyz.browser;

import android.content.Intent;

/**
 * VPN confirmation dialogs — themed to match the app (dark/light).
 * Extracted from MainActivity to keep MainActivity.java under the push size limit.
 */
public final class VpnDialogs {
  private VpnDialogs() {}

  /** Show the themed VPN connect/disconnect confirmation dialog. */
  public static void confirmToggle(MainActivity a) {
    String confirm = a.prefs.getString("vpn_confirm", "all");
    boolean isConn = DevxyzVpnService.isConnected();
    boolean needConfirm = "all".equals(confirm) ||
        ("disconnect".equals(confirm) && isConn) ||
        ("reconnect".equals(confirm) && !isConn);
    if (needConfirm) {
      Ui.dialog(a)
          .setTitle(isConn ? "Disconnect VPN?" : "Connect VPN?")
          .setMessage(isConn ? "Disconnect from the VPN server?"
                             : "Connect to the VPN server?")
          .setPositiveButton(isConn ? "Disconnect" : "Connect",
              (d, w) -> a.doToggleVpnConnection())
          .setNegativeButton("Cancel", null)
          .show();
      return;
    }
    a.doToggleVpnConnection();
  }
}
