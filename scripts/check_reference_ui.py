#!/usr/bin/env python3
"""Static checks for the reference-matched browser start screen."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"
BACKDROP = ROOT / "app/src/main/res/drawable/home_backdrop.xml"
ACTIVITY = ROOT / "app/src/main/java/com/jepongdevxyz/browser/MainActivity.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
WORKFLOW = ROOT / ".github/workflows/android.yml"
ANDROID = "{http://schemas.android.com/apk/res/android}"

layout = ET.parse(LAYOUT).getroot()
backdrop = ET.parse(BACKDROP).getroot()
activity = ACTIVITY.read_text()
manifest = ET.parse(MANIFEST).getroot()
workflow = WORKFLOW.read_text()
ids = {
    node.attrib.get(ANDROID + "id", "").split("/")[-1]: node
    for node in layout.iter()
    if node.attrib.get(ANDROID + "id")
}

assert (ROOT / "app/src/main/res/drawable-nodpi/home_mountains.jpg").is_file(), "scenic background asset missing"
assert "@drawable/home_mountains" in ET.tostring(backdrop, encoding="unicode"), "home backdrop does not use scenic image"
assert any(node.attrib.get(ANDROID + "text") == "DevxyzBrowser" for node in layout.iter()), "brand headline missing"
assert any(node.attrib.get(ANDROID + "text") == "Browse Freely. Securely. Your Way." for node in layout.iter()), "reference tagline missing"

# Geometry checks tie the native layout to the supplied desktop reference.
desktop_panels = ids.get("desktopPanels")
assert desktop_panels is not None and desktop_panels.attrib.get(ANDROID + "orientation") == "horizontal", "desktop VPN and Extensions must be side-by-side"
assert desktop_panels.attrib.get(ANDROID + "layout_width") == "568dp", "desktop panels should match the reference right-column width"
assert ids.get("desktopVpnPanel") is not None and ids["desktopVpnPanel"].attrib.get(ANDROID + "layout_width") == "276dp", "VPN panel width differs from reference"
assert ids.get("desktopExtensionsPane") is not None and ids["desktopExtensionsPane"].attrib.get(ANDROID + "layout_width") == "276dp", "Extensions panel width differs from reference"
assert (ROOT / "app/src/main/res/drawable-nodpi/vpn_world_power.png").is_file(), "reference VPN map/power artwork missing"
assert "@drawable/vpn_world_power" in ET.tostring(layout, encoding="unicode"), "VPN reference artwork not used"
for view_id in ("desktopVpnMap", "desktopVpnAction", "extensionSummary"):
    assert view_id in ids, f"missing reference panel element {view_id}"

shortcut_ids = ("siteYoutube", "siteFacebook", "siteGithub", "siteReddit", "siteX", "addSite")
for view_id in shortcut_ids:
    assert view_id in ids, f"missing shortcut {view_id}"
    assert f"R.id.{view_id}" in activity, f"shortcut {view_id} has no Java handler"
    assert ids[view_id].attrib.get(ANDROID + "layout_width") == "72dp", f"shortcut {view_id} icon width differs from reference"
    assert ids[view_id].attrib.get(ANDROID + "layout_height") == "76dp", f"shortcut {view_id} icon height differs from reference"

for view_id in ("vpnPanel", "extensionsPanel", "desktopVpnAction", "desktopExtensionsAction"):
    assert view_id in ids, f"missing action {view_id}"
    assert f"R.id.{view_id}" in activity, f"action {view_id} has no Java handler"

application = manifest.find("application")
assert application is not None and application.attrib.get(ANDROID + "icon") == "@mipmap/ic_launcher", "custom installer icon not wired"
for fake_toggle in ("autoConnect", "killSwitch", "blockTrackers"):
    assert fake_toggle not in ids, f"remove nonfunctional VPN toggle {fake_toggle}"

assert "api-level: 30" in workflow and "arch: x86_64" in workflow, "visual review emulator must use the runner's supported ABI"
assert "dumpsys window" in workflow and "com.jepongdevxyz.browser" in workflow, "visual review must verify the browser is foregrounded"
assert "DevxyzBrowser-visual-review-only" in workflow, "verification workflow should publish screenshots only"
assert "name: DevxyzBrowser-debug-apk" not in workflow, "verification workflow must not expose an unreviewed APK"

print("Reference UI static checks passed")
