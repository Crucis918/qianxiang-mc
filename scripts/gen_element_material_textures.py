#!/usr/bin/env python3
"""
千相 (Qianxiang) - 元素扩展材料纹理生成脚本

为 7 个元素相材料生成 16×16 纹理（冰/雷/毒/暗影/光明/自然/虚空）：
  src/main/resources/assets/qianxiang/textures/item/
    frost_crystal（冰蓝晶）/ thunder_stone（金黄带电）/ venom_gland（毒绿腺）
    shadow_dust（紫黑尘）/ holy_shard（白金辉）/ nature_breath（嫩绿息）
    void_shard（深空紫，传奇）

绚丽化管线复用 gen_item_textures.py 的 helper（光晕/渐变/高光/切面/星闪），
风格与既有材料保持一致；本脚本只生成上述 7 个新纹理，不动既有 PNG。
运行后在 /tmp/qianxiang_element_preview/ 输出 16× 放大预览拼图，供自验。
"""

from pathlib import Path

from PIL import Image

from gen_item_textures import (
    S, add_glow_edge, add_halo, add_outline, fill_gradient, gem_facets,
    highlight_dots, lerp, magic_flow, mask_from_pixels, mask_from_polygon,
    new_canvas, rgb, set_px, shade, sparkle, tier_decor,
)

OUT_DIR = Path(__file__).resolve().parent.parent / \
    "src/main/resources/assets/qianxiang/textures/item"


def tex_frost_crystal() -> Image.Image:
    """寒霜晶：冰蓝晶簇，宝石切面棱线 + 霜白核心 + 冰蓝外发光。"""
    import PIL.ImageChops as Chops
    main = mask_from_polygon([(8, 1), (10, 4), (10, 9), (8, 13), (6, 9), (6, 4)])
    left = mask_from_polygon([(4, 6), (5, 8), (4, 12), (3, 9)])
    right = mask_from_polygon([(12, 5), (13, 8), (12, 11), (11, 8)])
    full = Chops.lighter(Chops.lighter(main, left), right)
    img = new_canvas()
    add_halo(img, main, rgb("#7EC8F8"), strength=110)
    fill_gradient(img, main, rgb("#D8F0FF"), rgb("#3E7AC8"), noise=5, seed=301)
    fill_gradient(img, left, rgb("#A8DCF8"), rgb("#2E64A8"), noise=5, seed=302)
    fill_gradient(img, right, rgb("#A8DCF8"), rgb("#2E64A8"), noise=5, seed=303)
    # 宝石切面：中央棱线 + 两侧刻面交线
    gem_facets(img, [(8, 2), (8, 3), (7, 5), (9, 5), (7, 8), (9, 8), (8, 11)],
               color=rgb("#F0FAFF"), alpha=215)
    # 霜白核心
    for x, y in [(8, 4), (8, 5), (7, 6), (8, 6)]:
        set_px(img, x, y, rgb("#F4FCFF"))
    highlight_dots(img, main)
    add_glow_edge(img, full, rgb("#7EC8F8"))
    add_outline(img, main, rgb("#0E2A4A"))
    add_outline(img, left, rgb("#0E2A4A"))
    add_outline(img, right, rgb("#0E2A4A"))
    sparkle(img, 12, 2, glow=rgb("#A8DCF8"))
    return img


