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
    """生成 256×256 的炼金台 GUI 背景（复古风：深木 + 深灰金属 + 铜 + 一点暗绿珐琅）。

    布局坐标与代码硬约束一致（只换皮，不挪位置）：
      * 标题条 y 4..13
      * 材料区（文字列表用木质告示板）(4,14)-(65,54)
      * 输入框 (104,17,144×14)
      * 状态条 (8,56,132×10)
      * 操作按钮 (146,50/66/82, 58×13)
      * 结果槽 32×32 框 (214,46)-(246,78)，逻辑槽 (222,54)
      * AI 方案卡片 1×3：(8|90|172, 96, 76×40)
      * 玩家背包 9×3 (8,176) 间距 18，快捷栏 (8,234)
    """
    W, H = 256, 256
    img = Image.new("RGBA", (W, H), hex_rgba("#2A1E12"))
    draw = ImageDraw.Draw(img)

    # 复古配色板：与锻造台统一，炼金台以铜 + 少量暗绿珐琅区分
    wood_dark = hex_rgba("#3A2A1A")
    wood = hex_rgba("#4A3423")
    wood_light = hex_rgba("#5C442E")
    metal_dark = hex_rgba("#3C3C3C")
    metal = hex_rgba("#565656")
    copper_dark = hex_rgba("#8A5A2B")
    copper = hex_rgba("#B87333")
    cream = hex_rgba("#D8CDB0")
    enamel = hex_rgba("#2E4A38")          # 暗绿珐琅（炼金区分色，少量）
    slot_dark = hex_rgba("#241A12")

    # 1. 深木面板垂直渐变（低对比）
    for y in range(H):
        t = y / H
        c = tuple(int(wood_dark[i] * (1 - t) + wood[i] * t) for i in range(4))
        draw.line([(0, y), (W, y)], fill=c)

    # 2. 外边框：细金属线 + 四角包铁
    draw.rectangle([0, 0, W - 1, H - 1], outline=metal, width=1)
    for cx, cy in [(1, 1), (W - 5, 1), (1, H - 5), (W - 5, H - 5)]:
        draw.rectangle([cx, cy, cx + 3, cy + 3], fill=metal_dark, outline=metal, width=1)

    # 3. 顶部标题条：木牌 + 米白衬线 + 一线暗绿珐琅（炼金身份）
    draw.rectangle([4, 4, W - 5, 13], fill=wood_light, outline=metal_dark, width=1)
    draw.line([(8, 8), (W - 9, 8)], fill=cream, width=1)
    draw.line([(8, 11), (W - 9, 11)], fill=enamel, width=1)

    # 4. 输入框凹槽（深棕描边）
    input_rect = (104, 17, 104 + 144 - 1, 17 + 14 - 1)
    draw.rectangle(input_rect, fill=slot_dark, outline=metal, width=1)

    # 5. 左上材料区：木质告示板（文字列表用），板缝 + 一角铜铆钉
    draw.rectangle([4, 14, 65, 54], fill=wood_light, outline=metal_dark, width=1)
    for sy in (28, 42):  # 木板横缝
        draw.line([(5, sy), (64, sy)], fill=wood_dark, width=1)
    draw.rectangle([59, 17, 61, 19], fill=copper)  # 右上角铜铆钉

    # 6. 状态条凹槽（材料区下方）
    status_rect = (8, 56, 8 + 132 - 1, 56 + 10 - 1)
    draw.rectangle(status_rect, fill=slot_dark, outline=metal, width=1)

    # 7. 中部操作按钮面板（问AI/清空/确认）
    draw.rectangle([142, 46, 209, 97], fill=wood, outline=metal_dark, width=1)
    for rect in [(146, 50, 146 + 58 - 1, 50 + 13 - 1),
                 (146, 66, 146 + 58 - 1, 66 + 13 - 1),
                 (146, 82, 146 + 58 - 1, 82 + 13 - 1)]:
        draw.rectangle(rect, fill=slot_dark, outline=metal, width=1)
        draw.line([(rect[0] + 2, rect[3] - 1), (rect[2] - 2, rect[3] - 1)], fill=copper_dark, width=1)

    # 8. 右侧结果槽面板（32×32 暗铜边大槽，逻辑槽 16×16 居中）
    draw.rectangle([212, 42, 250, 82], fill=wood, outline=metal_dark, width=1)
    frame_rect = (214, 46, 214 + 31, 46 + 31)
    draw.rectangle(frame_rect, fill=slot_dark, outline=copper_dark, width=1)
    result_rect = (222, 54, 222 + 15, 54 + 15)
    draw.rectangle(result_rect, outline=metal, width=1)

    # 9. 下方 AI 方案卡片区（压暗木板，1×3）
    draw.rectangle([4, 90, 251, 142], fill=wood, outline=metal_dark, width=1)
    for card_x in (8, 90, 172):
        draw.rectangle([card_x, 96, card_x + 76 - 1, 96 + 40 - 1],
                       outline=metal_dark, width=1)

    # 10. 底部玩家背包区（保持玩家熟悉的样子）
    draw.rectangle([4, 170, 173, 254], fill=wood, outline=metal_dark, width=1)
    for r in range(3):
        for c in range(9):
            sx, sy = 8 + c * 18, 176 + r * 18
            draw.rectangle([sx, sy, sx + 15, sy + 15], fill=slot_dark, outline=metal_dark, width=1)
    for c in range(9):
        sx, sy = 8 + c * 18, 234
        draw.rectangle([sx, sy, sx + 15, sy + 15], fill=slot_dark, outline=metal, width=1)

    # 11. 轻微噪点（质感）
    add_noise(img, intensity=6, seed=321)

    return img


