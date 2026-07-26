#!/usr/bin/env python3
"""
千相 (Qianxiang) - 物品/方块纹理生成脚本（绚丽版 v3）

重做所有相材料、产物物品与维度方块的 16×16 纹理，覆盖：
  src/main/resources/assets/qianxiang/textures/item/
    ember_crystal / beast_fang / ember_iron / bloodroot / abyss_iron
    salamander_gland / dragon_bone / rift_essence / shadowhide_patch
    glimmer_wood_sap / myriad_fragment
    ember_blade / phase_staff / bone_blade / phase_shield
    phase_hoe / phase_watering_can / spell_book
  src/main/resources/assets/qianxiang/textures/block/
    rift_stone / glimmer_log / glimmer_log_top / glimmer_leaves
    void_ore / wildlight_grass
  （forge_table 纹理由 generate_forge_table_textures.py 单独维护，本脚本不动。）

统一绚丽化管线（所有物品图标）：
  ① add_glow_edge   —— 按物品主色的 1~2px 外发光边缘（亮环+淡环双层）
  ② fill_gradient   —— 对角双向渐变（左上亮部高光 → 右下暗部深邃）+ 噪点
  ③ highlight_dots  —— 形状上缘 1~2 个亮色反光点（统一左上光源）
  ④ 材质纹理感      —— brushed_metal 金属拉丝 / gem_facets 宝石切面 /
                        magic_flow 魔法流光 / bone_texture 骨质纹理
  ⑤ tier_decor      —— 史诗（dragon_bone/salamander_gland）对角双星闪、
                        传奇（rift_essence）四角星闪 + 更强光晕 + 金边

风格参考 Botania / Thaumcraft / 血魔法：魔法梦幻感，绚丽但不糊。
运行后在 /tmp/qianxiang_item_preview/ 输出 16× 放大预览拼图，供自验。
"""

import random
from pathlib import Path

from PIL import Image, ImageFilter

S = 16  # 纹理边长


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


def new_canvas() -> Image.Image:
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def mask_from_pixels(pixels) -> Image.Image:
    """由 [(x,y), ...] 列表生成 L 模式掩码。"""
    m = Image.new("L", (S, S), 0)
    px = m.load()
    for x, y in pixels:
        if 0 <= x < S and 0 <= y < S:
            px[x, y] = 255
    return m


def mask_from_polygon(points) -> Image.Image:
    """由多边形顶点生成掩码（逐点扫描包含测试，16×16 足够快）。"""
    m = Image.new("L", (S, S), 0)
    px = m.load()
    n = len(points)

    def inside(x, y):
        # 射线法，测试像素中心
        cx, cy = x + 0.5, y + 0.5
        hit = False
        j = n - 1
        for i in range(n):
            xi, yi = points[i]
            xj, yj = points[j]
            if (yi > cy) != (yj > cy) and cx < (xj - xi) * (cy - yi) / (yj - yi) + xi:
                hit = not hit
            j = i
        return hit

    for y in range(S):
        for x in range(S):
            if inside(x, y):
                px[x, y] = 255
    return m


def fill_gradient(img: Image.Image, mask: Image.Image, c_light, c_dark,
                  diagonal: bool = True, noise: int = 7, seed: int = 1):
    """②在掩码区域内做 亮(左上)→暗(右下) 对角渐变并加噪点，形成体积感。"""
    px = img.load()
    mp = mask.load()
    rnd = random.Random(seed)
    for y in range(S):
        for x in range(S):
            if mp[x, y]:
                t = (x + y) / (2 * (S - 1)) if diagonal else y / (S - 1)
                base = lerp(c_light, c_dark, t)
                d = rnd.randint(-noise, noise)
                px[x, y] = (clamp(base[0] + d), clamp(base[1] + d),
                            clamp(base[2] + d), 255)


def add_outline(img: Image.Image, mask: Image.Image, color):
    """沿掩码外缘描 1px 深色边，让物品在背包里有清晰轮廓。"""
    dil = mask.filter(ImageFilter.MaxFilter(3))
    dp, mp, px = dil.load(), mask.load(), img.load()
    for y in range(S):
        for x in range(S):
            if dp[x, y] and not mp[x, y]:
                px[x, y] = (color[0], color[1], color[2], 255)


def add_halo(img: Image.Image, mask: Image.Image, color, strength: int = 110,
             radius: float = 1.2):
    """在形状背后加一圈半透明光晕（魔法/高阶材料用）。需在填充形状之前调用。"""
    blur = mask.filter(ImageFilter.GaussianBlur(radius))
    bp = blur.load()
    halo = new_canvas()
    hp = halo.load()
    for y in range(S):
        for x in range(S):
            a = bp[x, y] * strength // 255
            if a:
                hp[x, y] = (color[0], color[1], color[2], a)
    img.alpha_composite(halo)


