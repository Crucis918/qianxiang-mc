#!/usr/bin/env python3
"""
32×32 武器模板手绘生成器（草稿迭代用）。

用几何原语（贝塞尔刃体 / 多边形 / 圆 / 粗线）设计 13 张模板，
统一走「深色描边 + 清晰剪影 + 分段色阶」三要素：
  o=深描边（自动 8 邻域描边）、D/B/L=刃体暗/中/亮档、
  e/E/X=刃缘暗/中/白热、T/t=亮金/暗金、G=宝石、R=符文、H/h=柄暗/亮、W=缠绕。

输出：
  scripts/out/draft_<shape>.png   预览（金属族 + ignite 色板 + tier1，复刻 roleColor）
  scripts/out/templates.java.txt  Java 数组字面量块，粘贴进 DynamicWeaponTexture.java

用法：python3 scripts/draft_templates.py
"""

import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

SIZE = 32
SCALE = 8
OUT = Path(__file__).resolve().parent / "out"


class Grid:
    def __init__(self):
        self.px = [['.'] * SIZE for _ in range(SIZE)]

    def set(self, x, y, c):
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.px[y][x] = c

    def get(self, x, y):
        return self.px[y][x] if 0 <= x < SIZE and 0 <= y < SIZE else '.'

    def rows(self):
        return [''.join(r) for r in self.px]

    def paint_mask(self, mask, fn):
        """对 mask>0 的像素调用 fn(x, y) -> 字符（返回 None 跳过）。"""
        data = mask.load()
        for y in range(SIZE):
            for x in range(SIZE):
                if data[x, y]:
                    c = fn(x, y)
                    if c:
                        self.px[y][x] = c

    def outline(self):
        """8 邻域自动描边：非空像素旁的空位涂 'o'。"""
        for y in range(SIZE):
            for x in range(SIZE):
                if self.px[y][x] != '.':
                    continue
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        if self.get(x + dx, y + dy) not in '.o':
                            self.px[y][x] = 'o'
                            dy = 2
                            break
                    else:
                        continue
                    break


# ---------------- 遮罩原语 ----------------

def mask_new():
    return Image.new('L', (SIZE, SIZE), 0)


def mask_poly(points):
    img = mask_new()
    ImageDraw.Draw(img).polygon(points, fill=255)
    return img


def mask_disc(cx, cy, r):
    img = mask_new()
    ImageDraw.Draw(img).ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
    return img


def mask_line(p0, p1, w):
    img = mask_new()
    ImageDraw.Draw(img).line([p0, p1], fill=255, width=max(1, round(w)))
    return img


def mask_or(*ms):
    out = ms[0]
    for m in ms[1:]:
        out = ImageChops.lighter(out, m)
    return out


# ---------------- 刃体绘制（贝塞尔中线 + 分段色阶） ----------------

def _bez(p0, pc, p1, u):
    a = (1 - u) * (1 - u)
    b = 2 * (1 - u) * u
    c = u * u
    return (a * p0[0] + b * pc[0] + c * p1[0],
            a * p0[1] + b * pc[1] + c * p1[1])


