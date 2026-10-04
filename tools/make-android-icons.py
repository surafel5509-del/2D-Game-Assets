#!/usr/bin/env python3
"""Generate the Lumen2D Studio launcher icons.

Pixel-art mark: a dark rounded tile with a mint/blue "L2D" glyph drawn from an 8x8 bitmask, scaled
with nearest neighbour to every density. Written by hand (zlib + PNG chunks) so the repo needs no
image library, and the icons stay reproducible from source.

Usage: tools/make-android-icons.py <res-dir>
"""
import os
import struct
import sys
import zlib

BG = (0x0C, 0x0F, 0x19, 0xFF)
TILE = (0x14, 0x1A, 0x28, 0xFF)
MINT = (0x7C, 0xF7, 0xC4, 0xFF)
ACCENT = (0x6C, 0x8C, 0xFF, 0xFF)

# 16x16 bitmask: 0 empty, 1 accent, 2 mint. "L", "2" and a "D"-ish diamond.
GLYPH = [
    "................",
    "................",
    "..1111..22222...",
    "..1.....2...2...",
    "..1.........2...",
    "..1.......22....",
    "..1.....22......",
    "..1...22........",
    "..1...2222222...",
    "..1.....2...2...",
    "..1111..22222...",
    "................",
    "................",
    "................",
    "................",
    "................",
]


def pixels(size):
    rows = []
    scale = size / 16.0
    for y in range(size):
        row = []
        gy = int(y / scale)
        for x in range(size):
            gx = int(x / scale)
            # rounded-ish tile background
            edge = min(x, y, size - 1 - x, size - 1 - y)
            base = TILE if edge > size * 0.08 else BG
            value = GLYPH[gy][gx] if gy < len(GLYPH) and gx < len(GLYPH[0]) else "."
            row.append(ACCENT if value == "1" else MINT if value == "2" else base)
        rows.append(row)
    return rows


def write_png(path, rows):
    size = len(rows)
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *px) for px in row) for row in rows)
    chunk = lambda tag, data: struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(png)


def main():
    res = sys.argv[1] if len(sys.argv) > 1 else "app-android/src/main/res"
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        write_png(os.path.join(res, "mipmap-%s" % density, "ic_launcher.png"), pixels(size))
    # Round variant for launchers that ask for it.
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        write_png(os.path.join(res, "mipmap-%s" % density, "ic_launcher_round.png"), pixels(size))
    print("wrote launcher icons into %s" % res)


if __name__ == "__main__":
    main()
