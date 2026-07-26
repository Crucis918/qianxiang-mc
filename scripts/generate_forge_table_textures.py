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
    """生成 256×256 的相之凝结台 GUI 背景。

    布局（与 ForgeTableScreen / ForgeTableMenu 坐标一致）：
      * 标题条 y 4..13
      * 材料槽 2 行 5 列：y=17 / y=35，x = 8/26/44/62/80（间距 18，色环 18×18）
      * 输入框 (104,17,144×14)，类型按钮 (104,35,24×14)，档位按钮 (132,35,24×14)
      * 状态条 (8,56,132×10)
      * 操作按钮 (146,50/66/82, 58×13)
      * 结果槽 32×32 框 (214,46)-(246,78)，逻辑槽 (222,54)
      * AI 推荐卡片 2×2 网格：(8|132, 102|132, 116×28)
      * 玩家背包 9×3 (8,176) 间距 18，快捷栏 (8,234)
    """
    W, H = 256, 256
    img = Image.new("RGBA", (W, H), hex_rgba("#0D0F14"))
    draw = ImageDraw.Draw(img)

    # 配色板
    base = hex_rgba("#151821")          # 主底色
    panel = hex_rgba("#1C1F2A")         # 面板色
    metal = hex_rgba("#2A2E3B")         # 金属色
    metal_light = hex_rgba("#3B4152")   # 亮金属
    rune_glow = hex_rgba("#4ECDC4", 200)   # 青色符文发光
    rune_glow_dim = hex_rgba("#4ECDC4", 80)
    gold = hex_rgba("#C9A227", 180)     # 金色点缀
    slot_dark = hex_rgba("#0B0C11")     # 槽位暗色

    # 1. 整体垂直渐变背景
    for y in range(H):
        t = y / H
        c = tuple(int(base[i] * (1 - t) + panel[i] * t) for i in range(4))
        draw.line([(0, y), (W, y)], fill=c)

    # 2. 外边框 —— 暗金属 + 四角符文三角
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

    # 类型 / 档位按钮凹槽（输入框下方，24×14）
    type_rect = (104, 35, 104 + 24 - 1, 35 + 14 - 1)
    tier_rect = (132, 35, 132 + 24 - 1, 35 + 14 - 1)
    for rect in [type_rect, tier_rect]:
        draw.rectangle(rect, fill=slot_dark, outline=metal_light, width=1)
        draw_glow_line(draw, rect[0] + 1, rect[3] - 1, rect[2] - 1, rect[3] - 1, gold, 1)

    # 5. 状态条凹槽（材料槽下方，10px 高，状态文字绘制在条内）
    status_rect = (8, 56, 8 + 132 - 1, 56 + 10 - 1)
    draw.rectangle(status_rect, fill=slot_dark, outline=metal_light, width=1)

    # 6. 左上材料槽面板（2 行 5 列）
    draw.rectangle([4, 14, 101, 54], fill=panel, outline=metal, width=1)
    draw_glow_line(draw, 6, 18, 6, 50, rune_glow_dim, 1)
    draw_glow_line(draw, 99, 18, 99, 50, rune_glow_dim, 1)

    # 10 个材料槽凹槽（2 行 5 列，间距 18，与 ForgeTableMenu 一致）
    slot_positions = [(8 + (i % 5) * 18, 17 + (i // 5) * 18) for i in range(10)]
    slot_ring_colors = (
        [(0, 255, 255, 120)] +      # 槽0 核心 - 青
        [(168, 85, 247, 100)] * 4 +  # 槽1-4 辅助 - 紫
        [(249, 115, 22, 100)] * 5    # 槽5-9 基底 - 橙
    )
    for (sx, sy), ring_color in zip(slot_positions, slot_ring_colors):
        # 槽位背景
        draw.rectangle([sx, sy, sx + 15, sy + 15], fill=slot_dark, outline=metal_light, width=1)
        # 色环暗示（外扩 1px，18×18，与屏幕端色环一致）
        draw.rectangle([sx - 1, sy - 1, sx + 16, sy + 16], outline=ring_color, width=1)

    # 7. 中部操作按钮面板
    draw.rectangle([142, 46, 209, 97], fill=panel, outline=metal, width=1)
    ask_rect = (146, 50, 146 + 58 - 1, 50 + 13 - 1)
    clear_rect = (146, 66, 146 + 58 - 1, 66 + 13 - 1)
    confirm_rect = (146, 82, 146 + 58 - 1, 82 + 13 - 1)
    for rect in [ask_rect, clear_rect, confirm_rect]:
        draw.rectangle(rect, fill=slot_dark, outline=metal_light, width=1)
        draw_glow_line(draw, rect[0] + 1, rect[3] - 1, rect[2] - 1, rect[3] - 1, rune_glow_dim, 1)

    # 8. 右侧结果槽面板（32×32 金边大槽，逻辑槽 16×16 居中）
    draw.rectangle([212, 42, 250, 82], fill=panel, outline=metal, width=1)
    frame_rect = (214, 46, 214 + 31, 46 + 31)   # 32×32 视觉框
    draw.rectangle(frame_rect, fill=slot_dark, outline=gold, width=1)
    result_rect = (222, 54, 222 + 15, 54 + 15)  # 逻辑槽
    draw.rectangle(result_rect, outline=metal_light, width=1)
    # 结果框四角小符文
    for ox, oy in [(-2, -2), (33, -2), (-2, 33), (33, 33)]:
        px, py = frame_rect[0] + ox, frame_rect[1] + oy
        draw.rectangle([px, py, px + 1, py + 1], fill=rune_glow)

    # 9. 下方 AI 推荐卡片区（2×2 网格）
    draw.rectangle([4, 90, 251, 164], fill=panel, outline=metal, width=1)
    for card_x in (8, 132):
        for card_y in (102, 132):
            draw.rectangle([card_x, card_y, card_x + 116 - 1, card_y + 28 - 1],
                           outline=metal, width=1)
            draw_glow_line(draw, card_x + 2, card_y + 1, card_x + 20, card_y + 1,
                           rune_glow_dim, 1)

    # 10. 底部玩家背包区（9×3 + 快捷栏，原版间距 18）
    draw.rectangle([4, 170, 173, 254], fill=panel, outline=metal, width=1)
    for r in range(3):
        for c in range(9):
            sx, sy = 8 + c * 18, 176 + r * 18
            draw.rectangle([sx, sy, sx + 15, sy + 15], fill=slot_dark, outline=metal, width=1)
    for c in range(9):
        sx, sy = 8 + c * 18, 234
        draw.rectangle([sx, sy, sx + 15, sy + 15], fill=slot_dark, outline=metal_light, width=1)

    # 11. 边缘符文 —— 沿左右两侧绘制竖排神秘符号
    for y in range(18, H - 18, 12):
        draw.rectangle([2, y, 3, y + 4], fill=rune_glow_dim)
        draw.rectangle([W - 4, y + 2, W - 3, y + 6], fill=rune_glow_dim)

    # 12. 顶部与底部横向发光纹
    draw_glow_line(draw, 20, 14, 60, 14, rune_glow_dim, 1)
    draw_glow_line(draw, 150, 14, 190, 14, rune_glow_dim, 1)
    draw_glow_line(draw, 180, H - 4, 248, H - 4, rune_glow_dim, 1)

    # 13. 添加轻微噪点
    add_noise(img, intensity=6, seed=123)

    return img


# -----------------------------------------------------------------------------
# 方块纹理生成
# -----------------------------------------------------------------------------

def generate_block_texture() -> Image.Image:
    """生成 16×16 的相之凝结台方块纹理。"""
    SIZE = 16
    img = Image.new("RGBA", (SIZE, SIZE), hex_rgba("#12141A"))
    draw = ImageDraw.Draw(img)

    # 配色
    base = hex_rgba("#181B24")
    dark = hex_rgba("#0E1015")
    metal = hex_rgba("#2E3342")
    metal_light = hex_rgba("#40485A")
    rune = hex_rgba("#4ECDC4", 200)
    rune_dim = hex_rgba("#4ECDC4", 90)
    gold = hex_rgba("#C9A227", 160)

    # 1. 基础金属面板（略小于全图，留出边框）
    draw.rectangle([1, 1, SIZE - 2, SIZE - 2], fill=base, outline=metal, width=1)

    # 2. 中心凹槽（祭坛感）
    draw.rectangle([5, 5, SIZE - 6, SIZE - 6], fill=dark, outline=metal_light, width=1)
    draw.rectangle([7, 7, SIZE - 8, SIZE - 8], outline=rune_dim, width=1)

    # 3. 四角符文刻痕
    corners = [(2, 2), (SIZE - 3, 2), (2, SIZE - 3), (SIZE - 3, SIZE - 3)]
    for cx, cy in corners:
        draw.rectangle([cx, cy, cx + 1, cy + 1], fill=rune)
        # 向中心延伸的短线
        if cx < SIZE // 2 and cy < SIZE // 2:
            draw.line([(cx + 2, cy), (cx + 2, cy + 1)], fill=rune_dim, width=1)
        elif cx >= SIZE // 2 and cy < SIZE // 2:
            draw.line([(cx - 1, cy), (cx - 1, cy + 1)], fill=rune_dim, width=1)
        elif cx < SIZE // 2 and cy >= SIZE // 2:
            draw.line([(cx + 2, cy), (cx + 2, cy + 1)], fill=rune_dim, width=1)
        else:
            draw.line([(cx - 1, cy), (cx - 1, cy + 1)], fill=rune_dim, width=1)

    # 4. 四边中点金色铆钉/符文
    midpoints = [(SIZE // 2, 1), (SIZE // 2, SIZE - 2), (1, SIZE // 2), (SIZE - 2, SIZE // 2)]
    for mx, my in midpoints:
        draw.rectangle([mx, my, mx + 1, my + 1], fill=gold)

    # 5. 从四角到中心的小刻线
    draw.line([(3, 3), (5, 5)], fill=rune_dim, width=1)
    draw.line([(SIZE - 4, 3), (SIZE - 6, 5)], fill=rune_dim, width=1)
    draw.line([(3, SIZE - 4), (5, SIZE - 6)], fill=rune_dim, width=1)
    draw.line([(SIZE - 4, SIZE - 4), (SIZE - 6, SIZE - 6)], fill=rune_dim, width=1)

    # 5b. 绚丽化：中心凹槽内发光符文核（青色亮核 + 四向星芒 + 金点）
    rune_hi = hex_rgba("#9FF5EC", 255)
    draw.point([(8, 7), (7, 8), (8, 8), (9, 8), (8, 9)], fill=rune)
    draw.point([(8, 8)], fill=rune_hi)                       # 白热符文心
    draw.point([(6, 8), (10, 8), (8, 6), (8, 10)], fill=rune_dim)  # 四向星芒
    draw.point([(7, 7), (9, 9)], fill=gold)                  # 对角金点
    # 四角符文刻痕提亮（与中心核呼应）
    for cx, cy in corners:
        draw.point([(cx, cy)], fill=rune_hi)

    # 6. 添加噪点
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
