#!/usr/bin/env python3
"""Lumen2D — tiny helper for the emulator smoke test.

Reads a `uiautomator dump` XML file and answers the two questions the smoke test
needs: "which texts are on screen?" and "where do I tap to hit this control?".

Usage:
  tools/ui_dump.py texts <dump.xml>              # one text per line (text + content-desc)
  tools/ui_dump.py find  <dump.xml> <label>      # prints "x y" centre of the first match
  tools/ui_dump.py count <dump.xml> <label>      # prints how many nodes match
  tools/ui_dump.py keys  <dump.xml>              # prints the distinct labels, comma separated

Matching is case-insensitive substring on either `text` or `content-desc`; `find` prefers an
exact, clickable, on-screen match so that "Play" selects the button and not a game title
such as "Pixel Platformer".
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


def rect(node: ET.Element) -> tuple[int, int, int, int] | None:
    match = BOUNDS.match(node.get("bounds") or "")
    if not match:
        return None
    x1, y1, x2, y2 = (int(g) for g in match.groups())
    return (x1, y1, x2, y2)


def screen(root: ET.Element) -> tuple[int, int, int, int] | None:
    """The largest node rectangle — uiautomator's hierarchy root is the whole display."""
    rects = [r for r in (rect(n) for n in root.iter("node")) if r and r[2] > r[0] and r[3] > r[1]]
    return max(rects, key=lambda r: (r[2] - r[0]) * (r[3] - r[1])) if rects else None


def clickable(node: ET.Element) -> bool:
    return (node.get("clickable") or "").lower() == "true"


def on_screen(node: ET.Element, view: tuple[int, int, int, int] | None) -> bool:
    box = rect(node)
    if box is None or view is None:
        return box is not None
    return box[0] >= view[0] and box[1] >= view[1] and box[2] <= view[2] and box[3] <= view[3]


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
        # A tap target has to be the *right* node: "Play" is a button, but "Pixel Platformer" also
        # contains those four letters, and a node inside a scrolled-away row cannot be tapped at
        # all. Prefer, in order: an exact label that is clickable and on screen, then exact, then
        # any clickable on-screen match, then anything at all.
        view = screen(root)
        found = [(node, value) for node, value in labels(root) if needle in value.lower()]
        exact = [(node, value) for node, value in found if value.strip().lower() == needle]
        for pool in (
            [c for c in exact if clickable(c[0]) and on_screen(c[0], view)],
            [c for c in exact if on_screen(c[0], view)],
            exact,
            [c for c in found if clickable(c[0]) and on_screen(c[0], view)],
            [c for c in found if on_screen(c[0], view)],
            found,
        ):
            for node, value in pool:
                point = bounds(node)
                if point:
                    print(f"{point[0]} {point[1]}")
                    return 0
        return 1

    print(f"unknown command: {command}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
