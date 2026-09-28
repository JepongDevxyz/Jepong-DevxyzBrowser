# DevxyzBrowser

Native Android browser for **com.jepongdevxyz.browser**.

Architecture:
- GeckoView browser engine with real WebExtension support.
- OpenVPN engine integration is pinned from the GPL-2.0 `schwabe/ics-openvpn` source tree during CI; this repository remains public to comply with the upstream license.
- Dark navy / purple responsive UI based on the supplied DevxyzBrowser reference.
- GitHub Actions builds and uploads the APK.

> Security: DevxyzBrowser never ships a shared/private VPN credential. Users import their own .ovpn profile or a provider-issued profile.

## Build
`gradle :app:assembleDebug`

The CI workflow performs the same build and runs lint/tests before publishing artifacts.
