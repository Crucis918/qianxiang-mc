#!/usr/bin/env python3
"""
千相 (Qianxiang) - 防具图标 + 穿戴层纹理生成脚本（64×64 新 humanoid UV 版）

覆盖：
  src/main/resources/assets/qianxiang/textures/item/
    phase_helmet / phase_chestplate / phase_leggings / phase_boots  （16×16 图标）
  src/main/resources/assets/qianxiang/textures/models/armor/
    phase_hide_layer_1.png / phase_hide_layer_2.png                 （64×64 穿戴层）

「相」风格（绚丽魔法向）：
  * 暗紫革底（对角渐变 × 受光系数 + 噪点 + 面缘压深/高光）
  * 青蓝相光脉络：胸口符文核心 / 额头节点向四周流动的发光纹路（亮核 + 辉光晕）
  * 发光节点：额头、双肩、双肘、双膝、双踝、胸口（2×2 亮核 + 十字辉光）
  * 紫色符文镶边：盔沿、胸甲下摆、靴口、护腿脚口
  * 边缘高光：每面顶/左棱提亮、底/右棱压深，参照钻石/下界合金甲的块面感

64×64 护甲 UV 布局（上半 = 经典 64×32 布局，下半 = 外层/左侧镜像，双向兼容）：
  layer_1（头盔+胸甲+靴子）：
    头部 x0-32/y0-16 | hat 外层 x32-64/y0-16 | 身体 x16-40/y16-32
    右臂 x40-56/y16-32 | 右腿靴 x0-16/y16-32 | jacket 外层 x16-40/y32-48
    左腿靴 x0-16/y48-64（另在 x16-32/y48-64 镜像一份兼容皮肤布局）
    左臂 x32-48/y48-64
  layer_2（护腿）：
    身体腰带 x16-40/y16-32 | 右腿 x0-16/y16-32
    左腿 x0-16/y48-64（另 x16-32/y48-64 镜像一份）| 身体外层 x16-40/y32-48

靴子只画腿下半截（靴筒 + 脚），腿区域上半留透明，绝不向上延伸占裤子；
护腿画满整条腿。

运行后在 /tmp/qianxiang_armor_preview/ 输出 16× 图标预览与穿戴正/背面拼图，供自验。
"""

import random
from pathlib import Path

from PIL import Image, ImageFilter

S = 16   # 图标边长
LW, LH = 64, 64  # 穿戴层尺寸（1.21 新 humanoid 布局）


# -----------------------------------------------------------------------------
# 基础工具
# -----------------------------------------------------------------------------

def project_root() -> Path:
    return Path(__file__).resolve().parent.parent


def rgb(hex_color: str):
    h = hex_color.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def lerp(c1, c2, t: float):
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))


def clamp(v: int) -> int:
    return max(0, min(255, v))


def blend(dst, src, t: float):
    """dst 向 src 混合 t（0~1）。"""
    return tuple(clamp(int(dst[i] + (src[i] - dst[i]) * t)) for i in range(3))


# 「相」风格调色板
LEATHER_L = rgb("#6B5588")   # 革料亮部
LEATHER_D = rgb("#2C2140")   # 革料暗部
LEATHER_X = rgb("#8871AC")   # 革料高光（棱线/护趾）
LEATHER_S = rgb("#1E1530")   # 革料压深（束带/接缝）
SEAM      = rgb("#150C24")   # 描边/缝线
CYAN      = rgb("#3FD8C8")   # 相光青蓝
CYAN_HI   = rgb("#A6F8EE")   # 相光高亮（亮核）
CYAN_DK   = rgb("#1E8E84")   # 相光深青（辉光末梢）
RUNE      = rgb("#9A5AE0")   # 符文紫
RUNE_HI   = rgb("#CDA9FA")   # 符文亮紫
GOLD      = rgb("#C89830")   # 少量金属点缀
WHITE     = rgb("#F4FEFF")   # 高光点


# -----------------------------------------------------------------------------
# 16×16 图标：字符网格 → 纹理（发光边缘 + 内渐变 + 高光点 + 相光流动）
# -----------------------------------------------------------------------------

