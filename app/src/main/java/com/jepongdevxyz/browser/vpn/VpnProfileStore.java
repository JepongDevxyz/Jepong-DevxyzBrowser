package com.jepongdevxyz.browser.vpn;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class VpnProfileStore {
    private static final String FILE = "active-profile.ovpn";
    private VpnProfileStore() {}

    public static String importProfile(Context context, Uri uri) throws IOException {
        String config;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Unable to open profile");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 2 * 1024 * 1024) throw new IOException("Profile is too large");
                out.write(buf, 0, n);
            }
            config = out.toString(StandardCharsets.UTF_8.name());
        }
        validate(config);
        try (FileOutputStream out = context.openFileOutput(FILE, Context.MODE_PRIVATE)) {
            out.write(config.getBytes(StandardCharsets.UTF_8));
        }
        return endpoint(config);
    }

    public static boolean hasProfile(Context context) {
        return new File(context.getFilesDir(), FILE).isFile();
    }

    public static String read(Context context) throws IOException {
        File file = new File(context.getFilesDir(), FILE);
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off=0,n;
            while(off<data.length && (n=in.read(data,off,data.length-off))!=-1) off+=n;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    public static boolean requiresCredentials(Context context) throws IOException {
        String profile = read(context);
        for (String line : profile.split("\\r?\\n")) {
            String s = line.trim();
            if (s.startsWith("<") || s.startsWith("#") || s.startsWith(";")) continue;
            String[] parts = s.split("\\s+", 2);
            if (parts.length == 0) continue;
            if (parts[0].equalsIgnoreCase("askpass") || parts[0].equalsIgnoreCase("auth-user-pass")) return true;
        }
        return false;
    }

    private static void validate(String c) throws IOException {
        String lower=c.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("client") || !lower.contains("remote "))
            throw new IOException("Not a valid OpenVPN client profile");
        if (lower.contains("script-security") || lower.contains("up ") || lower.contains("down "))
            throw new IOException("Profiles containing local script hooks are not accepted");
        boolean inline = false;
        for (String line : c.split("\\r?\\n")) {
            String s = line.trim();
            String sl = s.toLowerCase(java.util.Locale.ROOT);
            if (sl.startsWith("<") && sl.endsWith(">")) {
                String tag = sl.replaceFirst("^</?", "").replaceFirst(">$", "").trim();
                boolean inlineDataTag = java.util.Arrays.asList("ca", "cert", "key", "tls-auth", "tls-crypt",
                        "tls-crypt-v2", "pkcs12", "dh", "extra-certs", "crl-verify", "auth-user-pass").contains(tag);
                if (inlineDataTag) inline = !sl.startsWith("</");
                continue;
            }
            if (inline || s.isEmpty() || s.startsWith("#") || s.startsWith(";")) continue;
            String[] parts = s.split("\\s+", 2);
            if (parts.length < 2) continue;
            String option = parts[0].toLowerCase(java.util.Locale.ROOT);
            if ((option.equals("ca") || option.equals("cert") || option.equals("key")
                    || option.equals("tls-auth") || option.equals("tls-crypt")
                    || option.equals("crl-verify") || option.equals("auth-user-pass"))
                    && !parts[1].trim().startsWith("#")) {
                throw new IOException("This profile references external files. Import a profile with inline certificates and keys.");
            }
        }
    }

    private static String endpoint(String c) {
        for(String line:c.split("\\r?\\n")) {
            String s=line.trim();
            if(s.toLowerCase(java.util.Locale.ROOT).startsWith("remote ")) return s.substring(7).trim();
        }
        return "OpenVPN profile";
    }
}
