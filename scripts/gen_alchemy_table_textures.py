#!/usr/bin/env python3
"""
千相 (Qianxiang) - 炼金台 + 魔法卷轴纹理生成脚本

程序化生成（紫绿炼金配色）：
  * textures/gui/alchemy_table.png   (256×256) —— 炼金台 GUI 背景
  * textures/block/alchemy_table.png (16×16)   —— 炼金台方块纹理
  * textures/item/magic_scroll.png   (16×16)   —— 魔法卷轴物品纹理

GUI 布局（与 AlchemyTableScreen / AlchemyTableMenu 坐标一致）：
  * 标题条 y 4..13
  * 材料槽 2 行 3 列：y=17 / y=35，x = 8/26/44（间距 18）
  * 输入框 (104,17,144×14)
  * 操作按钮 (146,50/66/82, 58×13)
  * 状态条 (8,56,132×10)
  * 结果槽 32×32 框 (214,46)-(246,78)，逻辑槽 (222,54)
  * AI 方案卡片 1×3：(8|90|172, 96, 76×40)
  * 玩家背包 9×3 (8,176) 间距 18，快捷栏 (8,234)
"""

import random
from pathlib import Path

from PIL import Image, ImageDraw


# -----------------------------------------------------------------------------
# 工具函数（与 generate_forge_table_textures.py 同风格）
# -----------------------------------------------------------------------------

def project_root() -> Path:
    """脚本位于 scripts/，项目根目录为其父目录。"""
    return Path(__file__).resolve().parent.parent


def hex_rgba(hex_color: str, alpha: int = 255):
    """将 #RRGGBB 或 #RRGGBBAA 转为 (R, G, B, A)。"""
    hex_color = hex_color.lstrip("#")
    if len(hex_color) == 6:
        r, g, b = int(hex_color[0:2], 16), int(hex_color[2:4], 16), int(hex_color[4:6], 16)
        return (r, g, b, alpha)
    if len(hex_color) == 8:
        r, g, b, a = (
            int(hex_color[0:2], 16),
            int(hex_color[2:4], 16),
            int(hex_color[4:6], 16),
            int(hex_color[6:8], 16),
        )
        return (r, g, b, a)
    raise ValueError(f"Invalid hex color: {hex_color}")


