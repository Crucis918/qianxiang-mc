#!/usr/bin/env python3
"""
武器模板渲染预览：直接从 DynamicWeaponTexture.java 提取字符画模板，
按渲染管线（对角渐变 / 白热刃尖 / 宝石闪光 / 金边 / 核心材料族着色）的简化移植版
渲染成 8 倍放大 PNG，供目检（脚本输出到 scripts/out/weapon_*.png）。

用法：python3 scripts/preview_weapon_templates.py
"""

import re
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "src/main/java/com/qianxiang/client/DynamicWeaponTexture.java"
OUT = ROOT / "scripts/out"
SIZE = 16
SCALE = 8

def abgr(r, g, b, a=255):
    return (r, g, b, a)


SHAPES = ["sword", "greatsword", "dagger", "katana", "spear", "axe",
          "hammer", "scythe", "mace", "staff", "book"]

# 预览用效果色板（ignite：刃缘橙红 / 亮橙）
PALETTE_MAIN = abgr(0xE0, 0x5A, 0x1E)
PALETTE_BRIGHT = abgr(0xFF, 0xB0, 0x60)
PALETTE_NONE_MAIN = abgr(0xA8, 0xAE, 0xB6)
# 核心材料族刃体色（预览默认 metal 钢灰，书面走效果暗色）
FAMILY_BODY = abgr(0x9A, 0xA0, 0xA8)
HANDLE = abgr(0x6B, 0x4A, 0x2F)
WRAP = abgr(0x3E, 0x2A, 0x1A)
BOOK_COVER_DARK = abgr(0x7A, 0x2E, 0x0E)
TIER_GOLD = abgr(0xD8, 0xB2, 0x4A)




def shade(color, factor):
    r, g, b, a = color
    return (min(255, int(r * factor)), min(255, int(g * factor)), min(255, int(b * factor)), a)


def lerp_color(c1, c2, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)) + (c1[3],)


def gradient(color, x, y):
    t = (x + y) / (2.0 * (SIZE - 1))
    return shade(color, 1.22 - 0.5 * t)


def edge_hot(main, x, y):
    t = (x + (SIZE - 1 - y)) / (2.0 * (SIZE - 1))
    if t < 0.72:
        return main
    return lerp_color(main, (0xFF, 0xF6, 0xE2), (t - 0.72) / 0.28)


def extract_templates():
    """从 java 源里抠出 private static final String[] NAME = { ... } 的字符串行。"""
    text = JAVA.read_text(encoding="utf-8")
    out = {}
    for m in re.finditer(r'private static final String\[\] (\w+) = \{(.*?)\};', text, re.S):
        name = m.group(1).lower()
        rows = re.findall(r'"((?:\\.|[^"\\])*)"', m.group(2))
        rows = [(r + "." * SIZE)[:SIZE] for r in rows]
        out[name] = rows
    return out


def role_color(c, shape, x, y):
    base = BOOK_COVER_DARK if shape == "book" else FAMILY_BODY
    if c == 'B':
        return gradient(base, x, y)
    if c == 'b':
        return gradient(shade(base, 0.62), x, y)
    if c == 'E':
        return edge_hot(PALETTE_MAIN, x, y)
    if c == 'G':
        return PALETTE_BRIGHT
    if c == 'R':
        return PALETTE_BRIGHT
    if c == 'T':
        return TIER_GOLD
    if c == 'H':
        return HANDLE
    if c == 'W':
        return WRAP
    return None


def render(name, rows):
    img = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (0, 0, 0, 0))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            c = rows[y][x] if x < len(rows[y]) else '.'
            color = role_color(c, name, x, y)
            if color is None:
                continue
            for dy in range(SCALE):
                for dx in range(SCALE):
                    px[x * SCALE + dx, y * SCALE + dy] = color
    return img


def main():
    templates = extract_templates()
    OUT.mkdir(parents=True, exist_ok=True)
    missing = [s for s in SHAPES if s not in templates]
    if missing:
        raise SystemExit(f"模板未提取到: {missing}")
    for shape in SHAPES:
        img = render(shape, templates[shape])
        path = OUT / f"weapon_{shape}.png"
        img.save(path)
        print(f"rendered {path}")


if __name__ == "__main__":
    main()
