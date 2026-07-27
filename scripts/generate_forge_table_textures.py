#!/usr/bin/env python3
"""
千相 (Qianxiang) - 相之凝结台纹理生成脚本

为 MVP 版本程序化生成：
  * textures/gui/forge_table.png  (256×256) —— 暗色金属神秘符文风格 GUI 背景
  * textures/block/forge_table.png (16×16)  —— 暗色金属符文刻痕方块纹理

设计约束：
  * 保留 GUI 现有坐标与功能不变，仅美化背景。
  * 顶部右侧输入框/类型档位按钮、左上 10 材料槽 2 行 5 列（含色环）、其下状态条、
    中部操作按钮、右侧 32×32 结果槽、下方 2 列 AI 推荐卡片、底部玩家背包，均保持清晰留白。
  * 方块纹理适配 cube 模型，可作为完整方块六个面的贴图。
"""

import os
import random
from pathlib import Path

from PIL import Image, ImageDraw


# -----------------------------------------------------------------------------
# 工具函数
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


def darken(color, factor: float):
    """将 RGBA 颜色变暗。"""
    return tuple(int(c * factor) if i < 3 else c for i, c in enumerate(color))


def lighten(color, factor: float):
    """将 RGBA 颜色变亮。"""
    return tuple(min(255, int(c + (255 - c) * factor)) if i < 3 else c for i, c in enumerate(color))