def paint_blade(g, p_base, p_tip, w_base, w_tip=1.0, curve=0.0,
                edge_side=1, double_edge=False, tip_hot=0.90, ridge_w=0.75):
    """沿贝塞尔中线画刃体：edge_side=+1 表示法线 n 一侧为刃口（E），反侧为背（D）。
    double_edge=True 两侧都开刃（矛头等）。curve 为控制点垂直偏移量（顺 n 方向）。"""
    dx, dy = p_tip[0] - p_base[0], p_tip[1] - p_base[1]
    ln = math.hypot(dx, dy)
    tx, ty = dx / ln, dy / ln
    nx, ny = -ty, tx  # 法线（屏幕坐标 y 向下）
    pc = ((p_base[0] + p_tip[0]) / 2 + nx * curve,
          (p_base[1] + p_tip[1]) / 2 + ny * curve)
    # 每个采样点记录位置、参数 u 与切线（端帽判定用：最近点为端点且轴向越界则剔除，
    # 否则刃体投影会沿轴向无限延伸成贯通画布的长带）。
    samples = []
    for i in range(481):
        u = i / 480
        sx, sy = _bez(p_base, pc, p_tip, u)
        ddx = 2 * (1 - u) * (pc[0] - p_base[0]) + 2 * u * (p_tip[0] - pc[0])
        ddy = 2 * (1 - u) * (pc[1] - p_base[1]) + 2 * u * (p_tip[1] - pc[1])
        dl = math.hypot(ddx, ddy) or 1.0
        samples.append((sx, sy, u, ddx / dl, ddy / dl))

    for y in range(SIZE):
        for x in range(SIZE):
            best, bu = 1e9, 0.0
            bx = by = btx = bty = 0.0
            for sx, sy, u, stx, sty in samples:
                d2 = (x - sx) ** 2 + (y - sy) ** 2
                if d2 < best:
                    best, bu, bx, by, btx, bty = d2, u, sx, sy, stx, sty
            axial = (x - bx) * btx + (y - by) * bty
            if (bu <= 0.002 and axial < -0.6) or (bu >= 0.998 and axial > 0.6):
                continue
            d = (x - bx) * nx + (y - by) * ny  # 有符号法向距离
            w = w_base + (w_tip - w_base) * (bu ** 1.6)
            half = w / 2
            if abs(d) > half:
                continue
            sharp = d * edge_side
            if bu >= 0.965:
                c = 'X'
            elif bu >= tip_hot and sharp > half * 0.2:
                c = 'X'
            elif sharp >= half - 1.15:
                c = 'E'
            elif double_edge and sharp <= -half + 1.15:
                c = 'E'
            elif sharp <= -half + 1.15:
                c = 'D'
            elif ridge_w > 0 and abs(d) <= ridge_w and w >= 3.0:
                c = 'L'
            else:
                c = 'B'
            g.set(x, y, c)


def paint_guard(g, center, axis, length, thick=2.6):
    """垂直于刃轴的护手条：中段 T 亮金，两端与背光侧 t 暗金。"""
    tx, ty = axis
    nx, ny = -ty, tx
    half_l = length / 2
    half_t = thick / 2
    for y in range(SIZE):
        for x in range(SIZE):
            lx = (x - center[0]) * nx + (y - center[1]) * ny
            ly = (x - center[0]) * tx + (y - center[1]) * ty
            if abs(lx) <= half_l and abs(ly) <= half_t:
                # 端头加粗成球状端帽
                cap = half_l - abs(lx) < 1.2
                g.set(x, y, 't' if cap or ly > half_t - 1.0 else 'T')


def paint_grip(g, p0, p1, w, wrap_every=4.0, wrap_w=1.4):
    """柄段：H 主体，受光侧 h 亮档，螺旋缠绕 W。"""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    ln = math.hypot(dx, dy)
    tx, ty = dx / ln, dy / ln
    nx, ny = -ty, tx
    for y in range(SIZE):
        for x in range(SIZE):
            lx = (x - p0[0]) * tx + (y - p0[1]) * ty
            ly = (x - p0[0]) * nx + (y - p0[1]) * ny
            if not (-0.5 <= lx <= ln and abs(ly) <= w / 2):
                continue
            if wrap_every > 0 and (lx % wrap_every) < wrap_w:
                g.set(x, y, 'W')
            elif ly < -w / 2 + 1.1:
                g.set(x, y, 'h')  # 受光侧（法线负向朝左上）
            else:
                g.set(x, y, 'H')


def paint_disc(g, cx, cy, r, rim, body, light=None):
    """圆盘件：外圈 rim，内部 body，左上高光 light（可选）。"""
    for y in range(SIZE):
        for x in range(SIZE):
            d = math.hypot(x - cx, y - cy)
            if d > r:
                continue
            if d > r - 1.15:
                g.set(x, y, rim)
            elif light and (x - cx) + (y - cy) < -r * 0.55:
                g.set(x, y, light)
            else:
                g.set(x, y, body)


def rot_rect(cx, cy, ux, uy, length, width):
    """以 (ux,uy) 为长轴方向的旋转矩形四角点。"""
    nx, ny = -uy, ux
    hl, hw = length / 2, width / 2
    return [(cx + ux * s1 * hl + nx * s2 * hw, cy + uy * s1 * hl + ny * s2 * hw)
            for s1, s2 in ((1, 1), (1, -1), (-1, -1), (-1, 1))]


# ---------------- 13 个形状设计 ----------------

def design_sword():
    g = Grid()
    axis = (math.sqrt(2) / 2, -math.sqrt(2) / 2)
    paint_grip(g, (10.5, 21.5), (4.5, 27.5), 3.2)
    paint_disc(g, 4.0, 28.0, 2.1, 't', 'T')
    paint_guard(g, (10.5, 21.5), axis, 10.0)
    paint_blade(g, (12.0, 20.0), (29.5, 2.5), 5.0, 1.0, edge_side=-1)
    g.outline()
    return g