def tex_thunder_stone() -> Image.Image:
    """雷霆石：金黄奇石 + 锯齿电光（白热电弧）+ 金色光晕；史诗 → 对角双星闪。"""
    stone = mask_from_polygon([
        (8, 3), (11, 4), (13, 7), (12, 11), (9, 13), (5, 12),
        (3, 9), (4, 5), (6, 3),
    ])
    img = new_canvas()
    add_halo(img, stone, rgb("#9A5AE0"), strength=135, radius=1.4)  # 史诗紫晕
    fill_gradient(img, stone, rgb("#F8D878"), rgb("#7A5A10"), noise=6, seed=311)
    # 石面暗斑
    shade(img, [(5, 6), (11, 9), (6, 11), (10, 5)], 0.72)
    # 锯齿电光主脉（白热核心 + 金黄电弧）
    bolt = [(7, 4), (8, 5), (7, 6), (8, 7), (9, 8), (8, 9), (9, 10), (8, 11)]
    for x, y in bolt:
        set_px(img, x, y, rgb("#FFE45A"))
    for x, y in [(8, 5), (8, 7), (8, 9)]:
        set_px(img, x, y, rgb("#FFFBDC"))  # 白热电弧
    # 分叉小电光
    set_px(img, 9, 6, rgb("#FFE45A"))
    set_px(img, 10, 7, rgb("#F8CC3E"))
    set_px(img, 7, 8, rgb("#F8CC3E"))
    highlight_dots(img, stone)
    add_glow_edge(img, stone, rgb("#F8CC3E"))
    add_outline(img, stone, rgb("#2A1E04"))
    sparkle(img, 13, 3, glow=rgb("#FFE45A"))
    tier_decor(img, 2)  # 史诗双星
    return img


def tex_venom_gland() -> Image.Image:
    """剧毒腺：毒绿腺体，毒液囊泡 + 欲滴毒珠 + 毒绿外发光。"""
    blob = mask_from_polygon([
        (8, 3), (10, 4), (12, 6), (12, 9), (10, 12), (7, 12),
        (5, 10), (4, 7), (5, 5), (7, 3),
    ])
    img = new_canvas()
    add_halo(img, blob, rgb("#7AE84A"), strength=110)
    fill_gradient(img, blob, rgb("#B8F078"), rgb("#2E6A14"), noise=7, seed=321)
    # 毒液囊泡（亮绿核心群）
    for x, y in [(7, 6), (9, 7), (8, 9), (10, 9), (7, 10)]:
        set_px(img, x, y, rgb("#D8F8A0"))
    set_px(img, 8, 7, rgb("#F0FCC8"))
    set_px(img, 9, 9, rgb("#F0FCC8"))
    # 膜质褶皱
    shade(img, [(6, 5), (5, 7), (6, 10), (10, 10), (11, 8)], 0.68)
    # 欲滴毒珠（腺体下缘）
    set_px(img, 8, 12, rgb("#C8F890"))
    set_px(img, 8, 13, rgb("#9AE84A"))
    set_px(img, 8, 14, rgb("#D8F8A0"))
    highlight_dots(img, blob)
    add_glow_edge(img, blob, rgb("#7AE84A"))
    add_outline(img, blob, rgb("#103006"))
    sparkle(img, 12, 4, glow=rgb("#9AE84A"))
    return img


def tex_shadow_dust() -> Image.Image:
    """暗影尘：紫黑尘堆 + 飘散尘粒 + 幽紫微光。"""
    pile = mask_from_polygon([
        (4, 11), (6, 8), (8, 6), (10, 8), (12, 11), (11, 13), (5, 13),
    ])
    img = new_canvas()
    add_halo(img, pile, rgb("#7A5AB0"), strength=95)
    fill_gradient(img, pile, rgb("#6E5A94"), rgb("#161022"), noise=6, seed=331)
    # 尘堆顶面幽光
    shade(img, [(8, 6), (7, 7), (8, 7), (9, 7), (6, 8), (7, 8), (8, 8), (9, 8)], 1.4)
    set_px(img, 8, 7, rgb("#B098D8"))  # 堆尖最亮
    # 堆内暗斑
    shade(img, [(6, 11), (10, 11), (8, 12), (5, 12), (11, 12)], 0.6)
    # 飘散尘粒（围绕尘堆，向上飘散，明暗交替）
    motes = [(3, 8), (12, 7), (5, 5), (11, 4), (8, 2), (13, 10), (2, 11)]
    for i, (x, y) in enumerate(motes):
        set_px(img, x, y, rgb("#9A7ACC") if i % 2 == 0 else rgb("#5E4686"))
    set_px(img, 8, 2, rgb("#C8AEF0"))  # 最高一粒最亮
    add_glow_edge(img, pile, rgb("#7A5AB0"), inner=130, outer=55)
    add_outline(img, pile, rgb("#08060E"))
    sparkle(img, 13, 3, glow=rgb("#9A7ACC"), glow_alpha=90)
    return img


