#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成锻造台 GUI 背景纹理（176×166 RGBA）。
纯像素风、深色边框 + 石板/金属内底、立体凹凸槽位、中部箭头、结果区、强度预览区。

槽位坐标（相对 leftPos/topPos，与 ForgeTableMenu addSlot 对齐）：
  材料 0: (8,17)   材料 1: (26,17)   材料 2: (44,17)
  材料 3: (8,35)   材料 4: (26,35)
  结果 5: (133,36)
槽位凹槽按 18×18 画，覆盖 (x-1,y-1) ~ (x+16,y+16)。
"""
from PIL import Image, ImageDraw

W, H = 176, 166

# 配色
C_BORDER = (0x2B, 0x2B, 0x2B, 0xFF)          # 外深框
C_BORDER_HI = (0x4A, 0x4A, 0x4A, 0xFF)       # 外框高光
C_INNER_TOP = (0x6B, 0x6B, 0x6B, 0xFF)       # 内底渐变顶（浅）
C_INNER_BOT = (0x5A, 0x5A, 0x5A, 0xFF)       # 内底渐变底（深）
C_SLOT_DARK = (0x37, 0x37, 0x37, 0xFF)       # 槽位深底（凹陷）
C_SLOT_SHADOW = (0x1E, 0x1E, 0x1E, 0xFF)     # 槽位下/右阴影
C_SLOT_HI = (0x82, 0x82, 0x82, 0xFF)         # 槽位上/左高光
C_PREVIEW_BG = (0x48, 0x48, 0x48, 0xFF)      # 强度预览区底（更深）
C_PREVIEW_BORDER = (0x2B, 0x2B, 0x2B, 0xFF)
C_ARROW = (0xD0, 0xD0, 0xD0, 0xFF)           # 箭头主色
C_ARROW_SHADOW = (0x55, 0x55, 0x55, 0xFF)
C_ARROW_HI = (0xF0, 0xF0, 0xF0, 0xFF)
C_PANEL_HI = (0x7C, 0x7C, 0x7C, 0xFF)        # 面板上沿高光
C_PANEL_LO = (0x50, 0x50, 0x50, 0xFF)        # 面板下沿阴影
C_CORNER_RIVET = (0x82, 0x82, 0x82, 0xFF)    # 四角铆钉


def vgradient(img, x0, y0, x1, y1, top, bot):
    """竖直渐变填充矩形 [x0,x1) × [y0,y1)。"""
    if y1 <= y0:
        return
    for y in range(y0, y1):
        t = (y - y0) / max(1, (y1 - y0 - 1))
        r = int(top[0] + (bot[0] - top[0]) * t)
        g = int(top[1] + (bot[1] - top[1]) * t)
        b = int(top[2] + (bot[2] - top[2]) * t)
        a = int(top[3] + (bot[3] - top[3]) * t)
        for x in range(x0, x1):
            img.putpixel((x, y), (r, g, b, a))


def draw_slot_well(img, x, y):
    """在槽位坐标 (x,y) 画 18×18 凹槽：覆盖 (x-1,y-1)~(x+16,y+16)。
    像素风：上/左高光、下/右阴影、内深底。"""
    d = ImageDraw.Draw(img)
    sx, sy = x - 1, y - 1  # 凹槽左上角
    # 外框：上/左亮、下/右暗（凹陷感）
    d.rectangle([sx, sy, sx + 17, sy + 17], fill=C_SLOT_DARK)
    d.point((sx, sy), C_SLOT_HI)            # 左上角
    d.point((sx + 17, sy + 17), C_SLOT_SHADOW)  # 右下角
    # 上沿 +1px 高光
    for i in range(1, 16):
        img.putpixel((sx + i, sy), C_SLOT_HI)
        img.putpixel((sx, sy + i), C_SLOT_HI)
        img.putpixel((sx + 17, sy + 1 + i), C_SLOT_SHADOW)
        img.putpixel((sx + 1 + i, sy + 17), C_SLOT_SHADOW)
    # 内部再压一层底（留 1px 高光边）
    d.rectangle([sx + 1, sy + 1, sx + 16, sy + 16], fill=C_SLOT_DARK)


def draw_arrow(img, cx, cy):
    """在中部画一个像素风右指箭头（▶），中心 (cx,cy)。"""
    # 箭头杆：水平矩形，从 cx-14..cx-2
    bar_top, bar_bot = cy - 3, cy + 3
    for x in range(cx - 14, cx - 2):
        for y in range(bar_top, bar_bot + 1):
            img.putpixel((x, y), C_ARROW)
    # 箭头头：三角，顶点 cx+4
    head_half = 6  # 上下半高
    for i in range(0, 7):  # i=0..6 向右收窄
        x = cx - 2 + i
        hh = head_half - i  # 该列上下半宽
        for dy in range(-hh, hh + 1):
            xx, yy = x, cy + dy
            if 0 <= xx < W and 0 <= yy < H:
                img.putpixel((xx, yy), C_ARROW)
    # 高光：上沿亮一像素
    for x in range(cx - 14, cx + 5):
        yy = cy - 3 if x <= cx - 2 else cy - (6 - (x - (cx - 2)))
        if 0 <= yy < H and 0 <= x < W:
            # 只在杆顶和头沿画
            if x <= cx - 2:
                img.putpixel((x, cy - 3), C_ARROW_HI)
    # 阴影：下沿暗一像素
    for x in range(cx - 14, cx - 2):
        img.putpixel((x, cy + 3), C_ARROW_SHADOW)


def draw_preview_panel(img, x0, y0, x1, y1):
    """强度预览区：更深底 + 细边框 + 上/左高光下/右阴影。"""
    d = ImageDraw.Draw(img)
    d.rectangle([x0, y0, x1, y1], fill=C_PREVIEW_BG)
    # 边框
    for i in range(x0, x1 + 1):
        img.putpixel((i, y0), C_PREVIEW_BORDER)
        img.putpixel((i, y1), C_PREVIEW_BORDER)
    for j in range(y0, y1 + 1):
        img.putpixel((x0, j), C_PREVIEW_BORDER)
        img.putpixel((x1, j), C_PREVIEW_BORDER)
    # 内层高光/阴影（1px 内缩）
    for i in range(x0 + 1, x1):
        img.putpixel((i, y0 + 1), C_SLOT_HI)
        img.putpixel((i, y1 - 1), C_SLOT_SHADOW)
    for j in range(y0 + 1, y1):
        img.putpixel((x0 + 1, j), C_SLOT_HI)
        img.putpixel((x1 - 1, j), C_SLOT_SHADOW)


def main():
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))

    # 1. 外深框（整张）
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W - 1, H - 1], fill=C_BORDER)

    # 2. 内底渐变（1px 内缩）
    vgradient(img, 1, 1, W - 1, H - 1, C_INNER_TOP, C_INNER_BOT)

    # 3. 面板上沿高光 / 下沿阴影（内缩 1px 内再画 1px）
    for x in range(1, W - 1):
        img.putpixel((x, 1), C_PANEL_HI)
        img.putpixel((x, H - 2), C_PANEL_LO)
    for y in range(1, H - 1):
        img.putpixel((1, y), C_PANEL_HI)
        img.putpixel((W - 2, y), C_PANEL_LO)

    # 4. 四角铆钉（装饰，金属感）
    for (rx, ry) in [(3, 3), (W - 4, 3), (3, H - 4), (W - 4, H - 4)]:
        img.putpixel((rx, ry), C_CORNER_RIVET)
        img.putpixel((rx - 1, ry), C_BORDER)
        img.putpixel((rx + 1, ry), C_BORDER)
        img.putpixel((rx, ry - 1), C_BORDER)
        img.putpixel((rx, ry + 1), C_BORDER)

    # 5. 材料区槽位凹槽（5 个，与 ForgeTableMenu 对齐）
    material_slots = [(8, 17), (26, 17), (44, 17), (8, 35), (26, 35)]
    for (sx, sy) in material_slots:
        draw_slot_well(img, sx, sy)

    # 6. 中部箭头：中心 y 对齐结果槽中心(36+8=44)，x 居于材料区右缘(44+18=62)
    #    与结果槽左缘(133-1=132)之间 → cx≈97, cy≈44
    draw_arrow(img, 97, 44)

    # 7. 结果槽凹槽
    draw_slot_well(img, 133, 36)

    # 8. 强度预览面板（结果槽下方一带）
    #    结果槽底边 y=36+17=53；预览区 y 从 58 起，到 80 结束。
    #    背包第一行 y=84，留 4px 间隔。x 横跨右侧 96~168。
    draw_preview_panel(img, 96, 58, 168, 80)

    # 9. 在预览面板内画一个小标签条（可选装饰，预留"强度"二字位）
    #    左侧小竖条做点缀
    for y in range(60, 79):
        img.putpixel((98, y), C_PANEL_HI)

    out = "/home/zoumu/文档/MC experiment/qianxiang-mc/src/main/resources/assets/qianxiang/textures/gui/forge_table.png"
    img.save(out, "PNG")
    # 校验
    chk = Image.open(out)
    print("saved:", out)
    print("size:", chk.size, "mode:", chk.mode)
    assert chk.size == (176, 166), "尺寸必须是 176×166"
    assert chk.mode == "RGBA", "必须是 RGBA"
    print("校验通过：176×166 RGBA")


if __name__ == "__main__":
    main()