def icon_from_grid(grid, halo_color=RUNE, halo_strength=85, seed=1) -> Image.Image:
    """按字符网格生成图标，并叠加绚丽化后期：
    1) 稀有度光晕垫底；
    2) '#' 革料对角渐变 + 噪点（内渐变）；
    3) 边缘发光：朝空的外缘棱线向青白提亮；
    4) 高光点：左上受光处撒星芒白点。
    """
    assert len(grid) == S
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    # 形状掩码（任何非透明字符）
    mask = Image.new("L", (S, S), 0)
    mp = mask.load()
    for y, row in enumerate(grid):
        row = row.ljust(S, ".")
        assert len(row) == S, f"行 {y} 超长: {row!r}"
        for x, ch in enumerate(row):
            if ch != ".":
                mp[x, y] = 255

    # 稀有度光晕（垫底）
    if halo_color and halo_strength > 0:
        blur = mask.filter(ImageFilter.GaussianBlur(1.2))
        bp = blur.load()
        halo = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        hp = halo.load()
        for y in range(S):
            for x in range(S):
                a = bp[x, y] * halo_strength // 255
                if a:
                    hp[x, y] = (halo_color[0], halo_color[1], halo_color[2], a)
        img.alpha_composite(halo)

    fixed = {
        "=": LEATHER_X, ",": LEATHER_S, "o": SEAM,
        "c": CYAN, "C": CYAN_HI, "d": CYAN_DK,
        "r": RUNE, "R": RUNE_HI, "g": GOLD, "w": WHITE,
    }
    px = img.load()
    rnd = random.Random(seed)
    for y, row in enumerate(grid):
        row = row.ljust(S, ".")
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            if ch == "#":
                t = (x + y) / (2 * (S - 1))
                base = lerp(LEATHER_L, LEATHER_D, t)
                d = rnd.randint(-5, 5)
                px[x, y] = (clamp(base[0] + d), clamp(base[1] + d),
                            clamp(base[2] + d), 255)
            else:
                c = fixed[ch]
                px[x, y] = (c[0], c[1], c[2], 255)

    # 边缘发光：非透明像素若上/左邻居透明（受光外缘），按材质提亮
    edge_targets = {
        "c": (CYAN_HI, 0.5), "C": (WHITE, 0.45), "d": (CYAN, 0.5),
        "r": (RUNE_HI, 0.5), "R": (WHITE, 0.4), "g": (WHITE, 0.4),
        "o": (RUNE, 0.38), "=": (WHITE, 0.3), ",": (LEATHER_L, 0.55),
    }
    src = [row[:] for row in grid]
    for y in range(S):
        for x in range(S):
            ch = src[y][x] if x < len(src[y]) else "."
            if ch == ".":
                continue
            lit_top = y == 0 or (x >= len(src[y - 1]) or src[y - 1][x] == ".")
            lit_left = x == 0 or src[y][x - 1] == "."
            if not (lit_top or lit_left):
                continue
            r, g, b, a = px[x, y]
            if ch in edge_targets:
                glow_to, t = edge_targets[ch]
            else:  # '#' 革料：向自身亮部 + 微白提亮
                glow_to, t = blend((r, g, b), WHITE, 0.45), 0.32
            if lit_top and lit_left:
                t = min(1.0, t + 0.12)
            nr, ng, nb = blend((r, g, b), glow_to, t)
            px[x, y] = (nr, ng, nb, a)
    return img


# 头盔：小缨冠（青蓝流光）+ 额心发光节点 + 细目镜缝 + 符文铆钉 + 护鼻
HELMET_GRID = [
    "................",
    ".......wC.......",
    "......c##c......",
    ".....r####r.....",
    "....o######o....",
    "...o########o...",
    "..o##=####=##o..",
    "..o#r##CC##r#o..",
    "..o##########o..",
    "..o##########o..",
    "..o#cc####cc#o..",
    "..o,##=##=##,o..",
    "..o,##=##=##,o..",
    "..o,#,####,#,o..",
    "...oo######oo...",
    "................",
]

# 胸甲：肩甲（相光铆钉）+ 护胸板 + 中央菱形符文核心 + 流动脉络 + 腰带金扣
CHESTPLATE_GRID = [
    "................",
    "................",
    "...oo......oo...",
    "..o##o....o##o..",
    "..o#c#o..o#c#o..",
    ".o####o..o####o.",
    ".o####oooo####o.",
    "..o##########o..",
    "..o###r##r###o..",
    "..o##r#CC#r##o..",
    "..o#d#rCCr#d#o..",
    "..o###r##r###o..",
    "..o,,,,gg,,,,o..",
    "..o#,#,,,,#,#o..",
    "...o########o...",
    "................",
]

# 护腿：腰带（符文扣+金铆）+ 双腿革甲 + 膝部发光节点 + 相光收口
LEGGINGS_GRID = [
    "................",
    "................",
    "..o##########o..",
    "..o,,,,rr,,,,o..",
    "..o,,c,gg,c,,o..",
    "..o###o..o###o..",
    "..o#c#o..o#c#o..",
    "..o###o..o###o..",
    "..o#CCo..oCC#o..",
    "..o###o..o###o..",
    "..o###o..o###o..",
    "..o#d#o..o#d#o..",
    "..o#,#o..o#,#o..",
    "..o#,ro..or,#o..",
    "...oo#o..o#oo...",
    "................",
]

# 靴子：靴筒（筒口符文镶边）+ 踝部发光节点 + 护趾高光 + 加厚底
BOOTS_GRID = [
    "................",
    "................",
    "................",
    "................",
    "..o###o..o###o..",
    "..or#ro..or#ro..",
    "..o###o..o###o..",
    "..o#,#o..o#,#o..",
    "..o###o..o###o..",
    "..o###o..o###o..",
    "..o#CCo..oCC#o..",
    "..o#c#o..o#c#o..",
    ".o####o..o####o.",
    ".o==##o..o##==o.",
    ".o,,,,o..o,,,,o.",
    "................",
]