def design_greatsword():
    g = Grid()
    axis = (math.sqrt(2) / 2, -math.sqrt(2) / 2)
    paint_grip(g, (10.5, 21.5), (3.5, 28.5), 3.4)
    paint_disc(g, 3.0, 29.0, 2.2, 't', 'G')
    paint_guard(g, (10.5, 21.5), axis, 13.0, 3.0)
    paint_blade(g, (12.5, 19.5), (30.0, 2.0), 6.6, 1.2, edge_side=-1)
    g.outline()
    return g


def design_dagger():
    g = Grid()
    axis = (math.sqrt(2) / 2, -math.sqrt(2) / 2)
    paint_grip(g, (11.5, 20.5), (6.5, 25.5), 2.8)
    paint_disc(g, 6.0, 26.0, 1.7, 't', 'T')
    paint_guard(g, (11.5, 20.5), axis, 6.4, 2.2)
    paint_blade(g, (13.0, 19.0), (24.5, 7.5), 4.0, 0.8, edge_side=-1)
    g.outline()
    return g


def design_katana():
    g = Grid()
    axis = (math.sqrt(2) / 2, -math.sqrt(2) / 2)
    paint_grip(g, (10.5, 21.5), (4.5, 27.5), 3.0, wrap_every=3.4)
    paint_disc(g, 10.5, 21.5, 2.5, 't', 'T')  # 圆形锷
    paint_blade(g, (11.5, 20.0), (27.5, 2.5), 3.4, 0.9, curve=2.6, edge_side=-1)
    g.outline()
    return g


def design_spear():
    g = Grid()
    paint_grip(g, (3.5, 28.5), (22.5, 9.5), 2.6, wrap_every=6.0)
    paint_guard(g, (22.5, 9.5), (math.sqrt(2) / 2, -math.sqrt(2) / 2), 4.6, 2.4)
    paint_blade(g, (24.0, 8.0), (30.5, 1.5), 5.0, 0.8, double_edge=True)
    g.outline()
    return g


def design_axe():
    g = Grid()
    paint_grip(g, (5.5, 27.5), (19.5, 11.5), 3.2)
    head = mask_poly([(16, 5), (25, 4), (30, 9), (29, 15), (23, 18), (18, 15)])
    g.paint_mask(head, lambda x, y: 'B')
    # 刃口弧（外侧）E，上端白热角 X
    g.paint_mask(mask_line((25, 4.5), (29.5, 9.5), 2.0), lambda x, y: 'E')
    g.paint_mask(mask_line((29.5, 9.5), (28.5, 14.5), 2.0), lambda x, y: 'E')
    g.set(29, 7, 'X')
    g.set(28, 6, 'X')
    # 受光面 L 斜带 + 背光 D 下缘
    g.paint_mask(mask_line((17.5, 6.0), (24.5, 5.0), 1.8), lambda x, y: 'L')
    g.paint_mask(mask_line((21.5, 16.5), (27.0, 14.0), 1.8), lambda x, y: 'D')
    # 头部铆接在柄上的 T 箍
    g.paint_mask(mask_disc(19.0, 12.0, 1.8), lambda x, y: 'T')
    g.outline()
    return g


def design_hammer():
    g = Grid()
    paint_grip(g, (5.5, 27.5), (18.5, 12.5), 3.2)
    s = math.sqrt(2) / 2
    head = mask_poly(rot_rect(22.0, 8.0, s, s, 12.0, 6.4))  # 长轴垂直于柄
    g.paint_mask(head, lambda x, y: 'B')
    # 两打击面 E 带
    g.paint_mask(mask_poly(rot_rect(26.2, 12.2, s, s, 3.2, 6.4)), lambda x, y: 'E')
    g.paint_mask(mask_poly(rot_rect(17.8, 3.8, s, s, 3.2, 6.4)), lambda x, y: 'E')
    # 受光棱 L + 背光棱 D
    g.paint_mask(mask_line((16.5, 6.5), (20.5, 2.5), 1.8), lambda x, y: 'L')
    g.paint_mask(mask_line((23.5, 13.5), (27.5, 9.5), 1.8), lambda x, y: 'D')
    g.paint_mask(mask_disc(22.0, 8.0, 1.7), lambda x, y: 'T')  # 中心铆
    g.outline()
    return g