def tex_holy_shard() -> Image.Image:
    """圣辉碎片：白金碎晶，放射圣光棱 + 白热核心 + 金白光晕；史诗 → 对角双星闪。"""
    import PIL.ImageChops as Chops
    shard = mask_from_polygon([(8, 2), (10, 6), (10, 10), (8, 14), (6, 10), (6, 6)])
    # 四角放射短芒
    rays = mask_from_pixels([(3, 3), (13, 3), (3, 13), (13, 13),
                             (4, 4), (12, 4), (4, 12), (12, 12)])
    full = Chops.lighter(shard, rays)
    img = new_canvas()
    add_halo(img, shard, rgb("#F4E8A8"), strength=140, radius=1.5)
    fill_gradient(img, shard, rgb("#FFFBEC"), rgb("#C8A028"), noise=4, seed=341)
    # 切面棱线
    gem_facets(img, [(8, 3), (7, 6), (9, 6), (8, 9), (7, 11), (9, 11)],
               color=rgb("#FFFDF4"), alpha=220)
    # 白热圣核
    for x, y in [(8, 6), (8, 7), (7, 7), (8, 8)]:
        set_px(img, x, y, rgb("#FFFFFF"))
    # 放射圣芒（白金 → 淡金）
    for x, y in [(4, 4), (12, 4), (4, 12), (12, 12)]:
        set_px(img, x, y, rgb("#F4E0A0"))
    for x, y in [(3, 3), (13, 3), (3, 13), (13, 13)]:
        set_px(img, x, y, rgb("#D8BC60"))
    highlight_dots(img, shard)
    add_glow_edge(img, full, rgb("#F4E8A8"), inner=170, outer=75)
    add_outline(img, shard, rgb("#4A3808"))
    sparkle(img, 8, 7, core=(255, 255, 255), glow=rgb("#FFF6D8"), glow_alpha=150)
    tier_decor(img, 2)  # 史诗双星
    return img


def tex_nature_breath() -> Image.Image:
    """自然之息：嫩绿生机旋息（螺旋气流）+ 萌芽小叶 + 青绿外发光。"""
    # 螺旋气流：从左下盘旋至右上的一缕息
    swirl = [(3, 12), (4, 11), (5, 11), (6, 10), (6, 9), (5, 8), (4, 7),
             (5, 6), (6, 6), (7, 6), (8, 7), (9, 7), (10, 6), (11, 5),
             (11, 4), (12, 3)]
    swirl_mask = mask_from_pixels(swirl)
    # 气流末端萌芽小叶（右上）
    leaf = mask_from_polygon([(12, 2), (14, 2), (14, 4), (13, 5), (12, 4)])
    import PIL.ImageChops as Chops
    full = Chops.lighter(swirl_mask, leaf)
    img = new_canvas()
    add_halo(img, full, rgb("#8AE85A"), strength=105)
    # 气流：魔法流光（明暗交替，像生机在流动）
    magic_flow(img, swirl, rgb("#C8F89A"), rgb("#5EA830"))
    # 气流起点（左下）更亮——生机之源
    set_px(img, 3, 12, rgb("#F0FCD0"))
    set_px(img, 4, 11, rgb("#D8F8A8"))
    # 小叶：嫩绿渐变 + 叶脉
    fill_gradient(img, leaf, rgb("#D0F8A0"), rgb("#3E8A28"), noise=3, seed=351)
    set_px(img, 13, 3, rgb("#F4FCD8"))  # 叶脉高光
    set_px(img, 13, 4, rgb("#E0F8B8"))
    # 飘散生机微粒
    for x, y in [(6, 13), (9, 11), (10, 9), (7, 3), (4, 4)]:
        set_px(img, x, y, rgb("#B8F080"))
    sparkle(img, 12, 3, core=(240, 255, 210), glow=rgb("#8AE85A"), glow_alpha=120)
    add_glow_edge(img, full, rgb("#8AE85A"), inner=140, outer=55)
    add_outline(img, leaf, rgb("#1E4010"))
    return img


