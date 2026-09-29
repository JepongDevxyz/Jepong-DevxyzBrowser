#!/usr/bin/env python3
"""Static checks for the reference-matched browser start screen."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"
BACKDROP = ROOT / "app/src/main/res/drawable-sw600dp/home_backdrop.xml"
ACTIVITY = ROOT / "app/src/main/java/com/jepongdevxyz/browser/MainActivity.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
WORKFLOW = ROOT / ".github/workflows/android.yml"
BUILD = ROOT / "app/build.gradle.kts"
ANDROID = "{http://schemas.android.com/apk/res/android}"

layout = ET.parse(LAYOUT).getroot()
backdrop = ET.parse(BACKDROP).getroot()
activity = ACTIVITY.read_text()
manifest = ET.parse(MANIFEST).getroot()
workflow = WORKFLOW.read_text()
build = BUILD.read_text()
ids = {
    node.attrib.get(ANDROID + "id", "").split("/")[-1]: node
    for node in layout.iter()
    if node.attrib.get(ANDROID + "id")
}
missing_ids = sorted(set(re.findall(r"R\.id\.([A-Za-z0-9_]+)", activity)) - set(ids) - {"content"})
assert not missing_ids, f"Java refers to missing layout IDs: {missing_ids}"

assert (ROOT / "app/src/main/res/drawable-nodpi/home_mountains.jpg").is_file(), "scenic background asset missing"
assert "@drawable/home_mountains" in ET.tostring(backdrop, encoding="unicode"), "home backdrop does not use scenic image"
assert any(node.attrib.get(ANDROID + "text") == "DevxyzBrowser" for node in layout.iter()), "brand headline missing"
assert any(node.attrib.get(ANDROID + "text") == "Browse Freely. Securely. Your Way." for node in layout.iter()), "reference tagline missing"

# Geometry checks tie the native layout to the supplied desktop reference.
desktop_panels = ids.get("desktopPanels")
assert desktop_panels is not None and desktop_panels.attrib.get(ANDROID + "orientation") == "horizontal", "desktop VPN and Extensions must be side-by-side"
assert desktop_panels.attrib.get(ANDROID + "layout_width") == "600dp", "desktop panels should match the reference right-column width"
assert ids.get("desktopVpnPanel") is not None and ids["desktopVpnPanel"].attrib.get(ANDROID + "layout_width") == "280dp", "VPN panel width differs from reference"
assert ids.get("desktopExtensionsPane") is not None and ids["desktopExtensionsPane"].attrib.get(ANDROID + "layout_width") == "282dp", "Extensions panel width differs from reference"
assert (ROOT / "app/src/main/res/drawable-nodpi/vpn_world_power.png").is_file(), "reference VPN map/power artwork missing"
assert "@drawable/vpn_world_power" in ET.tostring(layout, encoding="unicode"), "VPN reference artwork not used"
for view_id in ("desktopVpnMap", "desktopVpnAction", "desktopVpnStatus", "vpnProfileSelector", "autoConnect", "killSwitchSettings", "blockTrackers", "extensionSearch", "desktopExtensionRows"):
    assert view_id in ids, f"missing reference panel element {view_id}"
assert ids.get("heroTitle") is not None and "Browse Freely." in ids["heroTitle"].attrib.get(ANDROID + "text", ""), "reference home hero headline missing"
assert (ROOT / "app/src/main/res/drawable-sw600dp/home_backdrop.xml").is_file(), "desktop scenic background variant missing"
for addon_slug in ("ublock-origin", "darkreader", "sponsorblock"):
    assert addon_slug in activity, f"official Android add-on install action missing for {addon_slug}"
assert "WebExtensionController.EnableSource.USER" in activity, "installed add-ons need functional user enable/disable actions"
assert "GeckoSessionSettings" in activity and "setUseTrackingProtection" in activity, "Block Trackers must control Gecko tracking protection"
assert "Settings.ACTION_VPN_SETTINGS" in activity, "Kill Switch control must open Android VPN settings"
on_create = activity.split("@Override public void onCreate(Bundle state)", 1)[1].split("\n  private ", 1)[0]
assert "GeckoRuntime.create(this)" not in on_create, "Gecko startup must not block the first home-screen frame"
assert "session.open(runtime)" not in on_create, "browser sessions must be opened lazily after the home screen is ready"
assert "ensureBrowserSession" in activity.split("private void browse(", 1)[1].split("\n  private ", 1)[0], "navigating to a site must start Gecko on demand"
lazy_session = activity.split("private GeckoSession ensureBrowserSession(", 1)[1].split("\n  private ", 1)[0]
assert "ensureBrowserRuntime()" in lazy_session and "tab.session.open(runtime)" in lazy_session, "first navigation must create and open its Gecko session"
assert "GeckoRuntime.create(getApplicationContext())" in activity.split("private void ensureBrowserRuntime()", 1)[1].split("\n  private ", 1)[0], "lazy runtime must be process-scoped"

shortcut_ids = ("siteYoutube", "siteFacebook", "siteGithub", "siteReddit", "siteX", "addSite")
for view_id in shortcut_ids:
    assert view_id in ids, f"missing shortcut {view_id}"
    assert f"R.id.{view_id}" in activity, f"shortcut {view_id} has no Java handler"
    assert ids[view_id].attrib.get(ANDROID + "layout_width") == "58dp", f"mobile shortcut {view_id} icon width differs from reference"
    assert ids[view_id].attrib.get(ANDROID + "layout_height") == "58dp", f"mobile shortcut {view_id} icon height differs from reference"

for view_id in ("vpnPanel", "extensionsPanel", "desktopVpnAction", "desktopExtensionsAction"):
    assert view_id in ids, f"missing action {view_id}"
    assert f"R.id.{view_id}" in activity, f"action {view_id} has no Java handler"

application = manifest.find("application")
assert application is not None and application.attrib.get(ANDROID + "icon") == "@mipmap/ic_launcher", "custom installer icon not wired"
assert "killSwitch" not in ids, "do not present a fake in-app kill switch; Android controls Always-on VPN"

assert "api-level: 30" in workflow and "arch: x86_64" in workflow, "visual review emulator must use the runner's supported ABI"
assert "dumpsys window" in workflow and "com.jepongdevxyz.browser" in workflow, "visual review must verify the browser is foregrounded"
assert workflow.count("uiautomator dump") >= 2 and workflow.count("am force-stop com.jepongdevxyz.browser") == 1, "desktop and phone screenshots must reject ANR overlays and restart at phone dimensions"
assert "scripts/verify_emulator_screen.py" in workflow, "emulator screenshot flow must use the tested ANR verifier"
assert "DevxyzBrowser-visual-review-only" in workflow, "verification workflow should publish screenshots only"
assert "name: DevxyzBrowser-debug-apk" not in workflow, "verification workflow must not expose an unreviewed APK"
assert 'providers.gradleProperty("devxyz.abi")' in build, "APK must select one target ABI to avoid packaging all GeckoView binaries"
assert 'abiFilters += browserAbi' in build, "selected GeckoView ABI must be enforced in the APK"
assert "-Pdevxyz.abi=x86_64" in workflow, "screenshot emulator build must use its native x86_64 GeckoView ABI"

print("Reference UI static checks passed")