def tex_phase_helmet() -> Image.Image:
    return icon_from_grid(HELMET_GRID, seed=301)


def tex_phase_chestplate() -> Image.Image:
    return icon_from_grid(CHESTPLATE_GRID, seed=302)


def tex_phase_leggings() -> Image.Image:
    return icon_from_grid(LEGGINGS_GRID, seed=303)


def tex_phase_boots() -> Image.Image:
    return icon_from_grid(BOOTS_GRID, seed=304)


# -----------------------------------------------------------------------------
# 64×64 穿戴层：新 humanoid 护甲 UV 布局
# -----------------------------------------------------------------------------

def box_faces(ox, oy, w, h, d):
    """盒式 UV：返回 {面名: (x, y, 宽, 高)}。"""
    return {
        "top":    (ox + d, oy, w, d),
        "bottom": (ox + d + w, oy, w, d),
        "right":  (ox, oy + d, d, h),
        "front":  (ox + d, oy + d, w, h),
        "left":   (ox + d + w, oy + d, d, h),
        "back":   (ox + d + w + d, oy + d, w, h),
    }


HEAD  = box_faces(0, 0, 8, 8, 8)       # 头部 x0-32/y0-16
HAT   = box_faces(32, 0, 8, 8, 8)      # hat 外层 x32-64/y0-16
BODY  = box_faces(16, 16, 8, 12, 4)    # 身体 x16-40/y16-32
JACKET = box_faces(16, 32, 8, 12, 4)   # jacket 外层 x16-40/y32-48
RARM  = box_faces(40, 16, 4, 12, 4)    # 右臂 x40-56/y16-32
LARM  = box_faces(32, 48, 4, 12, 4)    # 左臂 x32-48/y48-64
RLEG  = box_faces(0, 16, 4, 12, 4)     # 右腿 x0-16/y16-32
LLEG_O = box_faces(0, 48, 4, 12, 4)    # 左腿 x0-16/y48-64（护甲布局）
LLEG_S = box_faces(16, 48, 4, 12, 4)   # 左腿 x16-32/y48-64（皮肤布局兼容）

# 各面受光系数：顶亮底暗，背面次之
FACE_LIGHT = {"top": 1.2, "bottom": 0.6, "front": 1.0,
              "right": 0.8, "left": 0.92, "back": 0.74}


def fill_face(img: Image.Image, rect, factor: float, seed: int,
              row_range=None, col_range=None):
    """革底填充：对角渐变 × 受光系数 + 噪点。可限定行/列区间（局部绘制）。"""
    x0, y0, w, h = rect
    px = img.load()
    rnd = random.Random(seed)
    ys = row_range if row_range is not None else range(h)
    xs = col_range if col_range is not None else range(w)
    ys = [ly for ly in ys if 0 <= ly < h]   # 裁剪到面内（top/bottom 面只有 d 行）
    xs = [lx for lx in xs if 0 <= lx < w]
    for ly in ys:
        for lx in xs:
            t = (lx / max(1, w - 1) + ly / max(1, h - 1)) / 2
            base = lerp(LEATHER_L, LEATHER_D, t)
            d = rnd.randint(-6, 6)
            px[x0 + lx, y0 + ly] = (clamp(int(base[0] * factor) + d),
                                    clamp(int(base[1] * factor) + d),
                                    clamp(int(base[2] * factor) + d), 255)


def edge_shade(img: Image.Image, rect):
    """面缘立体化：顶/左棱提亮，底/右棱压深，制造块面高光。"""
    x0, y0, w, h = rect
    px = img.load()

    def mul(x, y, f):
        r, g, b, a = px[x, y]
        if a:
            px[x, y] = (clamp(int(r * f)), clamp(int(g * f)),
                        clamp(int(b * f)), a)

    for i in range(w):
        mul(x0 + i, y0, 1.22)              # 顶棱高光
        mul(x0 + i, y0 + h - 1, 0.68)      # 底棱压深
    for i in range(h):
        mul(x0, y0 + i, 1.12)              # 左棱提亮
        mul(x0 + w - 1, y0 + i, 0.8)       # 右棱压深


def darken(img: Image.Image, pixels, factor: float):
    px = img.load()
    for x, y in pixels:
        if 0 <= x < LW and 0 <= y < LH:
            r, g, b, a = px[x, y]
            if a:
                px[x, y] = (clamp(int(r * factor)), clamp(int(g * factor)),
                            clamp(int(b * factor)), a)


def sp(img: Image.Image, x: int, y: int, color):
    if 0 <= x < LW and 0 <= y < LH:
        img.load()[x, y] = (color[0], color[1], color[2], 255)


def glow_dot(img: Image.Image, x: int, y: int, color=CYAN, t: float = 0.55):
    """向已有像素混一抹辉光（不点亮透明区）。"""
    if 0 <= x < LW and 0 <= y < LH:
        px = img.load()
        r, g, b, a = px[x, y]
        if a:
            nr, ng, nb = blend((r, g, b), color, t)
            px[x, y] = (nr, ng, nb, a)


