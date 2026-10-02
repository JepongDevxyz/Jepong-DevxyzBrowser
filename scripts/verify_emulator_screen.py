#!/usr/bin/env python3
"""Reject Android system ANR dialogs from an emulator accessibility dump."""
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
tree = ET.parse(path)
labels = " ".join(
    " ".join(node.attrib.get(key, "") for key in ("text", "content-desc"))
    for node in tree.iter()
).casefold()
if any(marker in labels for marker in ("isn't responding", "close app", "wait")):
    raise SystemExit(f"Android ANR dialog found in {path}")
print(f"No Android ANR dialog in {path}")