def draw_glow_line(draw: ImageDraw.ImageDraw, x1, y1, x2, y2, color, width: int = 1):
    """绘制带微弱发光效果的像素线。"""
    # 核心线
    draw.line([(x1, y1), (x2, y2)], fill=color, width=width)
    # 两侧更透明的光晕（仅当线条较细时）
    glow = color[:3] + (max(0, color[3] // 4),)
    if x1 == x2:  # 竖线
        draw.line([(x1 - 1, y1), (x2 - 1, y2)], fill=glow, width=1)
        draw.line([(x1 + 1, y1), (x2 + 1, y2)], fill=glow, width=1)
    elif y1 == y2:  # 横线
        draw.line([(x1, y1 - 1), (x2, y2 - 1)], fill=glow, width=1)
        draw.line([(x1, y1 + 1), (x2, y2 + 1)], fill=glow, width=1)


def add_noise(img: Image.Image, intensity: int = 8, seed: int = 42):
    """给图像添加轻微噪点，增强金属质感。"""
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
    """生成 256×256 的相之凝结台 GUI 背景（复古风：深木 + 深灰金属 + 少量铜）。

    布局坐标与代码硬约束一致（只换皮，不挪位置）：
      * 标题条 y 4..13
      * 材料区（文字列表用木质告示板）(4,14)-(97,108)
      * 输入框 (104,17,144×14)，类型按钮 (104,35,24×14)，档位按钮 (132,35,24×14)
      * 状态条 (8,108,132×10)
      * 操作按钮 (146,50/66/82, 58×13)
      * 结果槽 32×32 框 (214,46)-(246,78)，逻辑槽 (222,54)
      * AI 推荐卡片 2×2 网格：(8|132, 132|154, 116×20)
      * 玩家背包 9×3 (8,176) 间距 18，快捷栏 (8,234)
    """
    W, H = 256, 256
    img = Image.new("RGBA", (W, H), hex_rgba("#2A1E12"))
    draw = ImageDraw.Draw(img)

    # 复古配色板：深云杉木 + 深灰金属 + 少量铜，低饱和无霓虹
    wood_dark = hex_rgba("#3A2A1A")       # 主底色（深木）
    wood = hex_rgba("#4A3423")            # 面板木色
    wood_light = hex_rgba("#5C442E")      # 亮木（标题牌/告示板）
    metal_dark = hex_rgba("#3C3C3C")      # 深灰金属
    metal = hex_rgba("#565656")           # 金属
    copper_dark = hex_rgba("#8A5A2B")     # 暗铜（描边点缀）
    copper = hex_rgba("#B87333")          # 铜（铆钉）
    cream = hex_rgba("#D8CDB0")           # 米白（标题衬线）
    slot_dark = hex_rgba("#241A12")       # 凹槽深棕

    # 1. 深木面板垂直渐变（低对比）
    for y in range(H):
        t = y / H
        c = tuple(int(wood_dark[i] * (1 - t) + wood[i] * t) for i in range(4))
        draw.line([(0, y), (W, y)], fill=c)

    # 2. 外边框：细金属线 + 四角包铁（装饰做减法）
    draw.rectangle([0, 0, W - 1, H - 1], outline=metal, width=1)
    for cx, cy in [(1, 1), (W - 5, 1), (1, H - 5), (W - 5, H - 5)]:
        draw.rectangle([cx, cy, cx + 3, cy + 3], fill=metal_dark, outline=metal, width=1)

    # 3. 顶部标题条：木牌 + 米白衬线
    draw.rectangle([4, 4, W - 5, 13], fill=wood_light, outline=metal_dark, width=1)
    draw.line([(8, 8), (W - 9, 8)], fill=cream, width=1)

    # 4. 输入框凹槽（深棕描边）
    input_rect = (104, 17, 104 + 144 - 1, 17 + 14 - 1)
    draw.rectangle(input_rect, fill=slot_dark, outline=metal, width=1)

    # 类型 / 档位按钮凹槽（输入框下方，24×14）
    for rect in [(104, 35, 104 + 24 - 1, 35 + 14 - 1),
                 (132, 35, 132 + 24 - 1, 35 + 14 - 1)]:
        draw.rectangle(rect, fill=slot_dark, outline=metal, width=1)

    # 5. 状态条凹槽（材料区下方，10px 高）
    status_rect = (8, 108, 8 + 132 - 1, 108 + 10 - 1)
    draw.rectangle(status_rect, fill=slot_dark, outline=metal, width=1)

    # 6. 左上材料区：木质告示板（文字列表用），板缝 + 一角铜铆钉
    draw.rectangle([4, 14, 97, 108], fill=wood_light, outline=metal_dark, width=1)
    for sy in (38, 62, 86):  # 木板横缝
        draw.line([(5, sy), (96, sy)], fill=wood_dark, width=1)
    draw.rectangle([91, 17, 93, 19], fill=copper)  # 右上角铜铆钉

    # 7. 中部操作按钮面板
    draw.rectangle([142, 46, 209, 97], fill=wood, outline=metal_dark, width=1)
    for rect in [(146, 50, 146 + 58 - 1, 50 + 13 - 1),
                 (146, 66, 146 + 58 - 1, 66 + 13 - 1),
                 (146, 82, 146 + 58 - 1, 82 + 13 - 1)]:
        draw.rectangle(rect, fill=slot_dark, outline=metal, width=1)
        # 按钮下缘一线暗铜
        draw.line([(rect[0] + 2, rect[3] - 1), (rect[2] - 2, rect[3] - 1)], fill=copper_dark, width=1)

    # 8. 右侧结果槽面板（32×32 暗铜边大槽，逻辑槽 16×16 居中）
    draw.rectangle([212, 42, 250, 82], fill=wood, outline=metal_dark, width=1)
    frame_rect = (214, 46, 214 + 31, 46 + 31)
    draw.rectangle(frame_rect, fill=slot_dark, outline=copper_dark, width=1)
    result_rect = (222, 54, 222 + 15, 54 + 15)
    draw.rectangle(result_rect, outline=metal, width=1)

    # 9. 下方 AI 推荐卡片区（压暗木板，2×2 网格）
    draw.rectangle([4, 124, 251, 176], fill=wood, outline=metal_dark, width=1)
    for card_x in (8, 132):
        for card_y in (132, 154):
            draw.rectangle([card_x, card_y, card_x + 116 - 1, card_y + 20 - 1],
                           outline=metal_dark, width=1)

    # 10. 底部玩家背包区（9×3 + 快捷栏，保持玩家熟悉的样子）
    draw.rectangle([4, 170, 173, 254], fill=wood, outline=metal_dark, width=1)
    for r in range(3):
        for c in range(9):
            sx, sy = 8 + c * 18, 176 + r * 18
            draw.rectangle([sx, sy, sx + 15, sy + 15], fill=slot_dark, outline=metal_dark, width=1)
    for c in range(9):
        sx, sy = 8 + c * 18, 234
        draw.rectangle([sx, sy, sx + 15, sy + 15], fill=slot_dark, outline=metal, width=1)

    # 11. 轻微噪点（质感）
    add_noise(img, intensity=6, seed=123)

    return img


# -----------------------------------------------------------------------------
# 方块纹理生成
# -----------------------------------------------------------------------------

def generate_block_texture() -> Image.Image:
    """生成 16×16 的相之凝结台方块纹理（复古风：木质台面 + 钢灰包边 + 铜铆钉，铁砧感）。"""
    SIZE = 16
    img = Image.new("RGBA", (SIZE, SIZE), hex_rgba("#3A2A1A"))
    draw = ImageDraw.Draw(img)

    wood = hex_rgba("#4A3423")
    wood_dark = hex_rgba("#3A2A1A")
    metal_dark = hex_rgba("#3C3C3C")
    metal = hex_rgba("#565656")
    copper = hex_rgba("#B87333")
    copper_dark = hex_rgba("#8A5A2B")

    # 1. 木质台面基底
    draw.rectangle([0, 0, SIZE - 1, SIZE - 1], fill=wood)

    # 2. 木纹条（两条横缝，低对比）
    draw.line([(1, 5), (SIZE - 2, 5)], fill=wood_dark, width=1)
    draw.line([(1, 10), (SIZE - 2, 10)], fill=wood_dark, width=1)

    # 3. 钢灰金属包边（外圈 1px）+ 四角包铁
    draw.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=metal, width=1)
    for cx, cy in [(0, 0), (SIZE - 3, 0), (0, SIZE - 3), (SIZE - 3, SIZE - 3)]:
        draw.rectangle([cx, cy, cx + 2, cy + 2], fill=metal_dark, outline=metal, width=1)

    # 4. 四角铜铆钉（每角 1px，压在最外圈内侧）
    for px, py in [(3, 3), (SIZE - 4, 3), (3, SIZE - 4), (SIZE - 4, SIZE - 4)]:
        draw.point([(px, py)], fill=copper)

    # 5. 中央砧面凹槽（铁砧感）：钢灰 6×6 + 暗铜一丝
    draw.rectangle([5, 5, SIZE - 6, SIZE - 6], fill=metal_dark, outline=metal, width=1)
    draw.line([(6, 7), (SIZE - 7, 7)], fill=metal, width=1)
    draw.point([(7, 8)], fill=copper_dark)

    # 6. 添加噪点（质感）
    add_noise(img, intensity=8, seed=77)

    return img


# -----------------------------------------------------------------------------
# 主入口
# -----------------------------------------------------------------------------

def main():
    root = project_root()
    out_dir_gui = root / "src/main/resources/assets/qianxiang/textures/gui"
    out_dir_block = root / "src/main/resources/assets/qianxiang/textures/block"
    out_dir_gui.mkdir(parents=True, exist_ok=True)
    out_dir_block.mkdir(parents=True, exist_ok=True)

    gui_path = out_dir_gui / "forge_table.png"
    block_path = out_dir_block / "forge_table.png"

    gui_img = generate_gui_texture()
    block_img = generate_block_texture()

    gui_img.save(gui_path)
    block_img.save(block_path)

    print(f"Generated GUI texture: {gui_path} ({gui_img.size[0]}x{gui_img.size[1]})")
    print(f"Generated block texture: {block_path} ({block_img.size[0]}x{block_img.size[1]})")


if __name__ == "__main__":
    main()