def glow_node(img: Image.Image, x: int, y: int):
    """2×2 发光节点：亮核 + 十字辉光。"""
    sp(img, x, y, CYAN_HI)
    sp(img, x + 1, y, CYAN)
    sp(img, x, y + 1, CYAN)
    sp(img, x + 1, y + 1, CYAN)
    for nx, ny in ((x - 1, y), (x + 2, y), (x - 1, y + 1), (x + 2, y + 1),
                   (x, y - 1), (x + 1, y - 1), (x, y + 2), (x + 1, y + 2)):
        glow_dot(img, nx, ny, CYAN, 0.35)


def vein(img: Image.Image, points, core=CYAN, tip=CYAN_HI):
    """流动相光脉络：折线核心 + 邻域淡辉光。首点为源（更亮）。"""
    pts = list(points)
    for i, (x, y) in enumerate(pts):
        sp(img, x, y, tip if i == 0 else core)
    painted = set(pts)
    for x, y in pts:
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if (nx, ny) not in painted:
                glow_dot(img, nx, ny, CYAN_DK, 0.3)


def rune_trim(img: Image.Image, x0: int, y: int, length: int, step: int = 2):
    """水平符文镶边：每隔 step 一格的符文钉，间以暗扣。"""
    for i in range(length):
        if i % step == 0:
            sp(img, x0 + i, y, RUNE_HI if (i // step) % 2 == 0 else RUNE)
        else:
            darken(img, [(x0 + i, y)], 0.6)


def layer_canvas() -> Image.Image:
    return Image.new("RGBA", (LW, LH), (0, 0, 0, 0))


ALL_FACES = ["top", "bottom", "right", "front", "left", "back"]
SIDE_FACES = ["right", "front", "left", "back"]


# -----------------------------------------------------------------------------
# 部件绘制
# -----------------------------------------------------------------------------

def draw_helmet(img: Image.Image, box, seed: int, crest: bool):
    """头盔：盔体革底 + 额心发光节点 + 流动脉络 + 符文盔沿 + 盔顶棱冠。"""
    for i, face in enumerate(ALL_FACES):
        fill_face(img, box[face], FACE_LIGHT[face], seed + i)
    for face in ALL_FACES:
        edge_shade(img, box[face])

    hf = box["front"]   # 前脸
    fx, fy = hf[0], hf[1]
    # 额心发光节点（眉心）
    glow_node(img, fx + 3, fy + 1)
    # 自额心向两侧的相光脉络
    vein(img, [(fx + 2, fy + 2), (fx + 1, fy + 3), (fx + 1, fy + 4)])
    vein(img, [(fx + 5, fy + 2), (fx + 6, fy + 3), (fx + 6, fy + 4)])
    vein(img, [(fx + 3, fy + 4), (fx + 4, fy + 4), (fx + 4, fy + 5)], tip=CYAN)
    # 目镜缝（眼下压深 + 两点青蓝微光）
    darken(img, [(fx + lx, fy + 5) for lx in range(8)], 0.72)
    sp(img, fx + 2, fy + 5, CYAN_DK)
    sp(img, fx + 5, fy + 5, CYAN_DK)
    # 盔沿符文镶边
    rune_trim(img, fx, fy + 7, 8)
    # 太阳穴相光钉（侧面）
    for side in ("right", "left"):
        sf = box[side]
        glow_node(img, sf[0] + 1, sf[1] + 3)
        vein(img, [(sf[0] + 2, sf[1] + 6)])
    # 盔顶：纵向棱冠虚线 + 中央相光节点
    ht = box["top"]
    tx, ty = ht[0], ht[1]
    for ly in range(0, 8, 2):
        sp(img, tx + 3, ty + ly, CYAN_DK)
        sp(img, tx + 4, ty + ly, CYAN_DK)
    glow_node(img, tx + 3, ty + 3)
    darken(img, [(tx + lx, ty) for lx in range(8)], 0.8)
    if crest:
        # 外层 hat：缨冠高光脊（断续）
        for ly in (1, 5):
            sp(img, tx + 3, ty + ly, CYAN)
            sp(img, tx + 4, ty + ly, CYAN)
    # 后脸：符文垂饰
    hb = box["back"]
    vein(img, [(hb[0] + 3, hb[1] + 2), (hb[0] + 4, hb[1] + 3),
               (hb[0] + 3, hb[1] + 4), (hb[0] + 4, hb[1] + 5)], tip=RUNE_HI)
    for lx, ly in ((3, 2), (4, 3), (3, 4), (4, 5)):
        sp(img, hb[0] + lx, hb[1] + ly, RUNE)
    rune_trim(img, hb[0], hb[1] + 7, 8)


def draw_chest(img: Image.Image, box, seed: int, tabard: bool):
    """胸甲：护胸革底 + 中央符文核心 + 放射相光脉络 + 肩甲 + 腰带 + 下摆符文。"""
    for i, face in enumerate(ALL_FACES):
        fill_face(img, box[face], FACE_LIGHT[face], seed + i)
    for face in ALL_FACES:
        edge_shade(img, box[face])

    bf = box["front"]
    fx, fy = bf[0], bf[1]
    # 领口压深 + 肩缘高光
    darken(img, [(fx + lx, fy) for lx in range(8)], 0.78)
    for lx in (0, 7):
        glow_dot(img, fx + lx, fy + 1, LEATHER_X, 0.5)
    # 中央菱形符文核心（亮核 + 紫钻 + 辉光）
    sp(img, fx + 3, fy + 2, RUNE)
    sp(img, fx + 4, fy + 2, RUNE)
    sp(img, fx + 2, fy + 3, RUNE)
    sp(img, fx + 5, fy + 3, RUNE)
    sp(img, fx + 3, fy + 3, CYAN_HI)
    sp(img, fx + 4, fy + 3, CYAN_HI)
    sp(img, fx + 2, fy + 4, RUNE)
    sp(img, fx + 5, fy + 4, RUNE)
    sp(img, fx + 3, fy + 4, CYAN_HI)
    sp(img, fx + 4, fy + 4, CYAN_HI)
    sp(img, fx + 3, fy + 5, RUNE)
    sp(img, fx + 4, fy + 5, RUNE)
    for nx, ny in ((fx + 3, fy + 1), (fx + 4, fy + 1), (fx + 1, fy + 3),
                   (fx + 6, fy + 3), (fx + 1, fy + 4), (fx + 6, fy + 4)):
        glow_dot(img, nx, ny, RUNE, 0.45)
    # 放射相光脉络（自核心流向肩部与下摆）
    vein(img, [(fx + 2, fy + 2), (fx + 1, fy + 1), (fx + 0, fy + 2)])
    vein(img, [(fx + 5, fy + 2), (fx + 6, fy + 1), (fx + 7, fy + 2)])
    vein(img, [(fx + 2, fy + 6), (fx + 1, fy + 7), (fx + 1, fy + 8)])
    vein(img, [(fx + 5, fy + 6), (fx + 6, fy + 7), (fx + 6, fy + 8)])
    # 腰带：压深 + 相光扣 + 金铆
    darken(img, [(fx + lx, fy + 9) for lx in range(8)], 0.7)
    darken(img, [(fx + lx, fy + 10) for lx in range(8)], 0.85)
    glow_node(img, fx + 3, fy + 9)
    sp(img, fx + 1, fy + 9, GOLD)
    sp(img, fx + 6, fy + 9, GOLD)
    # 下摆符文镶边
    rune_trim(img, fx, fy + 11, 8)
    if tabard:
        # jacket 外层：垂甲片中央相光流
        vein(img, [(fx + 3, fy + 6), (fx + 4, fy + 6), (fx + 3, fy + 7),
                   (fx + 4, fy + 8), (fx + 3, fy + 9), (fx + 4, fy + 10)],
             tip=CYAN_HI)

    # 背甲：纵向大符文 + 脊柱相光线
    bb = box["back"]
    bx, by = bb[0], bb[1]
    for lx, ly, c in [(3, 2, RUNE), (4, 2, RUNE),
                      (2, 3, RUNE), (5, 3, RUNE), (3, 3, CYAN_HI), (4, 3, CYAN_HI),
                      (2, 4, RUNE), (5, 4, RUNE), (3, 4, CYAN), (4, 4, CYAN),
                      (2, 5, RUNE), (5, 5, RUNE), (3, 5, CYAN_HI), (4, 5, CYAN_HI),
                      (3, 6, RUNE), (4, 6, RUNE)]:
        sp(img, bx + lx, by + ly, c)
    vein(img, [(bx + 3, by + 7), (bx + 4, by + 7), (bx + 3, by + 8), (bx + 4, by + 8)])
    darken(img, [(bx + lx, by + 9) for lx in range(8)], 0.7)
    glow_node(img, bx + 3, by + 9)
    rune_trim(img, bx, by + 11, 8)

    # 体侧缝线：相光断续线（少量点缀）
    for side in ("right", "left"):
        sf = box[side]
        sp(img, sf[0] + 1, sf[1] + 3, CYAN_DK)
        sp(img, sf[0] + 1, sf[1] + 6, CYAN)
        darken(img, [(sf[0] + lx, sf[1] + 9) for lx in range(4)], 0.7)


def draw_arm(img: Image.Image, box, seed: int, mirror: bool):
    """臂甲：肩关节发光节点 + 臂面脉络 + 护腕相光带 + 手部压深。"""
    for i, face in enumerate(ALL_FACES):
        fill_face(img, box[face], FACE_LIGHT[face], seed + i)
    for face in ALL_FACES:
        edge_shade(img, box[face])

    at_ = box["top"]
    glow_node(img, at_[0] + 1, at_[1] + 1)                    # 肩节点
    af = box["front"]
    fx, fy = af[0], af[1]
    # 上臂相光脉络（自肩流向肘）
    vein(img, [(fx + 1, fy + 1), (fx + 2, fy + 2), (fx + 1, fy + 3)])
    # 肘部发光节点
    glow_node(img, fx + 1, fy + 5)
    # 护腕：压深 + 相光滚边（两点亮芯）+ 符文钉
    darken(img, [(fx + lx, fy + 8) for lx in range(4)], 0.78)
    darken(img, [(fx + lx, fy + 9) for lx in range(4)], 0.9)
    sp(img, fx + 1, fy + 9, CYAN)
    sp(img, fx + 2, fy + 9, CYAN_DK)
    sp(img, fx + (3 if mirror else 0), fy + 9, RUNE)
    # 手部压深 + 指缘微光
    darken(img, [(fx + lx, fy + 10) for lx in range(4)], 0.72)
    darken(img, [(fx + lx, fy + 11) for lx in range(4)], 0.6)
    sp(img, fx + 1, fy + 11, CYAN_DK)
    # 臂背脉络
    abk = box["back"]
    vein(img, [(abk[0] + 1, abk[1] + 3), (abk[0] + 2, abk[1] + 4),
               (abk[0] + 1, abk[1] + 6)])
    for side in ("right", "left"):
        sf = box[side]
        sp(img, sf[0] + 1, sf[1] + 5, CYAN_DK)
        darken(img, [(sf[0] + lx, sf[1] + 11) for lx in range(4)], 0.6)


def draw_boot(img: Image.Image, box, seed: int, mirror: bool):
    """靴子：只占腿下半截（ly 6-11），上半留透明，绝不向上延伸。
    靴筒符文镶边 + 踝部发光节点 + 相光襻带 + 护趾高光 + 加厚靴底。"""
    BOOT_TOP = 6  # 靴筒起始行（0-5 透明 = 小腿交给护腿/皮肤）
    for i, face in enumerate(ALL_FACES):
        # 顶/底是水平面（不外露于腿侧），整面绘制；侧面只画靴筒区间
        rr = None if face in ("top", "bottom") else range(BOOT_TOP, 12)
        fill_face(img, box[face], FACE_LIGHT[face], seed + i, row_range=rr)
    # 靴底整体压深成厚底
    bf = box["bottom"]
    darken(img, [(bf[0] + lx, bf[1] + ly) for lx in range(4) for ly in range(4)], 0.55)
    sp(img, bf[0] + 1, bf[1] + 1, CYAN_DK)                   # 足底相光纹
    sp(img, bf[0] + 2, bf[1] + 2, CYAN_DK)
    for face in ALL_FACES:
        x0, y0, w, h = box[face]
        if face in ("top", "bottom"):
            edge_shade(img, (x0, y0, w, h))
        else:
            edge_shade(img, (x0, y0 + BOOT_TOP, w, 12 - BOOT_TOP))

    lf = box["front"]
    fx, fy = lf[0], lf[1]
    # 靴口符文镶边 + 两点相光滚边
    rune_trim(img, fx, fy + BOOT_TOP, 4)
    glow_dot(img, fx + 1, fy + BOOT_TOP + 1, CYAN, 0.55)
    glow_dot(img, fx + 2, fy + BOOT_TOP + 1, CYAN, 0.4)
    # 踝部发光节点 + 短脉络
    glow_node(img, fx + 1, fy + 8)
    vein(img, [(fx + (0 if mirror else 3), fy + 7)], tip=CYAN)
    # 护趾高光 + 靴底压深
    toe_x = fx + (0 if mirror else 3)
    sp(img, toe_x, fy + 10, LEATHER_X)
    glow_dot(img, toe_x, fy + 9, LEATHER_X, 0.4)
    darken(img, [(fx + lx, fy + 11) for lx in range(4)], 0.5)
    sp(img, fx + 1, fy + 11, CYAN_DK)                        # 足底相光缝
    # 靴背：跟腱脉络
    lb = box["back"]
    vein(img, [(lb[0] + 1, lb[1] + 7), (lb[0] + 2, lb[1] + 8),
               (lb[0] + 1, lb[1] + 9)])
    darken(img, [(lb[0] + lx, lb[1] + 11) for lx in range(4)], 0.5)
    for side in ("right", "left"):
        sf = box[side]
        sp(img, sf[0] + 1, sf[1] + 8, CYAN_DK)
        darken(img, [(sf[0] + lx, sf[1] + 11) for lx in range(4)], 0.5)


def draw_leggings_leg(img: Image.Image, box, seed: int, mirror: bool):
    """护腿腿甲：满腿 12 行。大腿板 + 膝部发光节点 + 胫骨相光脊 + 符文收口。"""
    for i, face in enumerate(ALL_FACES):
        fill_face(img, box[face], FACE_LIGHT[face], seed + i)
    for face in ALL_FACES:
        edge_shade(img, box[face])

    lf = box["front"]
    fx, fy = lf[0], lf[1]
    # 大腿甲：上缘高光 + 流动脉络
    glow_dot(img, fx + 1, fy, LEATHER_X, 0.45)
    vein(img, [(fx + (3 if mirror else 0), fy + 1), (fx + 2, fy + 2),
               (fx + (0 if mirror else 3), fy + 3)])
    # 膝部发光节点（核心装饰）
    glow_node(img, fx + 1, fy + 5)
    glow_dot(img, fx + 1, fy + 4, CYAN, 0.4)
    # 胫骨相光脊 + 符文缀钉
    vein(img, [(fx + 1, fy + 7), (fx + 2, fy + 8), (fx + 1, fy + 9)])
    sp(img, fx + (3 if mirror else 0), fy + 8, RUNE)
    # 收口：压深 + 相光滚边 + 符文镶边
    darken(img, [(fx + lx, fy + 10) for lx in range(4)], 0.78)
    rune_trim(img, fx, fy + 11, 4)
    # 腿背脉络
    lb = box["back"]
    vein(img, [(lb[0] + 1, lb[1] + 2), (lb[0] + 2, lb[1] + 3),
               (lb[0] + 1, lb[1] + 4)])
    glow_node(img, lb[0] + 1, lb[1] + 5)                      # 膝后节点
    vein(img, [(lb[0] + 1, lb[1] + 8), (lb[0] + 2, lb[1] + 9)])
    darken(img, [(lb[0] + lx, lb[1] + 10) for lx in range(4)], 0.78)
    rune_trim(img, lb[0], lb[1] + 11, 4)
    for side in ("right", "left"):
        sf = box[side]
        sp(img, sf[0] + 1, sf[1] + 4, CYAN_DK)
        sp(img, sf[0] + 1, sf[1] + 6, CYAN)
        darken(img, [(sf[0] + lx, sf[1] + 11) for lx in range(4)], 0.78)


def draw_belt(img: Image.Image, box, seed: int, skirt: bool):
    """护腿腰甲：腰带 + 符文扣 + 相光滚边 + 垂甲裙摆。"""
    for i, face in enumerate(ALL_FACES):
        fill_face(img, box[face], FACE_LIGHT[face], seed + i)
    for face in ALL_FACES:
        edge_shade(img, box[face])

    bf = box["front"]
    fx, fy = bf[0], bf[1]
    # 腰带：双排压深 + 符文钉 + 相光扣
    darken(img, [(fx + lx, fy) for lx in range(8)], 0.7)
    darken(img, [(fx + lx, fy + 1) for lx in range(8)], 0.82)
    for lx in (1, 3, 4, 6):
        sp(img, fx + lx, fy, RUNE)
    glow_node(img, fx + 3, fy + 1)
    sp(img, fx + 0, fy + 1, GOLD)
    sp(img, fx + 7, fy + 1, GOLD)
    # 腰缘相光滚边（深青底 + 两点亮芯）
    for lx in range(8):
        sp(img, fx + lx, fy + 2, CYAN_DK)
    sp(img, fx + 2, fy + 2, CYAN)
    sp(img, fx + 5, fy + 2, CYAN)
    # 垂甲裙摆：两侧甲片 + 中央垂带相光流 + 下摆符文镶边
    darken(img, [(fx + 3, fy + ly) for ly in range(3, 11)], 0.92)
    darken(img, [(fx + 4, fy + ly) for ly in range(3, 11)], 0.92)
    vein(img, [(fx + 3, fy + 4), (fx + 4, fy + 5), (fx + 3, fy + 6),
               (fx + 4, fy + 7), (fx + 3, fy + 8), (fx + 4, fy + 9)])
    sp(img, fx + 1, fy + 5, CYAN_DK)
    sp(img, fx + 6, fy + 5, CYAN_DK)
    rune_trim(img, fx, fy + 11, 8)
    if skirt:
        # 身体外层：垂甲延长 + 缘边高光
        for lx in range(8):
            glow_dot(img, fx + lx, fy + 2, CYAN, 0.4)

    bb = box["back"]
    bx, by = bb[0], bb[1]
    darken(img, [(bx + lx, by) for lx in range(8)], 0.7)
    darken(img, [(bx + lx, by + 1) for lx in range(8)], 0.82)
    glow_node(img, bx + 3, by + 1)
    for lx in range(8):
        sp(img, bx + lx, by + 2, CYAN_DK)
    sp(img, bx + 2, by + 2, CYAN)
    sp(img, bx + 5, by + 2, CYAN)
    vein(img, [(bx + 3, by + 4), (bx + 4, by + 5), (bx + 3, by + 6),
               (bx + 4, by + 7), (bx + 3, by + 8)])
    rune_trim(img, bx, by + 11, 8)
    for side in ("right", "left"):
        sf = box[side]
        sp(img, sf[0] + 1, sf[1] + 1, CYAN)
        sp(img, sf[0] + 1, sf[1] + 5, CYAN_DK)
        sp(img, sf[0] + 1, sf[1] + 8, CYAN_DK)


# -----------------------------------------------------------------------------
# 两张穿戴层
# -----------------------------------------------------------------------------

def tex_layer_1() -> Image.Image:
    """layer_1：头盔 + 胸甲 + 靴子。"""
    img = layer_canvas()
    draw_helmet(img, HEAD, 401, crest=False)
    draw_helmet(img, HAT, 402, crest=True)
    draw_chest(img, BODY, 403, tabard=False)
    draw_chest(img, JACKET, 404, tabard=True)
    draw_arm(img, RARM, 405, mirror=False)
    draw_arm(img, LARM, 406, mirror=True)
    draw_boot(img, RLEG, 407, mirror=False)
    draw_boot(img, LLEG_O, 408, mirror=True)
    draw_boot(img, LLEG_S, 409, mirror=True)   # 皮肤布局兼容位
    return img


def tex_layer_2() -> Image.Image:
    """layer_2：护腿（腰带 + 双腿满腿甲）。"""
    img = layer_canvas()
    draw_belt(img, BODY, 501, skirt=False)
    draw_belt(img, JACKET, 502, skirt=True)
    draw_leggings_leg(img, RLEG, 503, mirror=False)
    draw_leggings_leg(img, LLEG_O, 504, mirror=True)
    draw_leggings_leg(img, LLEG_S, 505, mirror=True)   # 皮肤布局兼容位
    return img


# -----------------------------------------------------------------------------
# 预览（/tmp，不入库）
# -----------------------------------------------------------------------------

def worn_view(layers, scale: int = 8, back: bool = False) -> Image.Image:
    """把各部件拼成人形自验图。layers 自下而上叠放（护腿在靴下）。"""
    canvas = Image.new("RGBA", (24 * scale, 32 * scale), (32, 30, 40, 255))

    def put(layer, rect, dx, dy, mirror=False):
        x, y, w, h = rect
        part = layer.crop((x, y, x + w, y + h))
        if mirror:
            part = part.transpose(Image.FLIP_LEFT_RIGHT)
        part = part.resize((w * scale, h * scale), Image.NEAREST)
        canvas.alpha_composite(part, (dx * scale, dy * scale))

    for layer in layers:
        if back:
            put(layer, HEAD["back"], 8, 0)
            put(layer, BODY["back"], 8, 8)
            put(layer, RARM["back"], 4, 8, mirror=True)
            put(layer, RARM["back"], 16, 8)
            put(layer, RLEG["back"], 8, 20, mirror=True)
            put(layer, RLEG["back"], 12, 20)
        else:
            put(layer, HEAD["front"], 8, 0)
            put(layer, BODY["front"], 8, 8)
            put(layer, RARM["front"], 4, 8)
            put(layer, RARM["front"], 16, 8, mirror=True)
            put(layer, RLEG["front"], 8, 20)
            put(layer, RLEG["front"], 12, 20, mirror=True)
    return canvas


def make_previews(out_dir: Path, icons: dict, l1: Image.Image, l2: Image.Image):
    out_dir.mkdir(parents=True, exist_ok=True)
    scale = 16
    names = list(icons)
    canvas = Image.new("RGBA", (len(names) * S * scale, S * scale), (40, 40, 48, 255))
    for i, n in enumerate(names):
        canvas.alpha_composite(icons[n].resize((S * scale, S * scale), Image.NEAREST),
                               (i * S * scale, 0))
    canvas.save(out_dir / "items.png")

    l1.resize((LW * 6, LH * 6), Image.NEAREST).save(out_dir / "layer_1.png")
    l2.resize((LW * 6, LH * 6), Image.NEAREST).save(out_dir / "layer_2.png")
    worn_view([l1]).save(out_dir / "worn_layer_1_front.png")
    worn_view([l1], back=True).save(out_dir / "worn_layer_1_back.png")
    worn_view([l2]).save(out_dir / "worn_layer_2_front.png")
    # 全套：护腿垫底 + 靴/胸/盔覆盖（靴子不得盖住护腿大腿）
    worn_view([l2, l1]).save(out_dir / "worn_full_front.png")
    worn_view([l2, l1], back=True).save(out_dir / "worn_full_back.png")
    print(f"预览输出: {out_dir}")


# -----------------------------------------------------------------------------
# 主入口
# -----------------------------------------------------------------------------

def main():
    root = project_root()
    item_dir = root / "src/main/resources/assets/qianxiang/textures/item"
    armor_dir = root / "src/main/resources/assets/qianxiang/textures/models/armor"
    item_dir.mkdir(parents=True, exist_ok=True)
    armor_dir.mkdir(parents=True, exist_ok=True)

    icons = {
        "phase_helmet": tex_phase_helmet(),
        "phase_chestplate": tex_phase_chestplate(),
        "phase_leggings": tex_phase_leggings(),
        "phase_boots": tex_phase_boots(),
    }
    for name, img in icons.items():
        img.save(item_dir / f"{name}.png")
        print(f"item/{name}.png")

    l1, l2 = tex_layer_1(), tex_layer_2()
    assert l1.size == (64, 64) and l2.size == (64, 64)
    l1.save(armor_dir / "phase_hide_layer_1.png")
    l2.save(armor_dir / "phase_hide_layer_2.png")
    print("models/armor/phase_hide_layer_1.png (64x64)")
    print("models/armor/phase_hide_layer_2.png (64x64)")

    make_previews(Path("/tmp/qianxiang_armor_preview"), icons, l1, l2)
    print("Done: 4 armor icons + 2 armor layers (64x64).")


if __name__ == "__main__":
    main()