def sparkle(img: Image.Image, x: int, y: int,
            core=(255, 255, 255), glow=(255, 255, 255), glow_alpha=110):
    """画一个 4 向星形闪光粒子。"""
    px = img.load()
    if 0 <= x < S and 0 <= y < S:
        px[x, y] = (core[0], core[1], core[2], 255)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < S and 0 <= ny < S:
            r, g, b, a = px[nx, ny]
            na = max(a, glow_alpha)
            # 叠加微光（保留底色，向 glow 色靠拢）
            px[nx, ny] = (clamp((r + glow[0]) // 2),
                          clamp((g + glow[1]) // 2),
                          clamp((b + glow[2]) // 2), na)


def set_px(img: Image.Image, x: int, y: int, color, alpha: int = 255):
    if 0 <= x < S and 0 <= y < S:
        img.load()[x, y] = (color[0], color[1], color[2], alpha)


def shade(img: Image.Image, pixels, factor: float):
    """把指定像素调暗（factor<1）或调亮（factor>1），用于棱线/阴影。"""
    px = img.load()
    for x, y in pixels:
        if 0 <= x < S and 0 <= y < S:
            r, g, b, a = px[x, y]
            if a:
                px[x, y] = (clamp(int(r * factor)), clamp(int(g * factor)),
                            clamp(int(b * factor)), a)


# -----------------------------------------------------------------------------
# 绚丽化管线（①外发光边缘 / ③高光点 / ④材质纹理 / ⑤稀有度星闪）
# -----------------------------------------------------------------------------

def add_glow_edge(img: Image.Image, mask: Image.Image, color,
                  inner: int = 185, outer: int = 75):
    """①外发光边缘：紧贴形状的 1px 亮环 + 第 2px 淡环，颜色取物品主色。
    需在形状填充之后、深色描边之前调用（描边会压住内圈，只留向外的一圈）。"""
    ring1 = mask.filter(ImageFilter.MaxFilter(3))
    ring2 = mask.filter(ImageFilter.MaxFilter(5))
    layer = new_canvas()
    lp, mp = layer.load(), mask.load()
    r1, r2 = ring1.load(), ring2.load()
    for y in range(S):
        for x in range(S):
            if mp[x, y]:
                continue
            a = inner if r1[x, y] else (outer if r2[x, y] else 0)
            if a:
                lp[x, y] = (color[0], color[1], color[2], a)
    img.alpha_composite(layer)


def highlight_dots(img: Image.Image, mask: Image.Image, color=(255, 255, 255),
                   second_factor: float = 1.45):
    """③高光点：形状最上缘放 1 个亮反光点 + 邻近次亮点（统一左上光源）。"""
    mp = mask.load()
    top = None
    for y in range(S):
        for x in range(S):
            if mp[x, y]:
                top = (x, y)
                break
        if top:
            break
    if not top:
        return
    x, y = top
    set_px(img, x, y, color)
    for nx, ny in ((x + 1, y), (x, y + 1), (x + 1, y + 1)):
        if 0 <= nx < S and 0 <= ny < S and mp[nx, ny]:
            shade(img, [(nx, ny)], second_factor)
            break


def brushed_metal(img: Image.Image, mask: Image.Image, seed: int,
                  light: float = 1.07, dark: float = 0.93):
    """④金属拉丝：横向细拉丝 streak（隔行微亮/微暗 + 噪点），只作用形状内部。"""
    px, mp = img.load(), mask.load()
    rnd = random.Random(seed)
    for y in range(S):
        f = light if y % 2 == 0 else dark
        for x in range(S):
            if mp[x, y]:
                r, g, b, a = px[x, y]
                if a:
                    d = rnd.randint(-3, 3)
                    px[x, y] = (clamp(int(r * f) + d), clamp(int(g * f) + d),
                                clamp(int(b * f) + d), a)


def gem_facets(img: Image.Image, pixels, color=(255, 255, 255), alpha: int = 235):
    """④宝石切面：在切面交线处画亮棱线，营造刻面宝石感。"""
    for x, y in pixels:
        set_px(img, x, y, color, alpha)


def bone_texture(img: Image.Image, mask: Image.Image, seed: int, count: int = 8,
                 color=(96, 82, 60)):
    """④骨质纹理：形状内部随机深色细点（骨小孔/钙质斑）。"""
    mp, px = mask.load(), img.load()
    rnd = random.Random(seed)
    cand = [(x, y) for y in range(S) for x in range(S) if mp[x, y]]
    rnd.shuffle(cand)
    for x, y in cand[:count]:
        r, g, b, a = px[x, y]
        if a:
            px[x, y] = ((r + color[0]) // 2, (g + color[1]) // 2,
                        (b + color[2]) // 2, a)


def magic_flow(img: Image.Image, points, c_bright, c_dim):
    """④魔法流光：沿路径点列明暗交替发光，像有能量在表面流动。"""
    for i, (x, y) in enumerate(points):
        set_px(img, x, y, c_bright if i % 2 == 0 else c_dim)


def tier_decor(img: Image.Image, tier: int):
    """⑤稀有度星闪：史诗(2) 对角双紫星，传奇(3) 四角金星闪。"""
    if tier >= 3:
        for x, y in ((1, 1), (S - 2, 1), (1, S - 2), (S - 2, S - 2)):
            sparkle(img, x, y, core=(255, 250, 224), glow=rgb("#FFE9A8"),
                    glow_alpha=170)
    elif tier == 2:
        sparkle(img, 1, 1, core=(242, 228, 255), glow=rgb("#C9A2FF"),
                glow_alpha=150)
        sparkle(img, S - 2, S - 2, core=(242, 228, 255), glow=rgb("#C9A2FF"),
                glow_alpha=150)


# -----------------------------------------------------------------------------
# 物品纹理 —— 材料
# -----------------------------------------------------------------------------

def tex_ingot(c_light, c_dark, seed, extra=None, glow=None) -> Image.Image:
    """通用金属锭：梯形锭体 + 对角渐变 + 金属拉丝 + 顶面高光 + 外发光边缘。"""
    rows = {
        4: (6, 9), 5: (5, 10),
        6: (4, 11), 7: (4, 11), 8: (3, 12), 9: (3, 12),
        10: (3, 12), 11: (4, 11),
    }
    pixels = [(x, y) for y, (x0, x1) in rows.items() for x in range(x0, x1 + 1)]
    mask = mask_from_pixels(pixels)
    img = new_canvas()
    fill_gradient(img, mask, c_light, c_dark, noise=5, seed=seed)
    brushed_metal(img, mask, seed + 1000)  # ④ 金属拉丝
    # 顶面（受光面）提亮
    top = [(x, y) for y in (4, 5) for x in range(rows[y][0], rows[y][1] + 1)]
    shade(img, top, 1.35)
    # 左侧棱线高光、底部压暗
    shade(img, [(rows[y][0], y) for y in (6, 7, 8, 9, 10)], 1.2)
    shade(img, [(x, 11) for x in range(4, 12)], 0.75)
    if extra:
        extra(img, mask)
    highlight_dots(img, mask)  # ③
    if glow:
        add_glow_edge(img, mask, glow)  # ①
    add_outline(img, mask, tuple(c // 4 for c in c_dark))
    return img


def tex_ember_iron() -> Image.Image:
    """烬铁：拉丝暗铁锭 + 炽热熔岩裂纹（白热核心）+ 暖橙外发光。"""
    def cracks(img, mask):
        mp = mask.load()
        glow = rgb("#FF7722")
        hot = rgb("#FFC84A")
        for x, y in [(5, 8), (6, 8), (7, 9), (8, 9), (9, 8), (6, 6), (10, 10)]:
            if mp[x, y]:
                set_px(img, x, y, glow)
        for x, y in [(6, 8), (8, 9)]:
            if mp[x, y]:
                set_px(img, x, y, hot)
        set_px(img, 6, 8, rgb("#FFF0B0"))  # 最热的白热核
    return tex_ingot(rgb("#C4C8D4"), rgb("#4A3E3A"), 11, cracks,
                     glow=rgb("#FF8830"))


def tex_abyss_iron() -> Image.Image:
    """沉潜铁：深渊冷铁，拉丝锭面 + 青蓝冷光流脉 + 青色外发光。"""
    def sheen(img, mask):
        mp = mask.load()
        for x, y in [(5, 6), (6, 6), (7, 7), (8, 8), (9, 9)]:
            if mp[x, y]:
                set_px(img, x, y, rgb("#3FC8C8"), 220)
        set_px(img, 6, 6, rgb("#B8FBF4"))  # 流脉高光
    return tex_ingot(rgb("#9AB8C8"), rgb("#24343E"), 12, sheen,
                     glow=rgb("#3FC8C8"))


def tex_ember_crystal() -> Image.Image:
    """余烬石：火晶簇，宝石切面棱线 + 白热核心 + 橙色光晕 + 外发光边缘。"""
    main = mask_from_polygon([(8, 1), (10, 4), (10, 9), (8, 13), (6, 9), (6, 4)])
    left = mask_from_polygon([(4, 6), (5, 8), (4, 12), (3, 9)])
    right = mask_from_polygon([(12, 5), (13, 8), (12, 11), (11, 8)])
    import PIL.ImageChops as Chops
    full = Chops.lighter(Chops.lighter(main, left), right)
    img = new_canvas()
    add_halo(img, main, rgb("#FF8830"), strength=100)
    fill_gradient(img, main, rgb("#FFC86A"), rgb("#C33E14"), noise=6, seed=21)
    fill_gradient(img, left, rgb("#FF9A4A"), rgb("#A83410"), noise=6, seed=22)
    fill_gradient(img, right, rgb("#FF9A4A"), rgb("#A83410"), noise=6, seed=23)
    # ④ 宝石切面：中央棱线 + 两侧刻面交线
    gem_facets(img, [(8, 2), (8, 3), (7, 5), (9, 5), (7, 8), (9, 8), (8, 11)],
               color=rgb("#FFE9C0"), alpha=210)
    # 白热核心
    for x, y in [(8, 4), (8, 5), (7, 6), (8, 6)]:
        set_px(img, x, y, rgb("#FFF0C0"))
    highlight_dots(img, main)  # ③
    add_glow_edge(img, full, rgb("#FF8830"))  # ①
    add_outline(img, main, rgb("#4A1408"))
    add_outline(img, left, rgb("#4A1408"))
    add_outline(img, right, rgb("#4A1408"))
    sparkle(img, 12, 2, glow=rgb("#FFB060"))
    return img


def tex_beast_fang() -> Image.Image:
    """兽牙：弯曲利齿，骨质纹理 + 牙根暗、牙尖亮 + 淡暖外发光。"""
    fang = mask_from_polygon([
        (5, 2), (8, 2), (9, 4), (11, 7), (12, 10), (11, 13),
        (9, 11), (8, 8), (6, 5), (4, 4),
    ])
    img = new_canvas()
    fill_gradient(img, fang, rgb("#F8F2E2"), rgb("#B09A70"), noise=5, seed=31)
    bone_texture(img, fang, seed=32, count=6, color=(120, 100, 72))  # ④
    highlight_dots(img, fang)  # ③（先打高光，牙根压暗会覆盖不到牙尖）
    # 牙根（顶部）暗色角质
    shade(img, [(5, 2), (6, 2), (7, 2), (8, 2), (5, 3), (6, 3), (7, 3), (8, 3)], 0.55)
    # 牙中线脊高光（④ 骨质脊线）
    shade(img, [(7, 4), (8, 6), (9, 8), (10, 10)], 1.18)
    # 牙尖高亮
    set_px(img, 11, 12, rgb("#FFFBF0"))
    set_px(img, 11, 13, rgb("#FFF8E8"))
    add_glow_edge(img, fang, rgb("#F4E8C8"), inner=120, outer=50)  # ①（低阶材料弱光）
    add_outline(img, fang, rgb("#3A2E1C"))
    return img


def tex_bloodroot() -> Image.Image:
    """血根：饮血红藤，主根 + 须蔓，暗红渐变 + 血珠高光 + 暗红外发光。"""
    root = mask_from_polygon([
        (7, 2), (9, 2), (10, 4), (9, 6), (9, 9), (8, 13),
        (7, 13), (7, 9), (6, 6), (6, 4),
    ])
    tendrils = mask_from_pixels([
        (5, 7), (4, 8), (3, 9), (10, 7), (11, 8), (12, 9),
        (6, 12), (5, 13), (9, 12), (10, 13), (4, 10), (11, 10),
    ])
    import PIL.ImageChops as Chops
    full = Chops.lighter(root, tendrils)
    img = new_canvas()
    fill_gradient(img, root, rgb("#D8404A"), rgb("#5E0E18"), noise=6, seed=41)
    fill_gradient(img, tendrils, rgb("#A82430"), rgb("#4E0A14"), noise=5, seed=42)
    # 根瘤与血色高光
    for x, y in [(7, 3), (8, 3), (7, 4), (8, 4)]:
        set_px(img, x, y, rgb("#E04850"))
    set_px(img, 7, 7, rgb("#E8605E"))
    set_px(img, 8, 10, rgb("#B02030"))
    # 血珠（③ 高光点变体：欲滴的亮血珠）
    set_px(img, 8, 4, rgb("#FF8888"))
    set_px(img, 4, 8, rgb("#E85860"))
    set_px(img, 11, 8, rgb("#E85860"))
    highlight_dots(img, root)  # ③
    add_glow_edge(img, full, rgb("#E03848"), inner=140, outer=55)  # ①
    add_outline(img, root, rgb("#2A040A"))
    add_outline(img, tendrils, rgb("#2A040A"))
    return img


def tex_dragon_bone() -> Image.Image:
    """龙骨：黑玉色对角骨骼，两端骨节膨大；史诗 → 紫色光晕 + 对角双星闪。"""
    shaft = [(4, 11), (5, 10), (6, 9), (7, 8), (8, 7), (9, 6), (10, 5), (11, 4),
             (4, 10), (5, 9), (6, 8), (7, 7), (8, 6), (9, 5), (10, 4)]
    knobs = [(2, 12), (3, 12), (2, 13), (3, 13), (3, 11), (12, 3), (12, 2), (11, 3), (11, 2), (10, 3)]
    mask = mask_from_pixels(shaft + knobs)
    img = new_canvas()
    add_halo(img, mask, rgb("#9A5AE0"), strength=135, radius=1.4)  # ⑤ 史诗紫晕加强
    fill_gradient(img, mask, rgb("#6A8078"), rgb("#161A1E"), noise=5, seed=51)
    bone_texture(img, mask, seed=52, count=6, color=(10, 14, 16))  # ④ 黑玉骨斑
    # 黑玉冷光棱线
    for x, y in [(5, 10), (6, 9), (7, 8), (8, 7), (9, 6)]:
        set_px(img, x, y, rgb("#3FC8B0"), 230)
    set_px(img, 7, 8, rgb("#A8FFF0"))  # 棱线最亮一节
    highlight_dots(img, mask)  # ③
    add_glow_edge(img, mask, rgb("#9A5AE0"))  # ①
    add_outline(img, mask, rgb("#06080A"))
    sparkle(img, 13, 5, glow=rgb("#B080F0"))
    tier_decor(img, 2)  # ⑤ 史诗双星
    return img


def tex_salamander_gland() -> Image.Image:
    """火蜥蜴腺体：灼热搏动的腺体，炽热核心；史诗 → 紫色光晕 + 对角双星闪。"""
    blob = mask_from_polygon([
        (8, 3), (10, 4), (12, 6), (12, 9), (10, 12), (7, 12),
        (5, 10), (4, 7), (5, 5), (7, 3),
    ])
    img = new_canvas()
    add_halo(img, blob, rgb("#9A5AE0"), strength=135, radius=1.4)  # ⑤ 史诗紫晕加强
    fill_gradient(img, blob, rgb("#F07848"), rgb("#7A180E"), noise=7, seed=61)
    # 炽热核心（搏动囊）：白热心 + 金核 + 外围熔橙
    for x, y in [(7, 7), (10, 8), (8, 9), (9, 6)]:
        set_px(img, x, y, rgb("#FF9838"))
    for x, y in [(8, 7), (9, 7), (8, 8), (9, 8), (7, 8)]:
        set_px(img, x, y, rgb("#FFC23E"))
    set_px(img, 8, 8, rgb("#FFF0A8"))
    set_px(img, 9, 7, rgb("#FFF8CC"))
    # 膜质褶皱
    shade(img, [(6, 5), (5, 7), (6, 10), (10, 10), (11, 8)], 0.7)
    highlight_dots(img, blob)  # ③
    add_glow_edge(img, blob, rgb("#FF7038"))  # ①
    add_outline(img, blob, rgb("#330802"))
    sparkle(img, 12, 4, glow=rgb("#FF9040"))
    tier_decor(img, 2)  # ⑤ 史诗双星
    return img


def tex_rift_essence() -> Image.Image:
    """裂隙精髓：星砂碎晶簇；传奇 → 金边 + 强紫晕 + 四角星闪 + 宝石切面。"""
    # 三枚碎晶紧挨成簇，保证金边连续
    s1 = mask_from_polygon([(8, 2), (10, 5), (10, 9), (8, 12), (6, 9), (6, 5)])
    s2 = mask_from_polygon([(5, 7), (6, 9), (5, 13), (3, 13), (3, 10), (4, 8)])
    s3 = mask_from_polygon([(11, 6), (13, 9), (12, 13), (10, 13), (10, 9)])
    import PIL.ImageChops as Chops
    mask = Chops.lighter(Chops.lighter(s1, s2), s3)
    img = new_canvas()
    add_halo(img, mask, rgb("#9A5AE0"), strength=160, radius=1.6)  # ⑤ 传奇强紫晕
    fill_gradient(img, s1, rgb("#D0B4FA"), rgb("#5E2EA0"), noise=5, seed=71)
    fill_gradient(img, s2, rgb("#B090F0"), rgb("#4E2890"), noise=5, seed=72)
    fill_gradient(img, s3, rgb("#B090F0"), rgb("#4E2890"), noise=5, seed=73)
    # ④ 宝石切面棱线
    gem_facets(img, [(8, 3), (7, 6), (9, 6), (8, 9), (4, 10), (11, 9)],
               color=rgb("#E8D8FF"), alpha=215)
    # 星核
    set_px(img, 8, 5, rgb("#F4E8FF"))
    set_px(img, 8, 6, rgb("#E0CCFF"))
    highlight_dots(img, s1)  # ③
    add_glow_edge(img, mask, rgb("#B080F0"), inner=200, outer=95)  # ① 传奇更强
    # 传奇金边
    add_outline(img, mask, rgb("#D8A828"))
    sparkle(img, 12, 2, core=(255, 255, 255), glow=rgb("#E8D0FF"))
    sparkle(img, 3, 9, core=(255, 244, 200), glow=rgb("#D8A828"), glow_alpha=90)
    tier_decor(img, 3)  # ⑤ 四角金星闪
    return img


def tex_shadowhide_patch() -> Image.Image:
    """暗鞣皮：深渊鞣制皮甲片，缝线 + 冷光 + 微紫外发光。"""
    patch = mask_from_polygon([
        (5, 4), (10, 4), (12, 6), (12, 10), (10, 12), (5, 12), (3, 10), (3, 6),
    ])
    img = new_canvas()
    fill_gradient(img, patch, rgb("#5E5670"), rgb("#1C1822"), noise=6, seed=81)
    # 缝线（内圈点线）
    stitches = [(5, 5), (7, 5), (9, 5), (11, 7), (11, 9), (9, 11), (7, 11), (5, 11), (4, 9), (4, 7)]
    for x, y in stitches:
        set_px(img, x, y, rgb("#7E7490"))
    # 左上角冷光
    shade(img, [(5, 4), (6, 4), (4, 5), (5, 5), (4, 6)], 1.45)
    # 中央相光铆钉（冷光点缀）
    set_px(img, 7, 7, rgb("#3FD8C8"))
    set_px(img, 8, 8, rgb("#2AA8A0"))
    highlight_dots(img, patch)  # ③
    add_glow_edge(img, patch, rgb("#6E5AA8"), inner=130, outer=55)  # ①
    add_outline(img, patch, rgb("#0A080E"))
    return img


def tex_glimmer_wood_sap() -> Image.Image:
    """闪光木汁：活体树液滴，暖金绿光 + 发光核心 + 外发光边缘。"""
    drop = mask_from_polygon([
        (8, 2), (10, 5), (11, 8), (11, 10), (9, 13), (6, 13), (4, 10), (4, 8), (6, 5),
    ])
    img = new_canvas()
    add_halo(img, drop, rgb("#A8E84A"), strength=105)
    fill_gradient(img, drop, rgb("#F0F8A0"), rgb("#5E8A20"), noise=6, seed=91)
    # 发光核心
    for x, y in [(7, 9), (8, 9), (7, 10), (8, 10)]:
        set_px(img, x, y, rgb("#F8FFC0"))
    set_px(img, 8, 9, rgb("#FEFFEA"))
    # 高光
    set_px(img, 6, 6, rgb("#FFFFF0"))
    set_px(img, 7, 5, rgb("#F4FFD8"))
    highlight_dots(img, drop)  # ③
    add_glow_edge(img, drop, rgb("#A8E84A"))  # ①
    add_outline(img, drop, rgb("#2A4010"))
    sparkle(img, 12, 6, glow=rgb("#C8F070"))
    return img


def tex_myriad_fragment() -> Image.Image:
    """森罗残片：森罗维度碎片，青碧碎晶 + 宝石切面 + 微光。"""
    s1 = mask_from_polygon([(7, 3), (9, 5), (9, 9), (7, 11), (5, 9), (5, 5)])
    s2 = mask_from_polygon([(11, 6), (12, 8), (11, 11), (10, 9)])
    s3 = mask_from_polygon([(4, 10), (5, 12), (4, 14), (3, 12)])
    img = new_canvas()
    import PIL.ImageChops as Chops
    mask = Chops.lighter(Chops.lighter(s1, s2), s3)
    add_halo(img, mask, rgb("#3FD8C0"), strength=110)
    fill_gradient(img, s1, rgb("#A0F0DC"), rgb("#1E7A68"), noise=5, seed=101)
    fill_gradient(img, s2, rgb("#8AE0CC"), rgb("#1A6A5A"), noise=5, seed=102)
    fill_gradient(img, s3, rgb("#8AE0CC"), rgb("#1A6A5A"), noise=5, seed=103)
    # ④ 宝石切面棱线
    gem_facets(img, [(7, 4), (6, 7), (8, 7), (7, 9)], color=rgb("#D8FFF4"), alpha=210)
    set_px(img, 7, 6, rgb("#E8FFF4"))
    set_px(img, 7, 7, rgb("#C8F8E8"))
    highlight_dots(img, s1)  # ③
    add_glow_edge(img, mask, rgb("#3FD8C0"))  # ①
    add_outline(img, mask, rgb("#0A3028"))
    sparkle(img, 12, 3, glow=rgb("#80E8D0"))
    return img


# -----------------------------------------------------------------------------
# 物品纹理 —— 武器（对角构图：柄在左下、尖在右上）
# -----------------------------------------------------------------------------

def tex_ember_blade() -> Image.Image:
    """灼烧之刃 v3：拉丝钢刃 + 白热刃缘渐变 + 金柄 + 橙色外发光边缘。"""
    # 三列对角刃体：上脊（亮）/ 中脊 / 下缘（炽热）
    spine = [(5 + i, 10 - i) for i in range(7)]          # (5,10)..(11,4)
    mid = [(6 + i, 10 - i) for i in range(7)]            # (6,10)..(12,4)
    edge = [(5 + i, 9 - i) for i in range(7)]            # (5,9)..(11,3)
    tip = [(12, 3), (13, 2), (12, 2)]
    guard = [(3, 8), (4, 9), (6, 11), (7, 12)]
    grip = [(4, 11), (3, 12), (2, 13)]
    pommel = [(1, 14)]
    blade = mask_from_pixels(spine + mid + edge + tip)
    hilt = mask_from_pixels(guard + grip + pommel)
    full = mask_from_pixels(spine + mid + edge + tip + guard + grip + pommel)
    img = new_canvas()
    add_halo(img, blade, rgb("#FF7020"), strength=95)
    fill_gradient(img, blade, rgb("#EAEEF8"), rgb("#5A5E6E"), noise=3, seed=111)
    brushed_metal(img, blade, seed=113, light=1.05, dark=0.95)  # ④ 金属拉丝
    # 刃体中脊压出凹槽层次
    shade(img, mid[1:-1], 0.82)
    # 上脊高光棱线
    for x, y in spine[1:]:
        set_px(img, x, y, rgb("#F6F8FF"))
    # 下缘炽热渐变（根→尖 橙→金→白热尖）
    c_root, c_tip = rgb("#FF5A1A"), rgb("#FFC84A")
    for i, (x, y) in enumerate(edge):
        set_px(img, x, y, lerp(c_root, c_tip, i / 6))
    set_px(img, 12, 3, rgb("#FFE0A0"))
    set_px(img, 13, 2, rgb("#FFFBEC"))  # 白热尖端
    set_px(img, 12, 2, rgb("#FFE4A8"))
    # 护手金、柄缠革（两道缠纹）
    fill_gradient(img, hilt, rgb("#E0B848"), rgb("#6A4A14"), noise=4, seed=112)
    for x, y in grip:
        set_px(img, x, y, rgb("#4A3220"))
    set_px(img, 3, 12, rgb("#6A4A2A"))
    set_px(img, 1, 14, rgb("#D8A828"))
    add_glow_edge(img, full, rgb("#FF8830"))  # ①
    add_outline(img, full, rgb("#1A1410"))
    sparkle(img, 14, 1, glow=rgb("#FFB060"), glow_alpha=90)
    return img


def tex_phase_staff() -> Image.Image:
    """相杖 v3：缠金木杖（魔法流光箍）+ 四向尖角悬浮法球（十字闪光）+ 环绕浮游符文。"""
    rod = [(2, 13), (3, 12), (4, 11), (5, 10), (6, 9), (7, 8), (8, 7), (9, 6)]
    rod_mask = mask_from_pixels(rod)
    orb_core = [(11, 3), (12, 3), (13, 3), (11, 4), (12, 4), (13, 4),
                (11, 5), (12, 5), (13, 5)]
    orb_spikes = [(12, 2), (10, 4), (14, 4), (12, 6)]
    orb = mask_from_pixels(orb_core + orb_spikes)
    import PIL.ImageChops as Chops
    full = Chops.lighter(rod_mask, orb)
    img = new_canvas()
    add_halo(img, orb, rgb("#9A5AE0"), strength=170, radius=1.7)
    fill_gradient(img, rod_mask, rgb("#967A4E"), rgb("#3E2A18"), noise=5, seed=121)
    # ④ 魔法流光金属箍（明暗交替，像能量在杖身流动）
    magic_flow(img, [(4, 11), (6, 9), (8, 7), (9, 6)],
               rgb("#E8C85A"), rgb("#A87A24"))
    # 法球：四角青碧、棱面亮紫、白热核心
    for x, y in [(11, 3), (13, 3), (11, 5), (13, 5)]:
        set_px(img, x, y, rgb("#5AD8D0"))
    for x, y in [(12, 3), (11, 4), (13, 4), (12, 5)]:
        set_px(img, x, y, rgb("#C8A8F8"))
    set_px(img, 12, 4, rgb("#FFFFFF"))
    # 四向尖角
    for x, y in orb_spikes:
        set_px(img, x, y, rgb("#7FE8E0"))
    set_px(img, 12, 2, rgb("#D8FFF8"))
    # 浮游符文（提亮 + 微光）
    sparkle(img, 9, 2, core=(216, 188, 255), glow=rgb("#9A5AE0"), glow_alpha=100)
    sparkle(img, 15, 7, core=(216, 188, 255), glow=rgb("#9A5AE0"), glow_alpha=100)
    set_px(img, 10, 7, rgb("#C8A8F8"))
    add_glow_edge(img, full, rgb("#9A5AE0"), inner=160, outer=65)  # ①
    add_outline(img, rod_mask, rgb("#1E1208"))
    add_outline(img, orb, rgb("#2A1050"))
    sparkle(img, 15, 2, glow=rgb("#C0A0FF"))
    return img


def tex_bone_blade() -> Image.Image:
    """骨刃 v3：双刃体锯齿骨刀，骨白渐变 + 骨质纹理 + 暗裂纹 + 缠革柄（微光，与高阶武器拉开档次）。"""
    spine = [(4 + i, 10 - i) for i in range(8)]          # (4,10)..(11,3)
    edge = [(5 + i, 10 - i) for i in range(8)]           # (5,10)..(12,3)
    # 锯齿：刃缘外侧交错突齿
    teeth = [(6, 10), (8, 8), (10, 6), (12, 4)]
    tip = [(12, 2), (13, 2), (13, 1)]
    grip = [(3, 11), (2, 12), (1, 13)]
    wrap = [(4, 11), (3, 12), (2, 13)]
    mask = mask_from_pixels(spine + edge + teeth + tip + grip)
    img = new_canvas()
    fill_gradient(img, mask, rgb("#F6F0E0"), rgb("#94866A"), noise=4, seed=131)
    bone_texture(img, mask, seed=132, count=9, color=(110, 96, 72))  # ④ 骨质纹理
    # 骨面棱脊高光
    for x, y in spine[1:-1]:
        set_px(img, x, y, rgb("#FFFBEE"))
    # 暗裂纹
    for x, y in [(7, 7), (9, 5), (6, 8), (8, 7)]:
        set_px(img, x, y, rgb("#5E5442"))
    # 缠革柄（深浅缠纹交错）
    for x, y in grip + wrap:
        set_px(img, x, y, rgb("#4A3626"))
    set_px(img, 3, 12, rgb("#6A4E34"))
    set_px(img, 2, 12, rgb("#6A4E34"))
    # 齿尖与骨刺尖亮
    for x, y in teeth + tip:
        set_px(img, x, y, rgb("#FFFBEE"))
    highlight_dots(img, mask)  # ③
    add_glow_edge(img, mask, rgb("#E8DCC0"), inner=110, outer=45)  # ①（原始武器弱光）
    add_outline(img, mask, rgb("#2E2618"))
    return img


def tex_phase_shield() -> Image.Image:
    """相盾 v3：加长熨斗盾，钢缘分层 + 盾面板块 + 四角相光符记 + 中央相核宝石（十字闪光）。"""
    rows = {
        2: (6, 9), 3: (4, 11),
        4: (3, 12), 5: (3, 12), 6: (3, 12), 7: (3, 12), 8: (3, 12), 9: (3, 12),
        10: (4, 11), 11: (5, 10), 12: (6, 9), 13: (7, 8),
    }
    pixels = [(x, y) for y, (x0, x1) in rows.items() for x in range(x0, x1 + 1)]
    mask = mask_from_pixels(pixels)
    # 盾缘 = 每行两端像素 + 首末行
    rim_pixels = []
    for y, (x0, x1) in rows.items():
        rim_pixels += [(x0, y), (x1, y)]
        if y in (2, 13):
            rim_pixels += [(x, y) for x in range(x0, x1 + 1)]
    rim = mask_from_pixels(rim_pixels)
    img = new_canvas()
    add_halo(img, mask, rgb("#5A4AE0"), strength=90)
    fill_gradient(img, mask, rgb("#5E7494"), rgb("#1E2A40"), noise=4, seed=141)
    brushed_metal(img, mask, seed=142, light=1.04, dark=0.96)  # ④ 钢面拉丝
    # 钢缘分层：亮钢 + 左上受光
    rp = rim.load()
    for y in range(S):
        for x in range(S):
            if rp[x, y]:
                set_px(img, x, y, rgb("#94A6BC"))
    shade(img, [(x, y) for x, y in rim_pixels if x <= 4 or y <= 3], 1.25)
    # 盾面纵向高光带与横向板块缝
    shade(img, [(5, 4), (5, 5), (5, 6), (5, 7), (5, 8), (5, 9)], 1.18)
    shade(img, [(x, 9) for x in range(4, 12)], 0.82)
    # 四角相光符记（带微光）
    for x, y in [(4, 4), (11, 4), (4, 9), (11, 9)]:
        sparkle(img, x, y, core=(140, 245, 235), glow=rgb("#3FD8C8"), glow_alpha=90)
    # 中央相核宝石：菱形紫核 + 白热心 + ④ 切面棱线 + 十字闪光
    for x, y in [(7, 5), (8, 5), (6, 6), (9, 6), (7, 7), (8, 7)]:
        set_px(img, x, y, rgb("#9A5AE0"))
    set_px(img, 7, 6, rgb("#F0E6FF"))
    set_px(img, 8, 6, rgb("#C8A8F8"))
    gem_facets(img, [(7, 5), (6, 6)], color=rgb("#E8D8FF"), alpha=200)
    sparkle(img, 8, 6, core=(255, 255, 255), glow=rgb("#C8A8F8"), glow_alpha=140)
    highlight_dots(img, mask)  # ③
    add_glow_edge(img, mask, rgb("#5A6AE0"))  # ①
    add_outline(img, mask, rgb("#121A24"))
    sparkle(img, 13, 3, glow=rgb("#A0B8E0"), glow_alpha=90)
    return img


# -----------------------------------------------------------------------------
# 物品纹理 —— 工具
# -----------------------------------------------------------------------------

def tex_phase_hoe() -> Image.Image:
    """相锄：青钢锄刃（右上钩刃）+ 缠革木柄，对角构图 + 青色外发光。"""
    # 锄刃：柄顶端向右伸出的钩形刃
    head = [(10, 5), (11, 4), (12, 3), (11, 3), (12, 2), (13, 2), (13, 3), (13, 4)]
    edge = [(14, 2), (14, 3), (14, 4), (13, 5)]  # 刃缘（受光青钢）
    handle = [(9, 6), (8, 7), (7, 8), (6, 9), (5, 10), (4, 11), (3, 12), (2, 13)]
    wrap = [(8, 7), (5, 10), (3, 12)]
    head_mask = mask_from_pixels(head + edge)
    handle_mask = mask_from_pixels(handle)
    import PIL.ImageChops as Chops
    full = Chops.lighter(head_mask, handle_mask)
    img = new_canvas()
    add_halo(img, head_mask, rgb("#3FD8C8"), strength=85)
    # 锄刃：青钢对角渐变 + 拉丝
    fill_gradient(img, head_mask, rgb("#C8D8E4"), rgb("#4A5A6A"), noise=3, seed=151)
    brushed_metal(img, head_mask, seed=152, light=1.05, dark=0.95)
    # 刃缘相光青
    for x, y in edge:
        set_px(img, x, y, rgb("#3FD8C8"))
    set_px(img, 14, 2, rgb("#B8FBF4"))  # 刃尖高光
    # 木柄 + 缠革
    fill_gradient(img, handle_mask, rgb("#8A6A42"), rgb("#3E2A18"), noise=4, seed=153)
    for x, y in wrap:
        set_px(img, x, y, rgb("#4A3220"))
    # 柄首金箍
    set_px(img, 9, 6, rgb("#D8B048"))
    set_px(img, 2, 13, rgb("#D8A828"))
    highlight_dots(img, head_mask)  # ③
    add_glow_edge(img, full, rgb("#3FD8C8"), inner=150, outer=60)  # ①
    add_outline(img, full, rgb("#141210"))
    sparkle(img, 15, 1, glow=rgb("#80E8D8"), glow_alpha=90)
    return img


def tex_phase_watering_can() -> Image.Image:
    """相之浇水壶：青银壶身 + 黄铜箍带 + 斜提梁 + 壶嘴发光水珠。"""
    # 壶身（圆角罐体）
    rows = {
        8: (5, 11), 9: (4, 11), 10: (4, 11), 11: (4, 11),
        12: (4, 11), 13: (5, 11),
    }
    body_px = [(x, y) for y, (x0, x1) in rows.items() for x in range(x0, x1 + 1)]
    body = mask_from_pixels(body_px)
    # 提梁（顶部半环）与壶盖
    handle = mask_from_pixels([(6, 6), (7, 5), (8, 5), (9, 6), (7, 7), (8, 7)])
    # 壶嘴（右下斜出）与嘴口
    spout = mask_from_pixels([(12, 10), (13, 9), (14, 8), (14, 7), (13, 10), (14, 9)])
    import PIL.ImageChops as Chops
    full = Chops.lighter(Chops.lighter(body, handle), spout)
    img = new_canvas()
    add_halo(img, body, rgb("#3FC8C8"), strength=75)
    # 壶身：青银对角渐变 + 拉丝
    fill_gradient(img, body, rgb("#B8CCD8"), rgb("#3E5566"), noise=4, seed=155)
    brushed_metal(img, body, seed=156, light=1.05, dark=0.95)
    # 黄铜箍带（上下两道）
    for x in range(5, 12):
        set_px(img, x, 9, rgb("#C89830"))
    for x in range(5, 12):
        set_px(img, x, 12, rgb("#A87A24"))
    set_px(img, 4, 9, rgb("#C89830"))
    set_px(img, 4, 12, rgb("#A87A24"))
    # 壶身中央相光符记
    set_px(img, 7, 10, rgb("#3FD8C8"))
    set_px(img, 8, 10, rgb("#3FD8C8"))
    set_px(img, 7, 11, rgb("#2AA8A0"))
    set_px(img, 8, 11, rgb("#9FF5EC"))
    # 提梁与壶盖：黄铜
    fill_gradient(img, handle, rgb("#E0B848"), rgb("#7A5A1A"), noise=3, seed=157)
    # 壶嘴：青银 + 嘴口相光
    fill_gradient(img, spout, rgb("#A8C0D0"), rgb("#3A5060"), noise=3, seed=158)
    set_px(img, 14, 7, rgb("#3FD8C8"))
    # 发光水珠（壶嘴滴落的相光水）
    sparkle(img, 15, 6, core=(220, 255, 252), glow=rgb("#3FD8C8"), glow_alpha=110)
    set_px(img, 15, 10, rgb("#9FF5EC"))
    set_px(img, 13, 12, rgb("#5AD8D0"))
    highlight_dots(img, body)  # ③
    add_glow_edge(img, full, rgb("#3FC8C8"), inner=140, outer=55)  # ①
    add_outline(img, full, rgb("#10181E"))
    return img


# -----------------------------------------------------------------------------
# 物品纹理 —— 法术书
# -----------------------------------------------------------------------------

def tex_spell_book() -> Image.Image:
    """千相法术书：紫革封面 + 烫金书脊 + 封面中央九相元素符文（魔法流光）+ 外发光。"""
    # 书体（正面朝上的合拢书）
    rows = {
        2: (4, 12), 3: (3, 13), 4: (3, 13), 5: (3, 13), 6: (3, 13),
        7: (3, 13), 8: (3, 13), 9: (3, 13), 10: (3, 13), 11: (3, 13),
        12: (3, 13), 13: (4, 12),
    }
    pixels = [(x, y) for y, (x0, x1) in rows.items() for x in range(x0, x1 + 1)]
    mask = mask_from_pixels(pixels)
    img = new_canvas()
    add_halo(img, mask, rgb("#9A5AE0"), strength=125)
    fill_gradient(img, mask, rgb("#6A44A0"), rgb("#241436"), noise=5, seed=161)
    # 书脊（左侧烫金线 + 页边）
    spine = [(3, y) for y in range(3, 13)]
    for x, y in spine:
        set_px(img, x, y, rgb("#D8A828"))
    set_px(img, 3, 7, rgb("#F4D878"))  # 书脊烫金高光
    # 书页边（右侧浅色）
    for y in range(3, 13):
        set_px(img, 13, y, rgb("#D8CBA8"))
    set_px(img, 12, 2, rgb("#D8CBA8"))
    set_px(img, 12, 13, rgb("#D8CBA8"))
    # 封面内框（暗金描边）
    frame = [(x, 4) for x in range(5, 12)] + [(x, 11) for x in range(5, 12)] \
        + [(5, y) for y in range(4, 12)] + [(11, y) for y in range(4, 12)]
    for x, y in frame:
        set_px(img, x, y, rgb("#8A6A20"))
    # 中央元素符文：菱形九芒阵（④ 魔法流光：核心亮紫 + 四点星芒明暗交替）
    rune_core = [(8, 7), (7, 8), (8, 8), (9, 8), (8, 9)]
    for x, y in rune_core:
        set_px(img, x, y, rgb("#C8A8F8"))
    set_px(img, 8, 8, rgb("#F4E8FF"))
    magic_flow(img, [(6, 8), (10, 8)], rgb("#9FF5EC"), rgb("#5AD8D0"))
    sparkle(img, 8, 6, core=(240, 230, 255), glow=rgb("#C8A8F8"), glow_alpha=140)
    sparkle(img, 12, 3, core=(255, 244, 200), glow=rgb("#D8A828"), glow_alpha=100)
    highlight_dots(img, mask)  # ③
    add_glow_edge(img, mask, rgb("#9A5AE0"))  # ①
    add_outline(img, mask, rgb("#0E0818"))
    return img


def tex_reverse_core() -> Image.Image:
    """逆相之核：阴阳/反转符号——S 曲线分半的圆，紫青双色相反相成，
    各含对方色的小圆点；传奇 → 金边 + 紫青双色光晕 + 四角星闪。"""
    import math
    cx, cy, r = 7.5, 7.5, 6.0
    purple_l, purple_d = rgb("#C8A0F8"), rgb("#5E2EA0")
    cyan_l, cyan_d = rgb("#7FE8E0"), rgb("#1A7A72")
    mask = Image.new("L", (S, S), 0)
    mp = mask.load()
    side = {}  # (x,y) -> True=紫半 / False=青半
    for y in range(S):
        for x in range(S):
            dx, dy = x - cx, y - cy
            if dx * dx + dy * dy <= r * r:
                mp[x, y] = 255
                # 阴阳 S 分界：x 相对中线偏移随 y 正弦摆动
                side[(x, y)] = x < cx + 2.2 * math.sin((y - cy) / r * math.pi)
    img = new_canvas()
    # ⑤ 传奇双色光晕：紫晕偏左、青晕偏右（先紫后青叠加）
    add_halo(img, mask, rgb("#9A5AE0"), strength=120, radius=1.6)
    add_halo(img, mask, rgb("#3FD8C8"), strength=90, radius=2.1)
    # 两半相反色对角渐变填充
    purple_mask = mask_from_pixels([p for p, v in side.items() if v])
    cyan_mask = mask_from_pixels([p for p, v in side.items() if not v])
    fill_gradient(img, purple_mask, purple_l, purple_d, noise=5, seed=171)
    fill_gradient(img, cyan_mask, cyan_l, cyan_d, noise=5, seed=172)
    # 阴阳鱼眼：各含对方色的亮核小圆点（上半青点、下半紫点）
    for ex, ey, c_core, c_dim in ((7, 4, rgb("#B8FBF4"), rgb("#3FD8C8")),
                                  (8, 11, rgb("#E0CCFF"), rgb("#9A5AE0"))):
        set_px(img, ex, ey, c_core)
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = ex + dx, ey + dy
            if (nx, ny) in side:
                set_px(img, nx, ny, c_dim)
    # S 分界提亮一道亮银细分形线
    for y in range(2, 14):
        bx = int(round(cx + 2.2 * math.sin((y - cy) / r * math.pi)))
        if (bx, y) in side:
            shade(img, [(bx, y)], 1.3)
    # ③ 高光点（紫半上缘）
    highlight_dots(img, purple_mask)
    # ① 双色外发光边缘
    add_glow_edge(img, mask, rgb("#B080F0"), inner=170, outer=70)
    # 传奇金边
    add_outline(img, mask, rgb("#D8A828"))
    # ⑤ 四角金星闪
    tier_decor(img, 3)
    return img


# -----------------------------------------------------------------------------
# 方块纹理
# -----------------------------------------------------------------------------

def fill_noise(img: Image.Image, c_light, c_dark, noise=14, seed=1, mask=None):
    """全图噪点渐变填充（方块基底用）。"""
    px = img.load()
    mp = mask.load() if mask else None
    rnd = random.Random(seed)
    for y in range(S):
        for x in range(S):
            if mp and not mp[x, y]:
                continue
            t = y / (S - 1)
            base = lerp(c_light, c_dark, t)
            d = rnd.randint(-noise, noise)
            px[x, y] = (clamp(base[0] + d), clamp(base[1] + d),
                        clamp(base[2] + d), 255)


def block_rift_stone() -> Image.Image:
    """裂隙石：深色板岩砖 + 内发光脉动裂隙（亮核强弱交替 + 双层辉光）。"""
    img = new_canvas()
    fill_noise(img, rgb("#3E4452"), rgb("#23262E"), noise=10, seed=201)
    px = img.load()
    # 砖缝（仿 deepslate tiles）
    for i in range(S):
        px[7, i] = (20, 22, 28, 255)
        px[8, i] = (20, 22, 28, 255)
        px[i, 3] = (20, 22, 28, 255)
        px[i, 11] = (20, 22, 28, 255)
    # 裂隙主干：锯齿斜线，近黑
    crack = [(3, 0), (3, 1), (4, 2), (4, 3), (5, 4), (5, 5), (6, 6), (6, 7),
             (7, 8), (7, 9), (8, 10), (9, 11), (9, 12), (10, 13), (10, 14), (11, 15)]
    crack_set = set(crack)
    for x, y in crack:
        px[x, y] = (8, 6, 14, 255)
    # 裂隙两侧第一层紫色辉光（强）
    glow1 = []
    for x, y in crack:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < S and 0 <= ny < S and (nx, ny) not in crack_set:
                glow1.append((nx, ny))
    for nx, ny in glow1:
        r, g, b, a = px[nx, ny]
        px[nx, ny] = (clamp((r + 130) // 2), clamp((g + 64) // 2),
                      clamp((b + 205) // 2), 255)
    # 第二层淡辉光（内发光外溢）
    glow1_set = set(glow1)
    for nx, ny in glow1:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            mx, my = nx + dx, ny + dy
            if 0 <= mx < S and 0 <= my < S and (mx, my) not in crack_set \
                    and (mx, my) not in glow1_set:
                r, g, b, a = px[mx, my]
                px[mx, my] = (clamp((2 * r + 120) // 3), clamp((2 * g + 60) // 3),
                              clamp((2 * b + 190) // 3), 255)
    # 裂隙中的脉动亮核（强弱交替，像能量沿裂隙搏动）
    for i, (x, y) in enumerate(crack):
        if i % 3 == 1:
            px[x, y] = (196, 130, 255, 255)       # 强核
        elif i % 3 == 2:
            px[x, y] = (120, 70, 190, 255)        # 弱核
    for x, y in [(4, 3), (7, 8), (10, 13)]:
        px[x, y] = (226, 178, 255, 255)           # 最强搏动点
    return img


def block_glimmer_log() -> Image.Image:
    """闪光原木侧面：竖纹树皮 + 发光绿脉（结点光晕加强）。"""
    img = new_canvas()
    rnd = random.Random(211)
    px = img.load()
    grooves = {2, 3, 7, 8, 12, 13}
    for y in range(S):
        for x in range(S):
            if x in grooves:
                base = rgb("#3A2A18")
            else:
                base = rgb("#5E4628") if (x + rnd.randint(0, 2)) % 3 else rgb("#6E5230")
            d = rnd.randint(-8, 8)
            px[x, y] = (clamp(base[0] + d), clamp(base[1] + d), clamp(base[2] + d), 255)
    # 发光树脉（两条竖向断续亮绿脉）
    for y in [1, 2, 4, 5, 6, 9, 10, 13, 14]:
        px[5, y] = (150, 230, 90, 255)
        if y + 1 < S:
            r, g, b, a = px[6, y]
            px[6, y] = ((r + 150) // 2, (g + 220) // 2, (b + 90) // 2, 255)
    for y in [0, 3, 7, 8, 11, 12, 15]:
        px[10, y] = (140, 220, 80, 255)
    # 脉结点高亮 + 四邻光晕（内发光感）
    for nx, ny in [(5, 5), (10, 8)]:
        px[nx, ny] = (230, 255, 170, 255)
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            mx, my = nx + dx, ny + dy
            if 0 <= mx < S and 0 <= my < S:
                r, g, b, a = px[mx, my]
                px[mx, my] = ((r + 180) // 2, (g + 240) // 2, (b + 120) // 2, 255)
    return img


def block_glimmer_log_top() -> Image.Image:
    """闪光原木横截面：年轮 + 绿色树心（光晕外溢）。"""
    img = new_canvas()
    px = img.load()
    rnd = random.Random(221)
    cx = cy = 7.5
    for y in range(S):
        for x in range(S):
            dist = max(abs(x - cx), abs(y - cy))
            if dist > 7:
                px[x, y] = (46, 34, 20, 255)  # 外圈树皮
            else:
                ring = int(dist) % 2
                base = rgb("#9A784A") if ring else rgb("#7E5E36")
                d = rnd.randint(-7, 7)
                px[x, y] = (clamp(base[0] + d), clamp(base[1] + d), clamp(base[2] + d), 255)
    # 发光树心 + 四邻光晕
    for x, y in [(7, 7), (8, 7), (7, 8), (8, 8)]:
        px[x, y] = (190, 255, 130, 255)
    px[7, 7] = (240, 255, 190, 255)
    for x, y in [(6, 7), (9, 7), (7, 6), (8, 9), (6, 8), (9, 8), (7, 9), (8, 6)]:
        r, g, b, a = px[x, y]
        px[x, y] = ((r + 170) // 2, (g + 245) // 2, (b + 110) // 2, 255)
    return img


def block_glimmer_leaves() -> Image.Image:
    """闪光树叶：层叠叶簇 + 透光孔 + 成簇发光光点（带微光晕）。"""
    img = new_canvas()
    rnd = random.Random(231)
    px = img.load()
    for y in range(S):
        for x in range(S):
            r = rnd.random()
            if r < 0.10:
                px[x, y] = (0, 0, 0, 0)  # 透光孔（仿原版树叶）
                continue
            base = rgb("#3E7A28") if rnd.random() < 0.5 else rgb("#2E5E1C")
            if (x + y) % 4 == 0:
                base = rgb("#4E8E32")
            d = rnd.randint(-9, 9)
            px[x, y] = (clamp(base[0] + d), clamp(base[1] + d), clamp(base[2] + d), 255)
    # 发光光点（更多、更亮，四邻带微光晕，像萤火停在叶间）
    motes = [(3, 4), (8, 2), (12, 6), (5, 10), (10, 12), (13, 13), (2, 13),
             (14, 3), (7, 7), (1, 8)]
    for x, y in motes:
        px[x, y] = (215, 255, 145, 255)
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < S and 0 <= ny < S:
                r0, g0, b0, a0 = px[nx, ny]
                if a0:
                    px[nx, ny] = ((r0 + 190) // 2, (g0 + 245) // 2,
                                  (b0 + 120) // 2, 255)
    # 两颗最亮星点
    px[8, 2] = (240, 255, 190, 255)
    px[5, 10] = (240, 255, 190, 255)
    return img


def block_void_ore() -> Image.Image:
    """虚空矿石：深邃近黑岩底 + 虚空紫晶簇（宝石切面闪光 + 亮棱）。"""
    img = new_canvas()
    fill_noise(img, rgb("#1E2028"), rgb("#0C0D12"), noise=6, seed=241)
    px = img.load()

    def crystal(cx, cy, bright, main=(96, 52, 160)):
        """十字晶簇：亮核 + 四向主色 + 斜向光晕 + 切面高光棱（左上受光）。"""
        px[cx, cy] = bright
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            px[cx + dx, cy + dy] = (main[0], main[1], main[2], 255)
        # 切面高光：左上棱最亮、右下棱次亮（宝石刻面感）
        px[cx - 1, cy] = (clamp(main[0] + 70), clamp(main[1] + 60),
                          clamp(main[2] + 80), 255)
        px[cx, cy - 1] = (clamp(main[0] + 45), clamp(main[1] + 40),
                          clamp(main[2] + 60), 255)
        px[cx + 1, cy] = (main[0] // 2, main[1] // 2, clamp(main[2] // 2 + 20), 255)
        for dx, dy in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
            nx, ny = cx + dx, cy + dy
            if 0 <= nx < S and 0 <= ny < S:
                r, g, b, a = px[nx, ny]
                px[nx, ny] = ((r + 70) // 2, (g + 36) // 2, (b + 120) // 2, 255)
        # 晶尖白色星闪
        px[cx, cy] = bright
        px[cx - 1, cy - 1] = (clamp((bright[0] + 255) // 2),
                              clamp((bright[1] + 255) // 2),
                              clamp((bright[2] + 255) // 2), 255)

    crystal(4, 4, (220, 180, 255, 255))
    crystal(11, 6, (190, 140, 245, 255))
    crystal(6, 12, (200, 150, 250, 255))
    crystal(13, 12, (160, 110, 215, 255))
    # 主晶十字闪光（最大一颗的刻面反光）
    sparkle(img, 4, 4, core=(240, 225, 255), glow=rgb("#C8A0F0"), glow_alpha=130)
    # 深处微光
    px[9, 14] = (70, 48, 112, 255)
    px[2, 9] = (60, 40, 100, 255)
    px[14, 2] = (56, 38, 96, 255)
    return img


def block_wildlight_grass() -> Image.Image:
    """野光草：苔绿基底 + 野光微粒（更多更亮）+ 小花。"""
    img = new_canvas()
    rnd = random.Random(251)
    px = img.load()
    for y in range(S):
        for x in range(S):
            base = rgb("#5E7A34") if rnd.random() < 0.55 else rgb("#4A6428")
            if (x * 3 + y) % 5 == 0:
                base = rgb("#6E8E40")
            d = rnd.randint(-10, 10)
            px[x, y] = (clamp(base[0] + d), clamp(base[1] + d), clamp(base[2] + d), 255)
    # 野光微粒（青绿萤火，带微光晕）
    for x, y in [(4, 3), (10, 5), (7, 9), (13, 10), (3, 12), (11, 14), (14, 5), (1, 7)]:
        px[x, y] = (185, 255, 210, 255)
        for dx, dy in ((1, 0), (0, 1)):
            nx, ny = x + dx, y + dy
            if nx < S and ny < S:
                r0, g0, b0, a0 = px[nx, ny]
                px[nx, ny] = ((r0 + 160) // 2, (g0 + 250) // 2, (b0 + 195) // 2, 255)
    # 小花
    px[8, 4] = (255, 250, 220, 255)
    px[5, 13] = (255, 230, 160, 255)
    px[12, 12] = (255, 240, 190, 255)
    return img


# -----------------------------------------------------------------------------
# 预览（/tmp，不入库）
# -----------------------------------------------------------------------------

def make_previews(out_dir: Path, items: dict, blocks: dict):
    """16× 最近邻放大拼图，深色底，供肉眼自验绚丽度。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    scale = 16

    def montage(named: dict, path: Path):
        names = list(named)
        cols = 6
        rows = (len(names) + cols - 1) // cols
        cell = S * scale + 8
        canvas = Image.new("RGBA", (cols * cell + 8, rows * cell + 8), (30, 28, 38, 255))
        for i, n in enumerate(names):
            img = named[n].resize((S * scale, S * scale), Image.NEAREST)
            x = 8 + (i % cols) * cell
            y = 8 + (i // cols) * cell
            canvas.paste(img, (x, y), img)
        canvas.save(path)

    montage(items, out_dir / "items.png")
    montage(blocks, out_dir / "blocks.png")
    print(f"预览输出: {out_dir} (items.png / blocks.png)")


# -----------------------------------------------------------------------------
# 主入口
# -----------------------------------------------------------------------------

ITEM_TEXTURES = {
    "ember_crystal": tex_ember_crystal,
    "beast_fang": tex_beast_fang,
    "ember_iron": tex_ember_iron,
    "abyss_iron": tex_abyss_iron,
    "bloodroot": tex_bloodroot,
    "dragon_bone": tex_dragon_bone,
    "salamander_gland": tex_salamander_gland,
    "rift_essence": tex_rift_essence,
    "shadowhide_patch": tex_shadowhide_patch,
    "glimmer_wood_sap": tex_glimmer_wood_sap,
    "myriad_fragment": tex_myriad_fragment,
    "ember_blade": tex_ember_blade,
    "phase_staff": tex_phase_staff,
    "bone_blade": tex_bone_blade,
    "phase_shield": tex_phase_shield,
    "phase_hoe": tex_phase_hoe,
    "phase_watering_can": tex_phase_watering_can,
    "spell_book": tex_spell_book,
    "reverse_core": tex_reverse_core,
}

BLOCK_TEXTURES = {
    "rift_stone": block_rift_stone,
    "glimmer_log": block_glimmer_log,
    "glimmer_log_top": block_glimmer_log_top,
    "glimmer_leaves": block_glimmer_leaves,
    "void_ore": block_void_ore,
    "wildlight_grass": block_wildlight_grass,
}


def main():
    root = project_root()
    item_dir = root / "src/main/resources/assets/qianxiang/textures/item"
    block_dir = root / "src/main/resources/assets/qianxiang/textures/block"
    item_dir.mkdir(parents=True, exist_ok=True)
    block_dir.mkdir(parents=True, exist_ok=True)

    items = {}
    for name, gen in ITEM_TEXTURES.items():
        img = gen()
        img.save(item_dir / f"{name}.png")
        items[name] = img
        print(f"item/{name}.png")

    blocks = {}
    for name, gen in BLOCK_TEXTURES.items():
        img = gen()
        img.save(block_dir / f"{name}.png")
        blocks[name] = img
        print(f"block/{name}.png")

    make_previews(Path("/tmp/qianxiang_item_preview"), items, blocks)
    print(f"Done: {len(ITEM_TEXTURES)} item + {len(BLOCK_TEXTURES)} block textures.")


if __name__ == "__main__":
    main()