def design_scythe():
    g = Grid()
    paint_grip(g, (7.5, 28.5), (17.5, 6.5), 3.0, wrap_every=6.0)
    paint_disc(g, 17.5, 6.5, 2.0, 't', 'T')  # 刃座箍
    paint_blade(g, (18.5, 6.0), (27.5, 14.0), 3.6, 0.7, curve=5.0,
                edge_side=-1, ridge_w=0.0)
    g.outline()
    return g


def design_mace():
    g = Grid()
    paint_grip(g, (5.5, 27.5), (16.5, 14.5), 3.2)
    paint_disc(g, 16.5, 14.5, 2.2, 't', 'T')  # 头下箍
    ball = mask_disc(21.0, 8.5, 6.4)
    g.paint_mask(ball, lambda x, y: 'B')
    g.paint_mask(mask_disc(18.6, 6.2, 3.2), lambda x, y: 'L')  # 左上高光
    g.paint_mask(mask_disc(24.0, 11.6, 3.0), lambda x, y: 'D')  # 右下背光
    g.paint_mask(ball, lambda x, y: None)  # noop 保持层次清晰
    # 四向钉刺 E + 顶端白热 X
    for cx, cy in [(21.0, 1.8), (27.6, 8.5), (21.0, 15.2), (14.4, 8.5)]:
        g.paint_mask(mask_disc(cx, cy, 1.7), lambda x, y: 'E')
    g.set(21, 1, 'X')
    # 对角 G 钉
    for cx, cy in [(16.3, 3.8), (25.7, 3.8), (25.7, 13.2), (16.3, 13.2)]:
        g.paint_mask(mask_disc(cx, cy, 1.3), lambda x, y: 'G')
    g.outline()
    return g


def design_staff():
    g = Grid()
    paint_grip(g, (10.5, 29.0), (18.5, 11.5), 3.0, wrap_every=7.0)
    # 杆身 R 符文带两道
    g.paint_mask(mask_line((13.5, 23.5), (14.5, 22.0), 3.0), lambda x, y: 'R')
    g.paint_mask(mask_line((16.0, 18.0), (17.0, 16.5), 3.0), lambda x, y: 'R')
    # 顶端 T 箍 + G 宝珠 + e 暗爪笼（爪压在珠上形成笼镶）+ X 白热顶
    g.paint_mask(mask_disc(18.5, 10.5, 2.2), lambda x, y: 'T')
    g.paint_mask(mask_disc(21.0, 6.0, 2.8), lambda x, y: 'G')
    g.paint_mask(mask_disc(19.8, 4.8, 1.1), lambda x, y: 'L')  # 宝珠高光
    # 爪：两侧弧线越过珠缘收拢到顶点
    g.paint_mask(mask_line((16.5, 10.0), (17.0, 4.0), 1.8), lambda x, y: 'e')
    g.paint_mask(mask_line((17.0, 4.0), (21.0, 1.8), 1.8), lambda x, y: 'e')
    g.paint_mask(mask_line((25.5, 10.0), (25.0, 4.0), 1.8), lambda x, y: 'e')
    g.paint_mask(mask_line((25.0, 4.0), (21.0, 1.8), 1.8), lambda x, y: 'e')
    # 珠前小爪牙
    g.paint_mask(mask_line((19.0, 8.6), (20.0, 6.0), 1.4), lambda x, y: 'e')
    g.paint_mask(mask_line((23.0, 8.6), (22.0, 6.0), 1.4), lambda x, y: 'e')
    g.set(21, 1, 'X')
    g.set(20, 2, 'X')
    g.outline()
    return g


def design_book():
    g = Grid()
    cover = mask_poly([(5, 3), (24, 3), (24, 26), (5, 26)])
    g.paint_mask(cover, lambda x, y: 'B')
    # 书页 W（右缘与下缘，与封面错位半格形成厚度）
    g.paint_mask(mask_poly([(24, 4), (27, 5), (27, 27), (24, 26)]), lambda x, y: 'W')
    g.paint_mask(mask_poly([(6, 26), (24, 26), (27, 27), (27, 28), (8, 28)]),
                 lambda x, y: 'W')
    # 封面金框 T / 角 t
    g.paint_mask(mask_line((6.5, 5.0), (22.5, 5.0), 1.6), lambda x, y: 'T')
    g.paint_mask(mask_line((6.5, 5.0), (6.5, 24.0), 1.6), lambda x, y: 'T')
    g.paint_mask(mask_line((22.5, 5.0), (22.5, 24.0), 1.6), lambda x, y: 't')
    g.paint_mask(mask_line((6.5, 24.0), (22.5, 24.0), 1.6), lambda x, y: 't')
    # 受光斜带 L + 背光 D
    g.paint_mask(mask_line((8.0, 7.0), (14.0, 7.0), 1.8), lambda x, y: 'L')
    g.paint_mask(mask_line((9.0, 22.5), (21.0, 22.5), 1.6), lambda x, y: 'D')
    # 中央 R 符文行 + G 扣
    g.paint_mask(mask_line((10.0, 13.0), (19.0, 13.0), 1.6), lambda x, y: 'R')
    g.paint_mask(mask_line((10.0, 17.0), (19.0, 17.0), 1.6), lambda x, y: 'R')
    g.paint_mask(mask_disc(14.5, 9.8, 2.0), lambda x, y: 'G')
    g.outline()
    return g


