#!/usr/bin/env python3
"""Lumen2D — tiny helper for the emulator smoke test.

Reads a `uiautomator dump` XML file and answers the two questions the smoke test
needs: "which texts are on screen?" and "where do I tap to hit this control?".

Usage:
  tools/ui_dump.py texts <dump.xml>              # one text per line (text + content-desc)
  tools/ui_dump.py find  <dump.xml> <label>      # prints "x y" centre of the first match
  tools/ui_dump.py count <dump.xml> <label>      # prints how many nodes match
  tools/ui_dump.py keys  <dump.xml>              # prints the distinct labels, comma separated

Matching is case-insensitive substring on either `text` or `content-desc`.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def labels(root: ET.Element):
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        for value in (text, desc):
            if value:
                yield node, value


def bounds(node: ET.Element) -> tuple[int, int] | None:
    match = BOUNDS.match(node.get("bounds") or "")
    if not match:
        return None
    x1, y1, x2, y2 = (int(g) for g in match.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def load(path: str) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        print(f"error: cannot read {path}: {exc}", file=sys.stderr)
        sys.exit(2)


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    command, path = sys.argv[1], sys.argv[2]
    needle = sys.argv[3].lower() if len(sys.argv) > 3 else ""
    root = load(path)

    if command == "texts":
        for _node, value in labels(root):
            print(value)
        return 0

    if command == "keys":
        seen = [value for _node, value in labels(root)]
        print(", ".join(dict.fromkeys(seen)))
        return 0

    if command == "count":
        print(sum(1 for _node, value in labels(root) if needle in value.lower()))
        return 0

    if command == "find":
        for node, value in labels(root):
            if needle in value.lower():
                point = bounds(node)
                if point:
                    print(f"{point[0]} {point[1]}")
                    return 0
        return 1

    print(f"unknown command: {command}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
