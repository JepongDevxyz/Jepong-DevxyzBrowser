# DevxyzBrowser

Native Android browser for **com.jepongdevxyz.browser**.

Architecture:
- GeckoView browser engine with real WebExtension support.
- OpenVPN 3 is built for Android ARM64 and connected through JNI to Android `VpnService`. The service protects the transport socket, transfers the Android TUN file descriptor to OpenVPN, and reports connected only for the core's `CONNECTED` event. Import profiles with inline certificates and keys; profiles that reference external files, run local scripts, use external-PKI callbacks, or configure a proxy are unsupported.
- Dark navy / purple responsive UI based on the supplied DevxyzBrowser reference.
- GitHub Actions builds and uploads the APK.

> Security: DevxyzBrowser never ships a shared/private VPN credential. Users import their own .ovpn profile or a provider-issued profile.

## Build
`gradle :app:assembleDebug`

The CI workflow builds the pinned OpenVPN 3 core and JNI bridge for `arm64-v8a`, packages them with the Android C++ runtime, runs lint and APK package/signature verification, and uploads the debug APK. The VPN connection still needs to be tested on an Android device with a valid provider profile.


## OpenVPN core licensing

The embedded client core is OpenVPN 3, whose client API is dual-licensed under MPL-2.0 or AGPL-3.0-only with the OpenVPN 3 OpenSSL exception. The workflow packages upstream license texts and dependency notices into the APK assets. DevxyzBrowser does not treat a VpnService permission grant as a successful VPN connection.


### Pinned OpenVPN 3 upstream

CI pins the official OpenVPN/openvpn3 source revision `2986b58108726a7aff278a50caa0cff9c4cdf943`. The JNI adapter implements the OpenVPN client callbacks and maps tunnel addresses, routes, DNS, MTU, socket protection, and TUN establishment to Android's `VpnService` APIs.
