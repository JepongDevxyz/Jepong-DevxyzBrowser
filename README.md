# DevxyzBrowser

Native Android browser for **com.jepongdevxyz.browser**.

Architecture:
- GeckoView browser engine with real WebExtension support.
- OpenVPN support is being integrated against the OpenVPN 3 client core. The app currently has profile import/validation and Android VpnService permission/lifecycle wiring; it does not report a connection until a real native tunnel is established.
- Dark navy / purple responsive UI based on the supplied DevxyzBrowser reference.
- GitHub Actions builds and uploads the APK.

> Security: DevxyzBrowser never ships a shared/private VPN credential. Users import their own .ovpn profile or a provider-issued profile.

## Build
`gradle :app:assembleDebug`

The CI workflow performs the same build and runs lint/tests before publishing artifacts.


## OpenVPN core licensing

The planned embedded client core is OpenVPN 3, whose client API is dual-licensed under MPL-2.0 or AGPL-3.0-only with the OpenVPN 3 OpenSSL exception. Third-party notices and the exact pinned upstream revision must ship with releases that include the native core. DevxyzBrowser does not treat a VpnService permission grant as a successful VPN connection.


### Pinned OpenVPN 3 upstream

CI currently pins the official OpenVPN/openvpn3 source revision `2986b58108726a7aff278a50caa0cff9c4cdf943` (2026-09-22). CI verifies the expected OpenVPNClient/TunBuilder API before the Android build. This pin establishes reproducible upstream provenance; native JNI/SWIG compilation is tracked separately and must pass before DevxyzBrowser reports OpenVPN as connected.
