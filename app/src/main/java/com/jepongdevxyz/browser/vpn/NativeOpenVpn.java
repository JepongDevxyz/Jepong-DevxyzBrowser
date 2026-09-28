package com.jepongdevxyz.browser.vpn;

/** JNI entry points backed by the pinned OpenVPN 3 client core. */
final class NativeOpenVpn {
    static {
        System.loadLibrary("openvpn3_core");
    }

    private NativeOpenVpn() {}

    static native boolean start(DevxyzVpnService service, String profile,
                                String username, String password, String privateKeyPassword);
    static native void stop();
}
