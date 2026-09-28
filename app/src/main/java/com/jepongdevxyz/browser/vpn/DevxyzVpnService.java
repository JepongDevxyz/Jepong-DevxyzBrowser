package com.jepongdevxyz.browser.vpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;

/**
 * Android VPN lifecycle owner. The OpenVPN native transport will attach here;
 * this class never establishes a dummy tunnel or reports a false connection.
 */
public final class DevxyzVpnService extends VpnService {
    public static final String ACTION_CONNECT = "com.jepongdevxyz.browser.vpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.jepongdevxyz.browser.vpn.DISCONNECT";
    private static volatile boolean connected;

    public static boolean isConnected() { return connected; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            connected = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onRevoke() {
        connected = false;
        stopSelf();
        super.onRevoke();
    }

    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }
}