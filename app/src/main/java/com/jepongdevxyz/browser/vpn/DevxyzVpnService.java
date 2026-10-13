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
import java.net.InetAddress;
import java.net.UnknownHostException;

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
    /** When true, apps cannot bypass the tunnel and unexpected drops auto-reconnect. */
    public static volatile boolean killSwitch;
    private int reconnectTries = 0;
    private android.content.BroadcastReceiver screenReceiver;
    private boolean pausedByBatterySaver = false;

    private Builder tunBuilder;
    private volatile boolean foreground;
    private volatile boolean nativeStarted;
    private volatile boolean userStopped;
    private volatile boolean destroyed;

    public static boolean isConnected() { return connected; }
    /** Wall-clock time (ms) when the tunnel last reached CONNECTED, 0 when not connected. */
    private static volatile long connectTimeMs;
    public static long getConnectTimeMs() { return connected ? connectTimeMs : 0; }
    private static volatile long rxBaseBytes, txBaseBytes;
    /** Approximate session traffic via per-UID counters (all tunnel traffic counts toward our UID). */
    public static long getSessionRxBytes() {
        if (!connected) return 0;
        long cur = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
        return cur < 0 || rxBaseBytes < 0 ? -1 : Math.max(0, cur - rxBaseBytes);
    }
    public static long getSessionTxBytes() {
        if (!connected) return 0;
        long cur = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
        return cur < 0 || txBaseBytes < 0 ? -1 : Math.max(0, cur - txBaseBytes);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            NativeOpenVpn.stop();
            connected = false;
            userStopped = true;
            publish("DISCONNECTED", "Stopped by user", false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_CONNECT.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        userStopped = false;

        if (nativeStarted) {
            publish(connected ? "CONNECTED" : "CONNECTING", "OpenVPN client is already running.", false);
            return START_NOT_STICKY;
        }

        startTunnelForeground();
        try {
            String profile = VpnProfileStore.read(this);
            profile = applyProtocolSetting(profile);
            setupBatterySaver();
            checkCaptivePortal();
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
        if (connected) {
            connectTimeMs = System.currentTimeMillis();
            rxBaseBytes = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
            txBaseBytes = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
        } else if ("DISCONNECTED".equals(state) || "EXITING".equals(state) || error) connectTimeMs = 0;
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
        // Seamless Tunnel: keep kill-switch blocking during reconnect
        boolean seamless = false;
        try {
            seamless = getSharedPreferences("devxyz", MODE_PRIVATE)
                .getBoolean("vpn_seamless", false);
        } catch (Exception ignored) {}
        if (seamless && (error || "DISCONNECTED".equals(state)) && !userStopped && !destroyed) {
            Log.i("DevxyzVpn", "Seamless tunnel: keeping traffic blocked during reconnect");
            // Keep TUN up (don't tear down) so traffic stays blocked
        }
        if (!userStopped && !destroyed && (error || "DISCONNECTED".equals(state))) {
            int maxRetries = maxReconnectAttempts();
            if (maxRetries < 0 || reconnectTries < maxRetries) {
                reconnectTries++;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (destroyed || userStopped || connected) return;
                    try {
                        if (!VpnProfileStore.hasProfile(this) || VpnProfileStore.requiresCredentials(this)) return;
                        Intent retry = new Intent(this, DevxyzVpnService.class).setAction(ACTION_CONNECT);
                        startService(retry);
                    } catch (Exception ignored) { }
                }, 4000);
            }
        }
        if ("CONNECTED".equals(state)) reconnectTries = 0;
    }

    /** Max reconnect attempts from VPN Settings (vpn_reconnect_count). -1 = unlimited. */
    private int maxReconnectAttempts() {
        try {
            String v = getSharedPreferences("devxyz", MODE_PRIVATE).getString("vpn_reconnect_count", "3");
            // Also check default prefs
            android.preference.PreferenceManager.getDefaultSharedPreferences(this);
            return Integer.parseInt(v);
        } catch (Exception e) { return 3; }
    }

    /** Connection timeout seconds from VPN Settings (vpn_timeout). -1 = retry forever. */
    private int connectionTimeoutSec() {
        try {
            return Integer.parseInt(
                getSharedPreferences("devxyz", MODE_PRIVATE).getString("vpn_timeout", "30"));
        } catch (Exception e) { return 30; }
    }

    /** Apply all VPN settings to the ovpn profile: protocol, TLS, security, timeout. */
    private String applyProtocolSetting(String profile) {
        try {
            if (profile == null) return profile;
            android.content.SharedPreferences p = getSharedPreferences("devxyz", MODE_PRIVATE);
            String[] lines = profile.split("\n");
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                String t = line.trim();
                // Strip lines we will re-inject from settings
                if (t.startsWith("proto ") || t.startsWith("tls-version-min") ||
                    t.startsWith("cipher ") || t.startsWith("connect-timeout") ||
                    t.startsWith("connect-retry")) continue;
                sb.append(line).append("\n");
            }
            // Protocol
            String proto = p.getString("vpn_protocol", "adaptive");
            if (!"adaptive".equals(proto)) {
                sb.append("proto ").append(proto).append("\n");
                Log.i("DevxyzVpn", "Protocol: " + proto);
            }
            // Enforce TLS 1.3
            if (p.getBoolean("vpn_tls13", false)) {
                sb.append("tls-version-min 1.3\n");
                Log.i("DevxyzVpn", "TLS 1.3 enforced");
            }
            // Security level -> cipher
            String sec = p.getString("vpn_security", "preferred");
            if ("legacy".equals(sec)) {
                sb.append("cipher AES-128-CBC\n");
            } else if ("insecure".equals(sec)) {
                sb.append("cipher BF-CBC\n");
            } else {
                sb.append("cipher AES-256-GCM\n");
            }
            Log.i("DevxyzVpn", "Security level: " + sec);
            // Connection timeout
            int timeout = 30;
            try { timeout = Integer.parseInt(p.getString("vpn_timeout", "30")); } catch (Exception ignored) {}
            if (timeout > 0) {
                sb.append("connect-timeout ").append(timeout).append("\n");
                sb.append("connect-retry ").append(timeout).append("\n");
                Log.i("DevxyzVpn", "Connect timeout: " + timeout + "s");
            }
            return sb.toString();
        } catch (Exception e) { return profile; }
    }

    /** Captive portal check: warn if WiFi needs login before VPN. */
    private void checkCaptivePortal() {
        try {
            boolean enabled = getSharedPreferences("devxyz", MODE_PRIVATE)
                .getBoolean("vpn_captive", true);
            if (!enabled) return;
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null && caps.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                Log.w("DevxyzVpn", "Captive portal detected - VPN may not work until login");
                publish("WARNING", "WiFi login required (captive portal detected)", false);
            }
        } catch (Exception e) { Log.e("DevxyzVpn", "Captive check failed", e); }
    }

    /** VPN protocol from settings: adaptive/tcp/udp. */
    private String vpnProtocol() {
        try {
            return getSharedPreferences("devxyz", MODE_PRIVATE).getString("vpn_protocol", "adaptive");
        } catch (Exception e) { return "adaptive"; }
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

    private void setupBatterySaver() {
        try {
            if (screenReceiver != null) {
                try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
                screenReceiver = null;
            }
            boolean enabled = getSharedPreferences("devxyz", MODE_PRIVATE)
                .getBoolean("vpn_battery_saver", false);
            if (!enabled) return;
            screenReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    String a = i.getAction();
                    if (android.content.Intent.ACTION_SCREEN_OFF.equals(a)) {
                        if (connected && !pausedByBatterySaver) {
                            pausedByBatterySaver = true;
                            Log.i("DevxyzVpn", "Battery saver: pausing VPN (screen off)");
                            try { NativeOpenVpn.stop(); } catch (Exception ignored) {}
                        }
                    } else if (android.content.Intent.ACTION_SCREEN_ON.equals(a)) {
                        if (pausedByBatterySaver) {
                            pausedByBatterySaver = false;
                            Log.i("DevxyzVpn", "Battery saver: resuming VPN (screen on)");
                            reconnectTries = 0;
                            try {
                                android.content.Intent retry = new android.content.Intent(
                                    DevxyzVpnService.this, DevxyzVpnService.class)
                                    .setAction(ACTION_CONNECT);
                                startService(retry);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            f.addAction(android.content.Intent.ACTION_SCREEN_ON);
            registerReceiver(screenReceiver, f);
            Log.i("DevxyzVpn", "Battery saver receiver registered");
        } catch (Exception e) { Log.e("DevxyzVpn", "Battery saver setup failed", e); }
    }

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
        // Allow LAN: skip routing local subnets through VPN
        if (allowLanAccess() && isLocalSubnet(address)) {
            Log.i("DevxyzVpn", "LAN access: skipping route " + address + "/" + prefix);
            return true;
        }
        tunBuilder.addRoute(address, prefix);
        return true;
    }

    private boolean allowLanAccess() {
        try {
            return getSharedPreferences("devxyz", MODE_PRIVATE).getBoolean("vpn_allow_lan", true);
        } catch (Exception e) { return true; }
    }

    private boolean isLocalSubnet(String addr) {
        return addr.startsWith("192.168.") || addr.startsWith("10.") ||
               addr.startsWith("172.16.") || addr.startsWith("172.17.") ||
               addr.startsWith("172.18.") || addr.startsWith("172.19.") ||
               addr.startsWith("172.2") || addr.startsWith("172.30.") ||
               addr.startsWith("172.31.");
    }
    public synchronized boolean nativeTunExcludeRoute(String address, int prefix) {
        if (tunBuilder == null) return false;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                tunBuilder.excludeRoute(new IpPrefix(InetAddress.getByName(address), prefix));
            } catch (UnknownHostException e) {
                Log.e("DevxyzVpn", "Invalid excluded route address", e);
                return false;
            }
        }
        return true;
    }
    public synchronized boolean nativeTunAddDns(String address) {
        if (tunBuilder == null) return false;
        tunBuilder.addDnsServer(address);
        // DNS Fallback: add system DNS as backup if enabled
        try {
            boolean fallback = getSharedPreferences("devxyz", MODE_PRIVATE)
                .getBoolean("vpn_dns_fallback", true);
            if (fallback) {
                // Add Cloudflare as fallback (widely reliable)
                try { tunBuilder.addDnsServer("1.1.1.1"); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
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
        // Families without a VPN address, route, or DNS entry are blocked by default.
        return true;
    }
    public synchronized int nativeTunEstablish() {
        if (tunBuilder == null) return -1;
        try (ParcelFileDescriptor descriptor = tunBuilder.establish()) {
            tunBuilder = null;
            return descriptor == null ? -1 : descriptor.detachFd();
        } catch (IOException | RuntimeException e) {
            Log.e("DevxyzVpn", "Failed to establish TUN", e);
            tunBuilder = null;
            return -1;
        }
    }
    public synchronized void nativeTunClosed() { tunBuilder = null; }

    @Override public void onRevoke() {
        NativeOpenVpn.stop();
        connected = false;
        userStopped = true;
        publish("DISCONNECTED", "VPN permission revoked", false);
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (nativeStarted) NativeOpenVpn.stop();
        nativeStarted = false;
        connected = false;
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