def design_shield():
    g = Grid()
    body = mask_poly([(6, 4), (25, 4), (25, 13), (21, 21), (15.5, 28),
                      (10, 21), (6, 13)])
    g.paint_mask(body, lambda x, y: 'B')
    # T 包边：外轮廓内侧一圈
    rim = mask_poly([(7.5, 5.5), (23.5, 5.5), (23.5, 12.5), (20, 19.5),
                     (15.5, 25.5), (11, 19.5), (7.5, 12.5)])
    rim_data = rim.load()
    body_data = body.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if body_data[x, y] and not rim_data[x, y]:
                g.set(x, y, 'T' if (x + y) < 24 else 't')
    # 受光 L 区 + 背光 D 区
    g.paint_mask(mask_poly([(9, 7), (14, 7), (12, 13), (9, 13)]), lambda x, y: 'L')
    g.paint_mask(mask_poly([(18, 16), (21, 13), (21, 17), (17.5, 21)]),
                 lambda x, y: 'D')
    # 中央 G 铆座
    g.paint_mask(mask_disc(15.5, 11.5, 2.8), lambda x, y: 'G')
    g.outline()
    return g


def design_hoe():
    g = Grid()
    paint_grip(g, (4.5, 28.5), (16.5, 14.5), 3.0)
    paint_disc(g, 16.5, 14.5, 2.0, 't', 'T')  # 銎箍
    # 锄板：从銎向右展开的宽梯形，刃口在外缘
    blade = mask_poly([(17.5, 13.5), (26.5, 4.5), (30.0, 8.0), (23.5, 19.5),
                       (19.5, 17.5)])
    g.paint_mask(blade, lambda x, y: 'B')
    g.paint_mask(mask_line((29.0, 7.0), (23.0, 18.5), 2.0), lambda x, y: 'E')
    g.set(28, 8, 'X')
    g.set(27, 7, 'X')
    g.paint_mask(mask_line((19.0, 13.5), (25.5, 6.5), 1.7), lambda x, y: 'L')
    g.paint_mask(mask_line((21.0, 17.5), (22.5, 16.0), 1.6), lambda x, y: 'D')
    g.outline()
    return g


DESIGNS = {
    "sword": design_sword,
    "greatsword": design_greatsword,
    "dagger": design_dagger,
    "katana": design_katana,
    "spear": design_spear,
    "axe": design_axe,
    "hammer": design_hammer,
    "scythe": design_scythe,
    "mace": design_mace,
    "staff": design_staff,
    "book": design_book,
    "shield": design_shield,
    "hoe": design_hoe,
}


# ---------------- 预览渲染（复刻 roleColor：金属族 + ignite + tier1） ----------------

PALETTE_MAIN = (0xE0, 0x5A, 0x1E, 255)
PALETTE_BRIGHT = (0xFF, 0xB0, 0x60, 255)
PALETTE_DARK = (0x7A, 0x2E, 0x0E, 255)
FAMILY_METAL = (0x9A, 0xA0, 0xA8, 255)
WHITE_HOT = (0xFF, 0xF6, 0xE2, 255)
OUTLINE = (0x26, 0x22, 0x1F, 255)
GOLD = (0xD8, 0xB2, 0x4A, 255)
GOLD_DARK = (0x8A, 0x5A, 0x2B, 255)

