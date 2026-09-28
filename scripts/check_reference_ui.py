#!/usr/bin/env python3
"""Static checks for the reference-matched browser start screen."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"
BACKDROP = ROOT / "app/src/main/res/drawable/home_backdrop.xml"
ACTIVITY = ROOT / "app/src/main/java/com/jepongdevxyz/browser/MainActivity.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"

layout = ET.parse(LAYOUT).getroot()
backdrop = ET.parse(BACKDROP).getroot()
activity = ACTIVITY.read_text()
manifest = ET.parse(MANIFEST).getroot()
ids = {
    node.attrib.get(ANDROID + "id", "").split("/")[-1]: node
    for node in layout.iter()
    if node.attrib.get(ANDROID + "id")
}

assert (ROOT / "app/src/main/res/drawable-nodpi/home_mountains.jpg").is_file(), "scenic background asset missing"
assert "@drawable/home_mountains" in ET.tostring(backdrop, encoding="unicode"), "home backdrop does not use scenic image"
assert any(node.attrib.get(ANDROID + "text") == "DevxyzBrowser" for node in layout.iter()), "brand headline missing"
assert any(node.attrib.get(ANDROID + "text") == "Browse Freely. Securely. Your Way." for node in layout.iter()), "reference tagline missing"

shortcut_ids = ("siteYoutube", "siteFacebook", "siteGithub", "siteReddit", "siteX", "addSite")
for view_id in shortcut_ids:
    assert view_id in ids, f"missing shortcut {view_id}"
    assert f"R.id.{view_id}" in activity, f"shortcut {view_id} has no Java handler"

for view_id in ("vpnPanel", "extensionsPanel", "desktopVpnAction", "desktopExtensionsAction"):
    assert view_id in ids, f"missing action {view_id}"
    assert f"R.id.{view_id}" in activity, f"action {view_id} has no Java handler"

application = manifest.find("application")
assert application is not None and application.attrib.get(ANDROID + "icon") == "@mipmap/ic_launcher", "custom installer icon not wired"
for fake_toggle in ("autoConnect", "killSwitch", "blockTrackers"):
    assert fake_toggle not in ids, f"remove nonfunctional VPN toggle {fake_toggle}"

print("Reference UI static checks passed")