def tex_void_shard() -> Image.Image:
    """虚空裂片：深空紫裂片，裂隙状亮脉 + 悬浮星点；传奇 → 金边 + 四角星闪。"""
    import PIL.ImageChops as Chops
    s1 = mask_from_polygon([(8, 1), (10, 5), (9, 9), (8, 13), (6, 9), (6, 4)])
    s2 = mask_from_polygon([(4, 7), (5, 9), (4, 13), (2, 12), (3, 9)])
    s3 = mask_from_polygon([(12, 6), (14, 9), (13, 13), (11, 12), (11, 8)])
    mask = Chops.lighter(Chops.lighter(s1, s2), s3)
    img = new_canvas()
    add_halo(img, mask, rgb("#8A5AE0"), strength=160, radius=1.6)  # 传奇强紫晕
    fill_gradient(img, s1, rgb("#B090F0"), rgb("#241040"), noise=5, seed=361)
    fill_gradient(img, s2, rgb("#9A78E0"), rgb("#1E0E38"), noise=5, seed=362)
    fill_gradient(img, s3, rgb("#9A78E0"), rgb("#1E0E38"), noise=5, seed=363)
    # 裂隙状亮脉（虚空回响在片内搏动）
    rift = [(8, 3), (7, 5), (8, 7), (7, 9), (8, 11)]
    for i, (x, y) in enumerate(rift):
        set_px(img, x, y, rgb("#D8BCFF") if i % 2 == 0 else rgb("#8A5AE0"))
    set_px(img, 8, 7, rgb("#F4EAFF"))  # 回响核心
    # 悬浮星点（失重的碎屑）
    for x, y in [(12, 2), (2, 5), (14, 6), (5, 15)]:
        set_px(img, x, y, rgb("#B090F0"))
    set_px(img, 12, 2, rgb("#E0CCFF"))
    highlight_dots(img, s1)
    add_glow_edge(img, mask, rgb("#B080F0"), inner=200, outer=95)  # 传奇更强
    add_outline(img, mask, rgb("#D8A828"))  # 传奇金边
    sparkle(img, 13, 2, core=(255, 255, 255), glow=rgb("#E8D0FF"))
    tier_decor(img, 3)  # 传奇四角金星闪
    return img


ELEMENT_TEXTURES = {
    "frost_crystal": tex_frost_crystal,
    "thunder_stone": tex_thunder_stone,
    "venom_gland": tex_venom_gland,
    "shadow_dust": tex_shadow_dust,
    "holy_shard": tex_holy_shard,
    "nature_breath": tex_nature_breath,
    "void_shard": tex_void_shard,
}


def make_preview(imgs: dict, out_dir: Path):
    """16× 最近邻放大拼图，深色底，供肉眼自验。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    scale = 16
    cols = 4
    rows = (len(imgs) + cols - 1) // cols
    cell = S * scale + 8
    canvas = Image.new("RGBA", (cols * cell + 8, rows * cell + 8), (30, 28, 38, 255))
    for i, (name, img) in enumerate(imgs.items()):
        big = img.resize((S * scale, S * scale), Image.NEAREST)
        x = 8 + (i % cols) * cell
        y = 8 + (i // cols) * cell
        canvas.paste(big, (x, y), big)
    canvas.save(out_dir / "elements.png")
    print(f"预览输出: {out_dir / 'elements.png'}")


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    imgs = {}
    for name, gen in ELEMENT_TEXTURES.items():
        img = gen()
        img.save(OUT_DIR / f"{name}.png")
        imgs[name] = img
        print(f"item/{name}.png")
    make_preview(imgs, Path("/tmp/qianxiang_element_preview"))
    print(f"Done: {len(ELEMENT_TEXTURES)} element material textures.")


if __name__ == "__main__":
    main()
