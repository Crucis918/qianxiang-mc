from PIL import Image

SRC = "/tmp/mctex/assets/minecraft/textures/models/armor/"
DST = "/home/zoumu/文档/MC experiment/qianxiang-mc/src/main/resources/assets/qianxiang/textures/models/armor/"

def recolor(src_path, dst_path, glow_points):
    img = Image.open(src_path).convert("RGBA")
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            # 暗部深紫(16,9,30) → 中部紫革(70,40,110) → 亮部青蓝相光(110,210,230)
            if lum < 0.5:
                t = lum / 0.5
                dr = int(16 + (70 - 16) * t); dg = int(9 + (40 - 9) * t); db = int(30 + (110 - 30) * t)
            else:
                t = (lum - 0.5) / 0.5
                dr = int(70 + (110 - 70) * t); dg = int(40 + (210 - 40) * t); db = int(110 + (230 - 110) * t)
            px[x, y] = (dr, dg, db, a)
    for (gx, gy) in glow_points:
        for dy in range(-1, 2):
            for dx in range(-1, 2):
                nx, ny = gx + dx, gy + dy
                if 0 <= nx < w and 0 <= ny < h:
                    r, g, b, a = px[nx, ny]
                    if a > 0:
                        px[nx, ny] = (160, 245, 255, a)
    img.save(dst_path)

recolor(SRC + "diamond_layer_1.png", DST + "phase_hide_layer_1.png",
        [(12, 12), (24, 24), (44, 20), (52, 20), (4, 24), (8, 24), (6, 28), (10, 28)])
recolor(SRC + "diamond_layer_2.png", DST + "phase_hide_layer_2.png",
        [(24, 22), (4, 22), (8, 22), (5, 27), (11, 27)])
print("done")
