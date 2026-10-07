#!/usr/bin/env python3
"""Check the manifest-discovered ML Kit constructor contract after R8.

Class-name keep rules alone did not preserve the public zero-argument
constructors. The broken release logged NoSuchMethodException and had no
text-recognizer components although its unminified debug APK worked.
"""
import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def check(manifest: Path, mapping: Path) -> list[str]:
    android = "{http://schemas.android.com/apk/res/android}"
    names = []
    for node in ET.parse(manifest).iter("meta-data"):
        name = node.get(android + "name", "")
        if (name.startswith("com.google.firebase.components:com.google.mlkit.")
                and node.get(android + "value") == "com.google.firebase.components.ComponentRegistrar"):
            names.append(name.split(":", 1)[1])
    if not names:
        raise ValueError("No bundled ML Kit registrars in the merged release manifest")
    blocks = {}
    current = None
    for line in mapping.read_text(encoding="utf-8").splitlines():
        header = re.fullmatch(r"(\S+) -> (\S+):", line)
        if header:
            current = header.group(1)
            blocks[current] = (header.group(2), [])
        elif current and line.startswith(" "):
            blocks[current][1].append(line)
    for name in sorted(set(names)):
        block = blocks.get(name)
        if not block or block[0] != name:
            raise ValueError(f"Manifest registrar was removed or renamed: {name}")
        if not any(re.search(r"\bvoid <init>\(\)", line) for line in block[1]):
            raise ValueError(f"Reflected no-argument constructor was removed: {name}")
    return sorted(set(names))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    args = parser.parse_args()
    try:
        names = check(args.manifest, args.mapping)
    except (ValueError, OSError, ET.ParseError) as error:
        print(f"ML KIT RELEASE FAIL: {error}")
        return 1
    print(f"ML KIT RELEASE PASS: {len(names)} reflected registrar constructors retained")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
