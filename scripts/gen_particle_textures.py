#!/usr/bin/env python3
"""
千相 (Qianxiang) - 自定义粒子贴图生成脚本

程序化生成：
  * textures/particle/spark.png     (16×16) —— 柔光点（径向渐变圆，火花粒子用）
  * textures/particle/ring.png      (16×16) —— 环带（圆环渐变，冲击波环粒子用）

两张都应是「白底带 alpha 渐变」：粒子着色经 rCol/gCol/bCol 乘法生效，
贴图本身必须是白色系，否则元素色会被贴图底色污染。
"""

from pathlib import Path

from PIL import Image, ImageDraw


def project_root() -> Path:
    """脚本位于 scripts/，项目根目录为其父目录。"""
    return Path(__file__).resolve().parent.parent


def generate_spark() -> Image.Image:
    """16×16 柔光点：中心白热 → 边缘透明的径向渐变。"""
    SIZE = 16
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    cx = (SIZE - 1) / 2.0
    max_r = SIZE / 2.0
    pixels = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            dx = x - cx
            dy = y - cx
            dist = (dx * dx + dy * dy) ** 0.5
            t = max(0.0, 1.0 - dist / max_r)
            # 亮度与 alpha 都按 t² 衰减：核心亮白、边缘干净
            v = int(255 * min(1.0, t * 1.4))
            a = int(255 * t * t)
            pixels[x, y] = (v, v, v, a)
    return img


def generate_ring() -> Image.Image:
    """16×16 环带：外缘实、向内羽化的圆环（冲击波扩散用）。"""
    SIZE = 16
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    cx = (SIZE - 1) / 2.0
    outer = SIZE / 2.0 - 0.5
    inner = outer * 0.55  # 环带内径
    pixels = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            dx = x - cx
            dy = y - cx
            dist = (dx * dx + dy * dy) ** 0.5
            if dist > outer or dist < inner:
                a = 0
            else:
                # 外缘最实，向内羽化到 0
                t = 1.0 - (outer - dist) / (outer - inner)
                a = int(255 * t * t)
            pixels[x, y] = (255, 255, 255, a)
    return img


def main():
    root = project_root()
    out_dir = root / "src/main/resources/assets/qianxiang/textures/particle"
    out_dir.mkdir(parents=True, exist_ok=True)

    spark = generate_spark()
    ring = generate_ring()
    spark_path = out_dir / "spark.png"
    ring_path = out_dir / "ring.png"
    spark.save(spark_path)
    ring.save(ring_path)
    print(f"Generated particle texture: {spark_path} ({spark.size[0]}x{spark.size[1]})")
    print(f"Generated particle texture: {ring_path} ({ring.size[0]}x{ring.size[1]})")


if __name__ == "__main__":
    main()
