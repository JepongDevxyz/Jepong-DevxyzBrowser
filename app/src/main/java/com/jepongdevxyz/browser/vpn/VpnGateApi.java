package com.jepongdevxyz.browser.vpn;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Built-in free public VPN servers from the VPNGate public API
 * (http://www.vpngate.net/api/iphone/). Every server entry ships its own
 * base64 OpenVPN config, so tapping a server connects immediately with no
 * account and no .ovpn file needed.
 */
public final class VpnGateApi {
    private static final String TAG = "VpnGateApi";
    private static final String API_URL = "https://www.vpngate.net/api/iphone/";
    private static final String CACHE_FILE = "vpngate_cache.json";
    /** Refresh the list at most this often; otherwise reuse the cache. */
    private static final long CACHE_TTL_MS = 6L * 60L * 60L * 1000L;

    public static final class VpnServer {
        public String hostname = "";
        public String ip = "";
        public String country = "";
        public String countryCode = "";
        public long pingMs;
        public long speedBps;
        public int sessions;
        public String configBase64 = "";

        /** Stable id for favorites: hostname is unique per server. */
        public String id() { return hostname; }

        public String flag() {
            if (countryCode == null || countryCode.length() != 2) return "\uD83C\uDFF3";
            try {
                int a = Character.toUpperCase(countryCode.charAt(0)) - 'A';
                int b = Character.toUpperCase(countryCode.charAt(1)) - 'A';
                if (a < 0 || a > 25 || b < 0 || b > 25) return "\uD83C\uDFF3";
                return new String(Character.toChars(0x1F1E6 + a))
                     + new String(Character.toChars(0x1F1E6 + b));
            } catch (Exception e) {
                return "\uD83C\uDFF3";
            }
        }

        public String speedLabel() {
            double mbps = speedBps / 1_000_000.0;
            if (mbps >= 1000) return String.format(java.util.Locale.ROOT, "%.1f Gbps", mbps / 1000);
            if (mbps >= 1) return String.format(java.util.Locale.ROOT, "%.0f Mbps", mbps);
            return String.format(java.util.Locale.ROOT, "%.0f Kbps", speedBps / 1000.0);
        }
    }

    public interface Callback {
        void onResult(List<VpnServer> servers, boolean fromCache);
        void onError(String message);
    }

    private VpnGateApi() {}

    /** Fetches the server list on a background thread. Falls back to cache when offline. */
    public static void fetch(Context context, boolean forceRefresh, Callback cb) {
        new Thread(() -> {
            try {
                List<VpnServer> cached = readCache(context);
                long cacheAge = cacheAgeMs(context);
                if (!forceRefresh && cached != null && !cached.isEmpty() && cacheAge < CACHE_TTL_MS) {
                    postResult(cb, cached, true, null);
                    return;
                }
                List<VpnServer> fresh = download();
                if (fresh != null && !fresh.isEmpty()) {
                    writeCache(context, fresh);
                    postResult(cb, fresh, false, null);
                } else if (cached != null && !cached.isEmpty()) {
                    postResult(cb, cached, true, null);
                } else {
                    postError(cb, "Could not reach the server list. Check your connection and try again.");
                }
            } catch (Exception e) {
                Log.w(TAG, "fetch failed", e);
                List<VpnServer> cached = readCache(context);
                if (cached != null && !cached.isEmpty()) postResult(cb, cached, true, null);
                else postError(cb, "Could not reach the server list. Check your connection and try again.");
            }
        }).start();
    }

    private static void postResult(Callback cb, List<VpnServer> s, boolean cached, String err) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onResult(s, cached));
    }

    private static void postError(Callback cb, String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onError(msg));
    }

    private static List<VpnServer> download() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_URL).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "DevxyzBrowser");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } finally {
            c.disconnect();
        }
        return parse(sb.toString());
    }

    /** Parses the VPNGate iphone CSV. First line "*vpn_servers", second "#"-header, then rows. */
    static List<VpnServer> parse(String csv) {
        List<VpnServer> out = new ArrayList<>();
        if (csv == null) return out;
        String[] lines = csv.split("\n");
        for (int i = 2; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("*")) continue;
            // 15 columns; the base64 config is last and never contains a comma.
            String[] cols = line.split(",", 15);
            if (cols.length < 15) continue;
            try {
                VpnServer s = new VpnServer();
                s.hostname = cols[0].trim();
                s.ip = cols[1].trim();
                s.pingMs = parseLong(cols[3]);
                s.speedBps = parseLong(cols[4]);
                s.country = cols[5].trim();
                s.countryCode = cols[6].trim();
                s.sessions = (int) parseLong(cols[7]);
                s.configBase64 = cols[14].trim();
                if (s.hostname.isEmpty() || s.configBase64.length() < 100) continue;
                out.add(s);
            } catch (Exception ignored) { }
        }
        Collections.sort(out, Comparator.comparingLong(a -> a.pingMs));
        return out;
    }

    private static long parseLong(String v) {
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return 0; }
    }

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), CACHE_FILE);
    }

    private static long cacheAgeMs(Context context) {
        File f = cacheFile(context);
        if (!f.exists()) return Long.MAX_VALUE;
        return System.currentTimeMillis() - f.lastModified();
    }

    private static List<VpnServer> readCache(Context context) {
        try {
            File f = cacheFile(context);
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            List<VpnServer> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                VpnServer s = new VpnServer();
                s.hostname = o.optString("h");
                s.ip = o.optString("ip");
                s.country = o.optString("c");
                s.countryCode = o.optString("cc");
                s.pingMs = o.optLong("p");
                s.speedBps = o.optLong("s");
                s.sessions = o.optInt("n");
                s.configBase64 = o.optString("cfg");
                if (!s.hostname.isEmpty() && !s.configBase64.isEmpty()) out.add(s);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context context, List<VpnServer> servers) {
        try {
            JSONArray arr = new JSONArray();
            for (VpnServer s : servers) {
                JSONObject o = new JSONObject();
                o.put("h", s.hostname);
                o.put("ip", s.ip);
                o.put("c", s.country);
                o.put("cc", s.countryCode);
                o.put("p", s.pingMs);
                o.put("s", s.speedBps);
                o.put("n", s.sessions);
                o.put("cfg", s.configBase64);
                arr.put(o);
            }
            try (FileWriter w = new FileWriter(cacheFile(context))) {
                w.write(arr.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "writeCache failed", e);
        }
    }
}
