package com.jepongdevxyz.browser.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.IpPrefix;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.IBinder;
import android.util.Log;
import com.jepongdevxyz.browser.MainActivity;
import com.jepongdevxyz.browser.R;
import java.io.IOException;

/** Owns the Android VPN interface and the OpenVPN 3 client lifetime. */
public final class DevxyzVpnService extends VpnService {
    public static final String ACTION_CONNECT = "com.jepongdevxyz.browser.vpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.jepongdevxyz.browser.vpn.DISCONNECT";
    public static final String ACTION_STATE = "com.jepongdevxyz.browser.vpn.STATE";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_KEY_PASSWORD = "key_password";
    private static final String CHANNEL = "devxyz_openvpn";
    private static final int NOTIFICATION_ID = 4107;
    private static volatile boolean connected;

    private Builder tunBuilder;
    private volatile boolean foreground;
    private volatile boolean nativeStarted;

    public static boolean isConnected() { return connected; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            NativeOpenVpn.stop();
            connected = false;
            publish("DISCONNECTED", "Stopped by user", false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_CONNECT.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (nativeStarted) {
            publish(connected ? "CONNECTED" : "CONNECTING", "OpenVPN client is already running.", false);
            return START_NOT_STICKY;
        }

        startTunnelForeground();
        try {
            String profile = VpnProfileStore.read(this);
            publish("CONNECTING", "Starting OpenVPN 3", false);
            nativeStarted = true;
            boolean started = NativeOpenVpn.start(this, profile,
                    intent.getStringExtra(EXTRA_USERNAME),
                    intent.getStringExtra(EXTRA_PASSWORD),
                    intent.getStringExtra(EXTRA_KEY_PASSWORD));
            if (!started) {
                nativeStarted = false;
                publish("ERROR", "OpenVPN client is already running or could not start.", true);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        } catch (IOException | UnsatisfiedLinkError e) {
            Log.e("DevxyzVpn", "Could not start OpenVPN", e);
            publish("ERROR", e.getMessage() == null ? "Unable to start VPN" : e.getMessage(), true);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void startTunnelForeground() {
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("Connecting"));
        foreground = true;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "OpenVPN connection", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows the state of the active VPN tunnel.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent disconnect = new Intent(this, DevxyzVpnService.class).setAction(ACTION_DISCONNECT);
        PendingIntent stop = PendingIntent.getService(this, 1, disconnect,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder.setSmallIcon(R.drawable.vpn_notification)
                .setContentTitle("DevxyzBrowser VPN")
                .setContentText(message)
                .setContentIntent(pending)
                .setOngoing(connected || foreground)
                .addAction(android.R.drawable.ic_media_pause, "Disconnect", stop)
                .build();
    }

    private void publish(String state, String detail, boolean error) {
        connected = "CONNECTED".equals(state);
        Intent broadcast = new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra("state", state).putExtra("detail", detail).putExtra("error", error);
        sendBroadcast(broadcast);
        if (foreground) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID, notification(connected ? "Connected" : state));
        }
        if ("EXITING".equals(state) || error) {
            nativeStarted = false;
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
            }
        }
    }

    /** Called on OpenVPN's worker thread for every client event. */
    public void nativeOnOpenVpnEvent(String name, String info, boolean error, boolean fatal) {
        Log.i("DevxyzVpn", name + (info == null || info.isEmpty() ? "" : ": " + info));
        String state = name;
        if ("CONNECTED".equals(name)) state = "CONNECTED";
        else if ("DISCONNECTED".equals(name) || "EXITING".equals(name)) state = "DISCONNECTED";
        publish(state, info == null || info.isEmpty() ? name : info, fatal);
        if (fatal || "EXITING".equals(name)) {
            nativeStarted = false;
            stopSelf();
        }
    }

    public boolean nativeProtectSocket(int fd) { return protect(fd); }

    public synchronized boolean nativeTunNew() {
        tunBuilder = new Builder().setSession("DevxyzBrowser OpenVPN");
        return true;
    }
    public synchronized boolean nativeTunAddAddress(String address, int prefix) {
        if (tunBuilder == null) return false;
        tunBuilder.addAddress(address, prefix);
        return true;
    }
    public synchronized boolean nativeTunAddRoute(String address, int prefix) {
        if (tunBuilder == null) return false;
        tunBuilder.addRoute(address, prefix);
        return true;
    }
    public synchronized boolean nativeTunExcludeRoute(String address, int prefix) {
        if (tunBuilder == null) return false;
        if (Build.VERSION.SDK_INT >= 33) tunBuilder.excludeRoute(new IpPrefix(address, prefix));
        return true;
    }
    public synchronized boolean nativeTunAddDns(String address) {
        if (tunBuilder == null) return false;
        tunBuilder.addDnsServer(address);
        return true;
    }
    public synchronized boolean nativeTunSetMtu(int mtu) {
        if (tunBuilder == null || mtu < 576 || mtu > 9000) return false;
        tunBuilder.setMtu(mtu);
        return true;
    }
    public synchronized boolean nativeTunSetSession(String name) {
        if (tunBuilder == null) return false;
        tunBuilder.setSession(name.isEmpty() ? "DevxyzBrowser OpenVPN" : name);
        return true;
    }
    public synchronized boolean nativeTunAllowFamily(int family, boolean allow) {
        if (tunBuilder == null) return false;
        if (allow) tunBuilder.allowFamily(family);
        else tunBuilder.disallowFamily(family);
        return true;
    }
    public synchronized int nativeTunEstablish() {
        if (tunBuilder == null) return -1;
        try (ParcelFileDescriptor descriptor = tunBuilder.establish()) {
            tunBuilder = null;
            return descriptor == null ? -1 : descriptor.detachFd();
        } catch (RuntimeException e) {
            Log.e("DevxyzVpn", "Failed to establish TUN", e);
            tunBuilder = null;
            return -1;
        }
    }
    public synchronized void nativeTunClosed() { tunBuilder = null; }

    @Override public void onRevoke() {
        NativeOpenVpn.stop();
        connected = false;
        publish("DISCONNECTED", "VPN permission revoked", false);
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        if (nativeStarted) NativeOpenVpn.stop();
        nativeStarted = false;
        connected = false;
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