# 各形状柄/缠绕色（摘自 java BASES）
HANDLE = {
    "sword": (0x6B, 0x4A, 0x2F, 255), "greatsword": (0x5A, 0x3E, 0x28, 255),
    "dagger": (0x4A, 0x32, 0x20, 255), "katana": (0x3A, 0x2A, 0x1E, 255),
    "axe": (0x7A, 0x52, 0x30, 255), "hammer": (0x6B, 0x4A, 0x2F, 255),
    "spear": (0x6B, 0x4A, 0x2F, 255), "scythe": (0x6B, 0x4A, 0x2F, 255),
    "mace": (0x6B, 0x4A, 0x2F, 255), "staff": (0x5A, 0x3A, 0x22, 255),
    "book": (0x6B, 0x4A, 0x2F, 255), "shield": (0x6B, 0x4A, 0x2F, 255),
    "hoe": (0x7A, 0x52, 0x30, 255),
}
WRAP = {
    "sword": (0x3E, 0x2A, 0x1A, 255), "greatsword": (0x32, 0x22, 0x14, 255),
    "dagger": (0x2E, 0x20, 0x12, 255), "katana": (0x24, 0x18, 0x10, 255),
    "axe": (0x3E, 0x2A, 0x1A, 255), "hammer": (0x3E, 0x2A, 0x1A, 255),
    "spear": (0x4A, 0x36, 0x24, 255), "scythe": (0x3E, 0x2A, 0x1A, 255),
    "mace": (0x3E, 0x2A, 0x1A, 255), "staff": (0xD8, 0xB2, 0x4A, 255),
    "book": (0xE8, 0xE0, 0xC8, 255), "shield": (0xE8, 0xE0, 0xC8, 255),
    "hoe": (0x3E, 0x2A, 0x1A, 255),
}


def shade(color, factor):
    r, g_, b, a = color
    return (min(255, int(r * factor)), min(255, int(g_ * factor)),
            min(255, int(b * factor)), a)


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
    return lerp_color(main, WHITE_HOT[:3], (t - 0.72) / 0.28)


def role_color(c, shape, x, y):
    base = PALETTE_DARK if shape == "book" else FAMILY_METAL
    if c == 'o':
        return OUTLINE
    if c == 'D':
        return shade(base, 0.62)
    if c == 'B':
        return gradient(base, x, y)
    if c == 'L':
        return shade(base, 1.28)
    if c == 'e':
        return PALETTE_DARK
    if c == 'E':
        return edge_hot(PALETTE_MAIN, x, y)
    if c == 'X':
        return WHITE_HOT
    if c == 'G':
        return PALETTE_BRIGHT  # tier1 → 效果亮色
    if c == 'R':
        return PALETTE_BRIGHT
    if c == 'T':
        return GOLD  # tier≥1 → 亮金
    if c == 't':
        return GOLD_DARK
    if c == 'H':
        return HANDLE[shape]
    if c == 'h':
        return shade(HANDLE[shape], 1.35)
    if c == 'W':
        return WRAP[shape]
    return None


def render(shape, rows):
    img = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (0, 0, 0, 0))
    # 灰白棋盘底，透明区可见
    bg = ImageDraw.Draw(img)
    for y in range(SIZE):
        for x in range(SIZE):
            v = 0x66 if (x // 4 + y // 4) % 2 == 0 else 0x88
            bg.rectangle([x * SCALE, y * SCALE, (x + 1) * SCALE - 1,
                          (y + 1) * SCALE - 1], fill=(v, v, v, 255))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            c = rows[y][x] if x < len(rows[y]) else '.'
            color = role_color(c, shape, x, y)
            if color is None:
                continue
            for dy in range(SCALE):
                for dx in range(SCALE):
                    px[x * SCALE + dx, y * SCALE + dy] = color
    return img


JAVA_HEADER = """    // ========== 32×32 手绘模板（scripts/draft_templates.py 生成并逐张目检） ==========
"""


def java_literal(name, rows):
    lines = [f'    private static final String[] {name.upper()} = {{']
    for i, r in enumerate(rows):
        comma = "," if i < len(rows) - 1 else ""
        lines.append(f'            "{r}"{comma}')
    lines.append("    };")
    return "\n".join(lines)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    blocks = [JAVA_HEADER]
    for shape, fn in DESIGNS.items():
        g = fn()
        rows = g.rows()
        for r in rows:
            assert len(r) == SIZE, f"{shape} 行长不齐"
        img = render(shape, rows)
        path = OUT / f"draft_{shape}.png"
        img.save(path)
        print(f"rendered {path}")
        blocks.append(java_literal(shape, rows))
        blocks.append("")
    (OUT / "templates.java.txt").write_text("\n".join(blocks), encoding="utf-8")
    print(f"wrote {OUT / 'templates.java.txt'}")


if __name__ == "__main__":
    main()