def draw_glow_line(draw: ImageDraw.ImageDraw, x1, y1, x2, y2, color, width: int = 1):
    """绘制带微弱发光效果的像素线。"""
    draw.line([(x1, y1), (x2, y2)], fill=color, width=width)
    glow = color[:3] + (max(0, color[3] // 4),)
    if x1 == x2:  # 竖线
        draw.line([(x1 - 1, y1), (x2 - 1, y2)], fill=glow, width=1)
        draw.line([(x1 + 1, y1), (x2 + 1, y2)], fill=glow, width=1)
    elif y1 == y2:  # 横线
        draw.line([(x1, y1 - 1), (x2, y2 - 1)], fill=glow, width=1)
        draw.line([(x1, y1 + 1), (x2, y2 + 1)], fill=glow, width=1)


def add_noise(img: Image.Image, intensity: int = 8, seed: int = 42):
    """给图像添加轻微噪点，增强质感。"""
    random.seed(seed)
    pixels = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = pixels[x, y]
            noise = random.randint(-intensity, intensity)
            pixels[x, y] = (
                max(0, min(255, r + noise)),
                max(0, min(255, g + noise)),
                max(0, min(255, b + noise)),
                a,
            )


# -----------------------------------------------------------------------------
# GUI 纹理生成
# -----------------------------------------------------------------------------

def generate_gui_texture() -> Image.Image:
    """生成 256×256 的炼金台 GUI 背景（紫绿炼金配色）。"""
    W, H = 256, 256
    img = Image.new("RGBA", (W, H), hex_rgba("#100B16"))
    draw = ImageDraw.Draw(img)

    # 配色板：暗紫底 + 翠绿药液光 + 紫晶点缀
    base = hex_rgba("#161022")          # 主底色（暗紫）
    panel = hex_rgba("#1E1530")         # 面板色
    metal = hex_rgba("#322646")         # 紫金属
    metal_light = hex_rgba("#4A3866")   # 亮紫金属
    rune_glow = hex_rgba("#5CE892", 200)   # 翠绿药液发光
    rune_glow_dim = hex_rgba("#5CE892", 80)
    amethyst = hex_rgba("#B678F0", 180)    # 紫晶点缀
    slot_dark = hex_rgba("#0C0812")     # 槽位暗色

    # 1. 整体垂直渐变背景
    for y in range(H):
        t = y / H
        c = tuple(int(base[i] * (1 - t) + panel[i] * t) for i in range(4))
        draw.line([(0, y), (W, y)], fill=c)

    # 2. 外边框 + 四角符文三角
    draw.rectangle([0, 0, W - 1, H - 1], outline=metal_light, width=1)
    draw.rectangle([1, 1, W - 2, H - 2], outline=metal, width=1)

    corner_size = 8
    for cx, cy, sx, sy in [(0, 0, 1, 1), (W - 1, 0, -1, 1), (0, H - 1, 1, -1), (W - 1, H - 1, -1, -1)]:
        pts = [
            (cx + sx * corner_size, cy),
            (cx, cy + sy * corner_size),
            (cx + sx * 3, cy + sy * 3),
        ]
        draw.polygon(pts, fill=rune_glow_dim)
        draw.line([(cx + sx * corner_size, cy), (cx, cy + sy * corner_size)], fill=rune_glow, width=1)

    # 3. 顶部标题条
    draw.rectangle([4, 4, W - 5, 13], fill=panel, outline=metal_light, width=1)
    draw_glow_line(draw, 8, 8, W - 9, 8, rune_glow, 1)

    # 4. 输入框凹槽（材料槽右侧，与第一行材料槽同排）
    input_rect = (104, 17, 104 + 144 - 1, 17 + 14 - 1)
    draw.rectangle(input_rect, fill=slot_dark, outline=metal_light, width=1)
    draw_glow_line(draw, input_rect[0] + 1, input_rect[1] + 1,
                   input_rect[0] + 1, input_rect[3] - 1, rune_glow_dim, 1)

    # 5. 左上材料槽面板（2 行 3 列）
    draw.rectangle([4, 14, 65, 54], fill=panel, outline=metal, width=1)
    draw_glow_line(draw, 6, 18, 6, 50, rune_glow_dim, 1)
    draw_glow_line(draw, 63, 18, 63, 50, rune_glow_dim, 1)

    # 6 个材料槽凹槽（2 行 3 列，间距 18，与 AlchemyTableMenu 一致）
    slot_positions = [(8 + (i % 3) * 18, 17 + (i // 3) * 18) for i in range(6)]
    slot_ring_colors = (
        [(92, 232, 146, 120)] * 3 +   # 第 1 行 - 翠绿
        [(182, 120, 240, 100)] * 3    # 第 2 行 - 紫晶
    )
    for (sx, sy), ring_color in zip(slot_positions, slot_ring_colors):
        draw.rectangle([sx, sy, sx + 15, sy + 15], fill=slot_dark, outline=metal_light, width=1)
        draw.rectangle([sx - 1, sy - 1, sx + 16, sy + 16], outline=ring_color, width=1)

    # 6. 状态条凹槽（材料槽下方）
    status_rect = (8, 56, 8 + 132 - 1, 56 + 10 - 1)
    draw.rectangle(status_rect, fill=slot_dark, outline=metal_light, width=1)

    # 7. 中部操作按钮面板（问AI/清空/确认）
    draw.rectangle([142, 46, 209, 97], fill=panel, outline=metal, width=1)
    for rect in [(146, 50, 146 + 58 - 1, 50 + 13 - 1),
                 (146, 66, 146 + 58 - 1, 66 + 13 - 1),
                 (146, 82, 146 + 58 - 1, 82 + 13 - 1)]:
        draw.rectangle(rect, fill=slot_dark, outline=metal_light, width=1)
        draw_glow_line(draw, rect[0] + 1, rect[3] - 1, rect[2] - 1, rect[3] - 1, rune_glow_dim, 1)

    # 8. 右侧结果槽面板（32×32 紫晶边大槽，逻辑槽 16×16 居中）
    draw.rectangle([212, 42, 250, 82], fill=panel, outline=metal, width=1)
    frame_rect = (214, 46, 214 + 31, 46 + 31)
    draw.rectangle(frame_rect, fill=slot_dark, outline=amethyst, width=1)
    result_rect = (222, 54, 222 + 15, 54 + 15)
    draw.rectangle(result_rect, outline=metal_light, width=1)
    for ox, oy in [(-2, -2), (33, -2), (-2, 33), (33, 33)]:
        px, py = frame_rect[0] + ox, frame_rect[1] + oy
        draw.rectangle([px, py, px + 1, py + 1], fill=rune_glow)

    # 9. 下方 AI 方案卡片区（1×3）
    draw.rectangle([4, 90, 251, 142], fill=panel, outline=metal, width=1)
    for card_x in (8, 90, 172):
        draw.rectangle([card_x, CARD_Y := 96, card_x + 76 - 1, 96 + 40 - 1],
                       outline=metal, width=1)
        draw_glow_line(draw, card_x + 2, 97, card_x + 20, 97, rune_glow_dim, 1)

    # 10. 底部玩家背包区（9×3 + 快捷栏，原版间距 18）
    draw.rectangle([4, 170, 173, 254], fill=panel, outline=metal, width=1)
    for r in range(3):
        for c in range(9):
            sx, sy = 8 + c * 18, 176 + r * 18
            draw.rectangle([sx, sy, sx + 15, sy + 15], fill=slot_dark, outline=metal, width=1)
    for c in range(9):
        sx, sy = 8 + c * 18, 234
        draw.rectangle([sx, sy, sx + 15, sy + 15], fill=slot_dark, outline=metal_light, width=1)

    # 11. 边缘药液气泡 —— 沿左右两侧绘制竖排小圆点（炼金沸腾感）
    for y in range(18, H - 18, 12):
        draw.rectangle([2, y, 3, y + 2], fill=rune_glow_dim)
        draw.rectangle([W - 4, y + 2, W - 3, y + 4], fill=rune_glow_dim)

    # 12. 顶部与底部横向发光纹
    draw_glow_line(draw, 20, 14, 60, 14, rune_glow_dim, 1)
    draw_glow_line(draw, 150, 14, 190, 14, amethyst, 1)
    draw_glow_line(draw, 180, H - 4, 248, H - 4, rune_glow_dim, 1)

    # 13. 轻微噪点
    add_noise(img, intensity=6, seed=321)

    return img


# -----------------------------------------------------------------------------
# 方块纹理生成
# -----------------------------------------------------------------------------

def generate_block_texture() -> Image.Image:
    """生成 16×16 的炼金台方块纹理（紫晶台体 + 翠绿药液核）。"""
    SIZE = 16
    img = Image.new("RGBA", (SIZE, SIZE), hex_rgba("#140E1E"))
    draw = ImageDraw.Draw(img)

    base = hex_rgba("#1C1430")
    dark = hex_rgba("#0E0918")
    metal = hex_rgba("#362A4E")
    metal_light = hex_rgba("#504070")
    rune = hex_rgba("#5CE892", 200)
    rune_dim = hex_rgba("#5CE892", 90)
    amethyst = hex_rgba("#B678F0", 160)

    # 1. 基础紫晶面板
    draw.rectangle([1, 1, SIZE - 2, SIZE - 2], fill=base, outline=metal, width=1)

    # 2. 中心坩埚凹槽
    draw.rectangle([5, 5, SIZE - 6, SIZE - 6], fill=dark, outline=metal_light, width=1)
    draw.rectangle([7, 7, SIZE - 8, SIZE - 8], outline=rune_dim, width=1)

    # 3. 四角符文刻痕
    corners = [(2, 2), (SIZE - 3, 2), (2, SIZE - 3), (SIZE - 3, SIZE - 3)]
    for cx, cy in corners:
        draw.rectangle([cx, cy, cx + 1, cy + 1], fill=amethyst)

    # 4. 四边中点翠绿铆钉
    midpoints = [(SIZE // 2, 1), (SIZE // 2, SIZE - 2), (1, SIZE // 2), (SIZE - 2, SIZE // 2)]
    for mx, my in midpoints:
        draw.rectangle([mx, my, mx + 1, my + 1], fill=rune)

    # 5. 中心药液核：翠绿亮核 + 气泡
    rune_hi = hex_rgba("#B8FFDC", 255)
    draw.point([(8, 7), (7, 8), (8, 8), (9, 8), (8, 9)], fill=rune)
    draw.point([(8, 8)], fill=rune_hi)                       # 白热药液心
    draw.point([(6, 8), (10, 8), (8, 6), (8, 10)], fill=rune_dim)  # 四向星芒
    draw.point([(7, 7), (9, 9)], fill=amethyst)              # 对角紫晶点
    for cx, cy in corners:
        draw.point([(cx, cy)], fill=rune_hi)

    # 6. 噪点
    add_noise(img, intensity=8, seed=87)

    return img


# -----------------------------------------------------------------------------
# 卷轴物品纹理生成
# -----------------------------------------------------------------------------

def generate_scroll_texture() -> Image.Image:
    """生成 16×16 的魔法卷轴物品纹理（羊皮纸 + 紫绳 + 翠绿符文）。"""
    SIZE = 16
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    parchment = hex_rgba("#E8D9A8")
    parchment_dark = hex_rgba("#C9B582")
    parchment_edge = hex_rgba("#A8946A")
    ribbon = hex_rgba("#9B59B6")
    rune = hex_rgba("#3FBF7A", 220)

    # 1. 卷轴主体（竖卷：上下卷边 + 中间纸面）
    draw.rectangle([3, 1, 12, 3], fill=parchment_dark, outline=parchment_edge, width=1)   # 上卷边
    draw.rectangle([3, 12, 12, 14], fill=parchment_dark, outline=parchment_edge, width=1)  # 下卷边
    draw.rectangle([4, 4, 11, 11], fill=parchment)                                        # 纸面
    draw.line([(4, 4), (4, 11)], fill=parchment_edge, width=1)
    draw.line([(11, 4), (11, 11)], fill=parchment_edge, width=1)

    # 2. 紫绳（斜扎一道）
    draw.line([(4, 6), (11, 9)], fill=ribbon, width=1)
    draw.point([(7, 7)], fill=hex_rgba("#B678F0"))

    # 3. 翠绿符文刻印（三行小刻痕）
    draw.line([(6, 5), (9, 5)], fill=rune, width=1)
    draw.line([(6, 8), (8, 8)], fill=rune, width=1)
    draw.line([(6, 10), (10, 10)], fill=rune, width=1)

    # 4. 噪点（羊皮纸质感）
    add_noise(img, intensity=5, seed=55)

    return img


# -----------------------------------------------------------------------------
# 主入口
# -----------------------------------------------------------------------------

def main():
    root = project_root()
    out_gui = root / "src/main/resources/assets/qianxiang/textures/gui"
    out_block = root / "src/main/resources/assets/qianxiang/textures/block"
    out_item = root / "src/main/resources/assets/qianxiang/textures/item"
    out_gui.mkdir(parents=True, exist_ok=True)
    out_block.mkdir(parents=True, exist_ok=True)
    out_item.mkdir(parents=True, exist_ok=True)

    gui_img = generate_gui_texture()
    block_img = generate_block_texture()
    scroll_img = generate_scroll_texture()

    gui_path = out_gui / "alchemy_table.png"
    block_path = out_block / "alchemy_table.png"
    scroll_path = out_item / "magic_scroll.png"

    gui_img.save(gui_path)
    block_img.save(block_path)
    scroll_img.save(scroll_path)

    print(f"Generated GUI texture: {gui_path} ({gui_img.size[0]}x{gui_img.size[1]})")
    print(f"Generated block texture: {block_path} ({block_img.size[0]}x{block_img.size[1]})")
    print(f"Generated scroll texture: {scroll_path} ({scroll_img.size[0]}x{scroll_img.size[1]})")


if __name__ == "__main__":
    main()