# -----------------------------------------------------------------------------
# 方块纹理生成
# -----------------------------------------------------------------------------

def generate_block_texture() -> Image.Image:
    """生成 16×16 的炼金台方块纹理（复古风：木质台面 + 铜件 + 一点暗绿珐琅坩埚）。"""
    SIZE = 16
    img = Image.new("RGBA", (SIZE, SIZE), hex_rgba("#3A2A1A"))
    draw = ImageDraw.Draw(img)

    wood = hex_rgba("#4A3423")
    wood_dark = hex_rgba("#3A2A1A")
    metal_dark = hex_rgba("#3C3C3C")
    metal = hex_rgba("#565656")
    copper = hex_rgba("#B87333")
    copper_dark = hex_rgba("#8A5A2B")
    enamel = hex_rgba("#2E4A38")

    # 1. 木质台面基底
    draw.rectangle([0, 0, SIZE - 1, SIZE - 1], fill=wood)

    # 2. 木纹条
    draw.line([(1, 5), (SIZE - 2, 5)], fill=wood_dark, width=1)
    draw.line([(1, 10), (SIZE - 2, 10)], fill=wood_dark, width=1)

    # 3. 金属包边 + 四角包铁（炼金台：包铁改铜件色以区分）
    draw.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=copper_dark, width=1)
    for cx, cy in [(0, 0), (SIZE - 3, 0), (0, SIZE - 3), (SIZE - 3, SIZE - 3)]:
        draw.rectangle([cx, cy, cx + 2, cy + 2], fill=copper_dark, outline=copper, width=1)

    # 4. 四边中点铜铆钉
    for mx, my in [(SIZE // 2, 1), (SIZE // 2, SIZE - 2), (1, SIZE // 2), (SIZE - 2, SIZE // 2)]:
        draw.point([(mx, my)], fill=copper)

    # 5. 中央坩埚：金属沿 + 暗绿珐琅液面
    draw.rectangle([5, 5, SIZE - 6, SIZE - 6], fill=metal_dark, outline=copper_dark, width=1)
    draw.rectangle([6, 7, SIZE - 7, SIZE - 7], fill=enamel)
    draw.point([(7, 8), (9, 9)], fill=hex_rgba("#3E5C46"))  # 珐琅高光两点

    # 6. 噪点（质感）
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
