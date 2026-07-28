package com.qianxiang.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.phase.ComposedAttributes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import org.joml.Vector3f;
import java.util.EnumMap;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 武器外貌即时生成——按产物的 {@link ComposedAttributes} 程序化画出 32×32 纹理。
 * <p>
 * 思路与设计期的 Pillow 原型一致，但纯 Java 实现（{@link NativeImage} 逐像素）：
 * </p>
 * <ul>
 *   <li>基底形状按原型：ember_blade→直剑 / bone_blade→骨刀 / phase_staff→法杖 /
 *       phase_shield 与相之防具→盾 / phase_hoe 与浇水壶→锄 / spell_book→书；
 *       另支持 pan/cleaver/scythe/bow/mace/wand/pickaxe/shovel 等扩展形状，
 *       由产物 {@code CUSTOM_NAME} 关键词驱动（「我要平底锅就画个平底锅」）。</li>
 *   <li>刃缘 / 宝石 / 符文按主导效果着色：ignite=橙红、poison=毒绿、frost=冰蓝、
 *       lifesteal=血红、shadow=紫黑、holy=金白、mana=紫电、wither=暗黑，无效果=素铁灰。</li>
 *   <li>绚丽化管线：基底对角渐变（不平涂）、刃缘渐变白热尖端、宝石十字闪光、
 *       符文发光；稀有度 tier（0 普通 / 1 稀有 / 2 史诗 / 3 传奇）由效果最高等级推导、
 *       {@code powerScore} 兜底：驱动 1px 外框主色（白灰/青/紫/金）、
 *       内外双层渐强光晕、紫/金宝石与角星闪光（见 {@link #drawTierFrame}），
 *       传奇另加 4~6 个飘浮粒子点。</li>
 * </ul>
 * <p>
 * 生成的纹理以 {@code qianxiang:dynamic/<形状>_<颜色>_<档位>} 注册进 TextureManager，
 * 客户端缓存防重复生成；同一内容 hash 的所有产物共享一份纹理与一组烘焙 quad。
 * 材料变化 → compose 结果变 → (形状, 颜色, 档位) hash 变 → 自动生成新纹理，
 * 物品栏与锻造台结果槽预览即时换肤。
 * </p>
 * <p>仅客户端：本类只被 {@code com.qianxiang.client} 包内代码引用，服务端不加载。</p>
 */
public final class DynamicWeaponTexture {
    /** 参与动态外貌的物品 id（列表中未注册的物品自动跳过，见 wrap 侧 containsKey 判断）。 */
    public static final List<String> SUPPORTED_ITEM_IDS = List.of(
            "ember_blade", "bone_blade", "phase_staff", "phase_shield",
            "phase_helmet", "phase_chestplate", "phase_leggings", "phase_boots",
            "phase_hoe", "phase_watering_can", "spell_book");

    /** powerScore 兜底档位阈值：≥T1 稀有，≥T2 史诗，≥T3 传奇（无效果等级时兜底用）。 */
    private static final double T1 = 8.0;
    private static final double T2 = 16.0;
    private static final double T3 = 28.0;

    private static final int SIZE = 32;

    /** 内容 hash → 变体（纹理 + RenderType + quad）。 */
    private static final Map<VariantKey, Variant> CACHE = new ConcurrentHashMap<>();

    private DynamicWeaponTexture() {
    }

    /** 一次渲染所需的全部资源：动态纹理 id、对应 RenderType、2D 挤出 quad 与手持 3D 挤出 quad。 */
    public static final class Variant {
        public final ResourceLocation texture;
        public final RenderType renderType;
        /** 2D 薄片（GUI/GROUND/FIXED 用）。 */
        public final List<BakedQuad> quads;
        /** 按形态剖面参数生成的 3D 几何（FIRST/THIRD_PERSON 手持用）。 */
        public final List<BakedQuad> quads3d;

        Variant(ResourceLocation texture, RenderType renderType, List<BakedQuad> quads, List<BakedQuad> quads3d) {
            this.texture = texture;
            this.renderType = renderType;
            this.quads = quads;
            this.quads3d = quads3d;
        }
    }

    /**
     * 纹理内容 hash：形状 × 主导效果颜色 × 装饰档位 × 核心材料基底族。
     * baseFamily 必须进 key——同 shape+color+tier 的不同基底族（钢灰/象牙白/深棕/革棕）
     * 若共用缓存会串色（缓存规模按 9 形态 × 9 色板 × 4 档 × 5 族 ≈ 1620 上界，仍远小于图集）。
     */
    public record VariantKey(String shape, String color, int tier, String baseFamily) {
        String path() {
            return "dynamic/" + shape + "_" + color + "_" + tier + "_" + baseFamily;
        }
    }

    /**
     * 按物品栈内容取动态变体；无 {@link ComposedAttributes} 时返回 null（调用方走静态纹理兜底）。
     * 渲染线程调用；生成过程全部 try-catch，失败退化为 null 兜底。
     */
    public static Variant variantFor(ItemStack stack) {
        try {
            ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
            if (attr == null) return null;

            // key 计算本身不便宜（shapeFor 要 toLowerCase + 十余次 contains，
            // colorKeyFor 要遍历 grantedEffects 并 toString 每个 key），
            // 而本方法在每帧、每个可见物品栈上都会被调用（GUI 里 40 格 × 60FPS）。
            // 按「物品 + 组件」做一层记忆化：同一把武器连续帧直接命中。
            ComposedAttributes lastAttr = lastKeyAttr;
            if (lastAttr == attr && lastKeyItem == stack.getItem() && lastKey != null) {
                Variant cached = CACHE.get(lastKey);
                if (cached != null) return cached == FAILED ? null : cached;
            }

            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            VariantKey key = new VariantKey(shapeFor(stack, path, attr), colorKeyFor(attr), tierFor(attr),
                    attr.baseFamily());
            lastKeyAttr = attr;
            lastKeyItem = stack.getItem();
            lastKey = key;

            Variant variant = CACHE.computeIfAbsent(key, k -> {
                try {
                    return bake(k);
                } catch (Throwable t) {
                    // 写入哨兵：bake 失败时若不留映射，computeIfAbsent 会每帧重试
                    // （每次重绘 32×32 并尝试注册纹理），表现为持续卡顿且零日志。
                    Qianxiang.LOGGER.warn("[Qianxiang] 动态武器变体烘焙失败，该变体改用静态纹理：{}（{}）",
                            k.path(), t.toString());
                    return FAILED;
                }
            });
            return variant == FAILED ? null : variant;
        } catch (Throwable t) {
            return null;
        }
    }

    /** bake 失败哨兵：占住 CACHE 的位置，阻止每帧重试。 */
    private static final Variant FAILED =
            new Variant(ResourceLocation.fromNamespaceAndPath("qianxiang", "failed_variant"),
                    null, List.of(), List.of());

    // 单条记忆化（渲染是单线程的；即便偶发竞态也只是多算一次 key，无正确性问题）
    private static ComposedAttributes lastKeyAttr;
    private static net.minecraft.world.item.Item lastKeyItem;
    private static VariantKey lastKey;

    // ============================ 变体烘焙 ============================

    private static Variant bake(VariantKey key) {
        NativeImage image = paint(key);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("qianxiang", key.path());
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        RenderType renderType = RenderType.entityTranslucentCull(id);
        return new Variant(id, renderType, bakeQuads(id, image),
                bakeQuads3D(id, image, com.qianxiang.combat.WeaponFormProfile.extrusionFor(key.shape()),
                        template(key.shape())));
    }

    /**
     * 把 SIZE×SIZE 图像按 item/generated 的挤出规则（前后面 + 透明边缘侧条）烘成 quad。
     * 伪造一个 UV 覆盖 0..1 的 {@link TextureAtlasSprite}，渲染时绑定的是我们自己的
     * 动态纹理而非图集，所以 UV 正好铺满整张纹理；ItemModelGenerator 按图像宽度自动缩放。
     */
    private static List<BakedQuad> bakeQuads(ResourceLocation id, NativeImage image) {
        SpriteContents contents = new SpriteContents(id, new FrameSize(SIZE, SIZE), image, ResourceMetadata.EMPTY);
        TextureAtlasSprite sprite = new TextureAtlasSprite(TextureAtlas.LOCATION_BLOCKS, contents, SIZE, SIZE, 0, 0) {
        };
        List<BlockElement> elements = new ItemModelGenerator().processFrames(-1, "layer0", contents);
        FaceBakery bakery = new FaceBakery();
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockElement element : elements) {
            for (Map.Entry<Direction, net.minecraft.client.renderer.block.model.BlockElementFace> entry : element.faces.entrySet()) {
                quads.add(bakery.bakeQuad(element.from, element.to, entry.getValue(), sprite,
                        entry.getKey(), BlockModelRotation.X0_Y0, element.rotation, false));
            }
        }
        return List.copyOf(quads);
    }

    /**
     * 手持 3D 几何：按形态剖面参数（{@link com.qianxiang.combat.WeaponFormProfile.ExtrusionProfile}）
     * 逐像素分区挤出——刃体区（B/b/E/G/T/R）用刃厚度、柄区（H/W）用柄厚度，
     * 前后大面 + 透明邻接方向的侧条；法线/着色与 2D 路径同一 FaceBakery 管线。
     * 零新资产：几何全代码生成。模型空间恒为 0..16，texel 坐标按 16/SIZE 缩放，
     * 纹理分辨率（32×32）不影响挤出厚度观感。
     */
    private static List<BakedQuad> bakeQuads3D(ResourceLocation id, NativeImage image,
                                               com.qianxiang.combat.WeaponFormProfile.ExtrusionProfile profile,
                                               String[] template) {
        SpriteContents contents = new SpriteContents(id, new FrameSize(SIZE, SIZE), image, ResourceMetadata.EMPTY);
        TextureAtlasSprite sprite = new TextureAtlasSprite(TextureAtlas.LOCATION_BLOCKS, contents, SIZE, SIZE, 0, 0) {
        };
        FaceBakery bakery = new FaceBakery();
        List<BakedQuad> quads = new ArrayList<>();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if ((image.getPixelRGBA(x, y) >>> 24) == 0) continue;
                char role = x < template[y].length() ? template[y].charAt(x) : '.';
                float thick = (role == 'H' || role == 'W') ? profile.handleThickness()
                        : profile.bladeThickness();
                float zMin = 8.0f - thick / 2.0f;
                float zMax = 8.0f + thick / 2.0f;
                // 模型空间恒为 0..16：texel 坐标按 16/SIZE 缩放（32×32 下每像素 0.5 格）。
                float k = 16.0f / SIZE;
                Vector3f from = new Vector3f(x * k, (SIZE - 1 - y) * k, zMin);
                Vector3f to = new Vector3f((x + 1) * k, (SIZE - y) * k, zMax);
                BlockFaceUV uv = new BlockFaceUV(
                        new float[]{x * k, (SIZE - 1 - y) * k, (x + 1) * k, (SIZE - y) * k}, 0);
                Map<Direction, BlockElementFace> faces = new EnumMap<>(Direction.class);
                faces.put(Direction.NORTH, new BlockElementFace(null, -1, "layer0", uv));
                faces.put(Direction.SOUTH, new BlockElementFace(null, -1, "layer0", uv));
                if (!opaque(image, x - 1, y)) {
                    faces.put(Direction.WEST, new BlockElementFace(null, -1, "layer0", uv));
                }
                if (!opaque(image, x + 1, y)) {
                    faces.put(Direction.EAST, new BlockElementFace(null, -1, "layer0", uv));
                }
                if (!opaque(image, x, y - 1)) {
                    faces.put(Direction.UP, new BlockElementFace(null, -1, "layer0", uv));
                }
                if (!opaque(image, x, y + 1)) {
                    faces.put(Direction.DOWN, new BlockElementFace(null, -1, "layer0", uv));
                }
                BlockElement element = new BlockElement(from, to, faces, null, true);
                for (Map.Entry<Direction, BlockElementFace> entry : element.faces.entrySet()) {
                    quads.add(bakery.bakeQuad(element.from, element.to, entry.getValue(), sprite,
                            entry.getKey(), BlockModelRotation.X0_Y0, element.rotation, false));
                }
            }
        }
        return List.copyOf(quads);
    }

    // ============================ 像素绘制 ============================

    private static NativeImage paint(VariantKey key) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
        String[] template = template(key.shape());
        Palette palette = PALETTES.getOrDefault(key.color(), PALETTES.get("none"));
        for (int y = 0; y < SIZE; y++) {
            String row = template[y];
            for (int x = 0; x < SIZE; x++) {
                char c = x < row.length() ? row.charAt(x) : '.';
                int color = roleColor(c, key, palette, x, y);
                if (color != 0) image.setPixelRGBA(x, y, color);
            }
        }
        // 符文发光：R 像素四邻空位涂效果亮色的微光。
        addRuneGlow(image, template, palette);
        // 宝石十字闪光：G 区域中心白热核 + 四向星芒。
        drawGemSparkle(image, template, palette, key.tier());
        if (key.tier() >= 1) {
            // 稀有度光晕：稀有微光 / 史诗中光晕 / 传奇强光晕，颜色跟随外框主色（内外双层）。
            addGlow(image, TIER_FRAME_COLORS[key.tier()], TIER_GLOW_ALPHAS[key.tier()]);
        }
        if (key.tier() >= 3) {
            // 传奇：4~6 个随机亮色飘浮粒子点（种子由内容 hash 决定，同一变体位置稳定）。
            drawFloatingParticles(image, key);
        }
        drawTierFrame(image, key.tier());
        return image;
    }

    /** 稀有度外框主色：0 普通白灰（半透明） / 1 稀有青 / 2 史诗紫 / 3 传奇金。 */
    private static final int[] TIER_FRAME_COLORS = {
            abgr(0x90, 0xC4, 0xC8, 0xCE),
            abgr(0x46, 0xD0, 0xDC),
            abgr(0xA8, 0x5C, 0xE8),
            abgr(0xFF, 0xC8, 0x40)
    };

    /** 各档光晕 alpha：稀有微光 / 史诗中 / 传奇强。 */
    private static final int[] TIER_GLOW_ALPHAS = {0, 0x30, 0x55, 0x8A};

    /** 角星颜色：史诗紫亮星 / 传奇金白亮星。 */
    private static final int STAR_EPIC = abgr(0xD8, 0xA8, 0xFF);
    private static final int STAR_LEGENDARY = abgr(0xFF, 0xF2, 0xC0);

    /**
     * 稀有度外框：paint 末尾调用，按 tier 在画布边缘画 1px 外框，并按强度加角星——
     * 史诗两颗对角紫星，传奇四角金星点闪光。外框压在光晕之上，保证描边清晰。
     */
    private static void drawTierFrame(NativeImage image, int tier) {
        int frame = TIER_FRAME_COLORS[Math.min(tier, TIER_FRAME_COLORS.length - 1)];
        for (int i = 0; i < SIZE; i++) {
            image.setPixelRGBA(i, 0, frame);
            image.setPixelRGBA(i, SIZE - 1, frame);
            image.setPixelRGBA(0, i, frame);
            image.setPixelRGBA(SIZE - 1, i, frame);
        }
        if (tier >= 3) {
            drawCornerStar(image, 1, 1, STAR_LEGENDARY);
            drawCornerStar(image, SIZE - 2, 1, STAR_LEGENDARY);
            drawCornerStar(image, 1, SIZE - 2, STAR_LEGENDARY);
            drawCornerStar(image, SIZE - 2, SIZE - 2, STAR_LEGENDARY);
        } else if (tier == 2) {
            drawCornerStar(image, 1, 1, STAR_EPIC);
            drawCornerStar(image, SIZE - 2, SIZE - 2, STAR_EPIC);
        }
    }

    /** 四角星点闪光：s×s 核 + 四向 s 长星芒（s=SIZE/16，32×32 下为 2px 核 + 2px 臂，压在外框内侧）。 */
    private static void drawCornerStar(NativeImage image, int cx, int cy, int color) {
        int s = SIZE / 16;
        for (int i = -s; i < 2 * s; i++) {
            for (int dy = 0; dy < s; dy++) {
                if (inside(cx + i, cy + dy)) image.setPixelRGBA(cx + i, cy + dy, color);
            }
            for (int dx = 0; dx < s; dx++) {
                if (inside(cx + dx, cy + i)) image.setPixelRGBA(cx + dx, cy + i, color);
            }
        }
    }

    /** 核心材料基底族 → 刃体色（钢灰/象牙白/深棕/革棕；空串回退形状写死色）。 */
    private static final Map<String, Integer> FAMILY_BODY = Map.of(
            "metal", abgr(0x9A, 0xA0, 0xA8),
            "bone", abgr(0xE8, 0xE0, 0xCC),
            "wood", abgr(0x6B, 0x4A, 0x2E),
            "hide", abgr(0x8A, 0x6B, 0x4A)
    );

    /** 刃体色解析：baseFamily 命中用族色，否则回退形状写死色（书面仍吃效果暗色）。 */
    private static int familyBody(String baseFamily, int fallback) {
        return FAMILY_BODY.getOrDefault(baseFamily == null ? "" : baseFamily, fallback);
    }

    /** 角色符号 → 颜色；返回 0 表示留空（全透明）。x/y 用于对角渐变与白热刃尖定位。
     *  <p>新词表（32×32 手绘模板，显式色阶）：o=深描边、D/B/L=刃体暗/中/亮档、
     *  e/E/X=刃缘暗档/中档/白热、H/h=柄暗/亮、W=缠绕、T/t=亮金/暗金、G=宝石、R=符文；
     *  旧符号（b 等）映射进同档色阶保兼容（7 张 ×2 放大兜底模板用）。</p> */
    private static int roleColor(char c, VariantKey key, Palette palette, int x, int y) {
        ShapeBase base = BASES.getOrDefault(key.shape(), BASES.get("sword"));
        // 刃体着色=核心材料色：baseFamily 命中用族色，空串回退形状写死色（向后兼容）；
        // 书面（coverFromEffect）不吃族色覆盖——封面仍走主导效果暗色。
        int baseColor = base.coverFromEffect() ? base.body(palette)
                : familyBody(key.baseFamily(), base.body(palette));
        return switch (c) {
            // 深色描边（剪影骨架，统一深灰棕）
            case 'o' -> abgr(0x26, 0x22, 0x1F);
            // 刃体三档色阶（基底族 ramp）：暗档背光 / 中档主体（带轻微对角体积）/ 亮档高光
            case 'D' -> shade(baseColor, 0.62);
            case 'B' -> gradient(baseColor, x, y);
            case 'L' -> shade(baseColor, 1.28);
            case 'b' -> gradient(shade(baseColor, 0.62), x, y);
            // 刃缘三档：暗档 / 中档（白热渐变）/ 白热刃口
            case 'e' -> palette.dark();
            case 'E' -> edgeHot(palette.main(), x, y);
            case 'X' -> abgr(0xFF, 0xF6, 0xE2);
            // 宝石随稀有度 tier 变色：史诗紫宝石、传奇金宝石，低档回落效果色。
            case 'G' -> key.tier() >= 3 ? abgr(0xFF, 0xD2, 0x55)
                    : key.tier() == 2 ? abgr(0xB8, 0x6A, 0xF0)
                    : key.tier() == 1 ? palette.bright() : palette.main();
            case 'R' -> "none".equals(key.color()) ? shade(baseColor, 0.62) : palette.bright();
            // 护手：亮金（tier≥1）/ 暗金（恒）
            case 'T' -> key.tier() >= 1 ? abgr(0xFF, 0xD8, 0xB2, 0x4A) : shade(baseColor, 0.62);
            case 't' -> abgr(0xFF, 0x8A, 0x5A, 0x2B);
            case 'H' -> base.handle();
            case 'h' -> shade(base.handle(), 1.35);
            case 'W' -> base.wrap();
            default -> 0;
        };
    }

    /** 对角渐变打底：左上提亮（factor>1）→ 右下压暗，给基底体积感。 */
    private static int gradient(int color, int x, int y) {
        double t = (x + y) / (2.0 * (SIZE - 1));
        return shade(color, 1.22 - 0.5 * t);
    }

    /** 刃缘白热尖端：t=0 在左下（柄侧），t=1 在右上（刃尖），尖端渐变为白热色。 */
    private static int edgeHot(int main, int x, int y) {
        double t = (x + (SIZE - 1 - y)) / (2.0 * (SIZE - 1));
        if (t < 0.72) return main;
        return lerpColor(main, abgr(0xFF, 0xF6, 0xE2), (t - 0.72) / 0.28);
    }

    /** 符文发光：R 像素四邻的空像素涂一层效果亮色微光。 */
    private static void addRuneGlow(NativeImage image, String[] template, Palette palette) {
        int soft = (palette.bright() & 0x00FFFFFF) | (0x55 << 24);
        for (int y = 0; y < SIZE; y++) {
            String row = template[y];
            for (int x = 0; x < SIZE; x++) {
                if (x >= row.length() || row.charAt(x) != 'R') continue;
                if (!opaque(image, x + 1, y) && inside(x + 1, y)) image.setPixelRGBA(x + 1, y, soft);
                if (!opaque(image, x - 1, y) && inside(x - 1, y)) image.setPixelRGBA(x - 1, y, soft);
                if (!opaque(image, x, y + 1) && inside(x, y + 1)) image.setPixelRGBA(x, y + 1, soft);
                if (!opaque(image, x, y - 1) && inside(x, y - 1)) image.setPixelRGBA(x, y - 1, soft);
            }
        }
    }

    /** 宝石十字闪光：G 区域几何中心白热核 + 四向星芒（史诗以上星芒加长到 2px）。 */
    private static void drawGemSparkle(NativeImage image, String[] template, Palette palette, int tier) {
        int sx = 0, sy = 0, n = 0;
        for (int y = 0; y < SIZE; y++) {
            String row = template[y];
            for (int x = 0; x < SIZE; x++) {
                if (x < row.length() && row.charAt(x) == 'G') {
                    sx += x;
                    sy += y;
                    n++;
                }
            }
        }
        if (n == 0) return;
        int cx = sx / n, cy = sy / n;
        image.setPixelRGBA(cx, cy, abgr(0xFF, 0xFF, 0xFF));
        int arms = palette.bright();
        // 星芒长度随分辨率放大：s=SIZE/16，史诗以上再翻倍。
        int len = (tier >= 2 ? 2 : 1) * (SIZE / 16);
        for (int i = 1; i <= len; i++) {
            if (inside(cx + i, cy)) image.setPixelRGBA(cx + i, cy, arms);
            if (inside(cx - i, cy)) image.setPixelRGBA(cx - i, cy, arms);
            if (inside(cx, cy + i)) image.setPixelRGBA(cx, cy + i, arms);
            if (inside(cx, cy - i)) image.setPixelRGBA(cx, cy - i, arms);
        }
    }

    /** 传奇 tier：4~6 个随机亮色飘浮粒子点（s×s 方块，s=SIZE/16），落在空像素上（含半透明光晕位避让实形）。 */
    private static void drawFloatingParticles(NativeImage image, VariantKey key) {
        java.util.Random rnd = new java.util.Random((key.shape() + "/" + key.color()).hashCode());
        int s = SIZE / 16;
        int count = 4 + rnd.nextInt(3);
        int placed = 0, guard = 0;
        while (placed < count && guard++ < 64) {
            int x = rnd.nextInt(SIZE - s + 1), y = rnd.nextInt(SIZE - s + 1);
            boolean clear = true;
            for (int dy = 0; dy < s && clear; dy++) {
                for (int dx = 0; dx < s && clear; dx++) {
                    clear = (image.getPixelRGBA(x + dx, y + dy) >>> 24) == 0;
                }
            }
            if (!clear) continue;
            for (int dy = 0; dy < s; dy++) {
                for (int dx = 0; dx < s; dx++) {
                    image.setPixelRGBA(x + dx, y + dy, STAR_LEGENDARY);
                }
            }
            placed++;
        }
    }

    private static boolean inside(int x, int y) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE;
    }

    /** 光晕按分辨率分层：内 s 层强光晕 + 外 s 层弱光晕（s=SIZE/16，32×32 下内 2px/外 2px），紧贴非空像素。 */
    private static void addGlow(NativeImage image, int color, int alpha) {
        int s = SIZE / 16;
        // dist：0=实形，-1=未触及；逐层 4 邻域传播到 2s 层。
        int[][] dist = new int[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                dist[x][y] = (image.getPixelRGBA(x, y) >>> 24) != 0 ? 0 : -1;
            }
        }
        for (int d = 1; d <= 2 * s; d++) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    if (dist[x][y] != -1) continue;
                    if (glowAt(dist, x + 1, y, d - 1) || glowAt(dist, x - 1, y, d - 1)
                            || glowAt(dist, x, y + 1, d - 1) || glowAt(dist, x, y - 1, d - 1)) {
                        dist[x][y] = d;
                    }
                }
            }
        }
        int softOuter = (color & 0x00FFFFFF) | ((alpha / 2) << 24);
        int softInner = (color & 0x00FFFFFF) | (alpha << 24);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (dist[x][y] >= 1 && dist[x][y] <= s) image.setPixelRGBA(x, y, softInner);
                else if (dist[x][y] > s) image.setPixelRGBA(x, y, softOuter);
            }
        }
    }

    private static boolean glowAt(int[][] dist, int x, int y, int d) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE && dist[x][y] == d;
    }

    private static boolean opaque(NativeImage image, int x, int y) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE && (image.getPixelRGBA(x, y) >>> 24) != 0;
    }

    /** NativeImage 像素为 ABGR32 排列。 */
    private static int abgr(int a, int r, int g, int b) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int abgr(int r, int g, int b) {
        return abgr(0xFF, r, g, b);
    }

    /** 按比例压暗/提亮一个 ABGR 颜色（保持 alpha，分量钳制到 0..255）。 */
    private static int shade(int color, double factor) {
        int a = color >>> 24;
        int b = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int r = color & 0xFF;
        return abgr(a, clamp255((int) (r * factor)), clamp255((int) (g * factor)),
                clamp255((int) (b * factor)));
    }

    /** 两个 ABGR 颜色线性插值（分量钳制）。 */
    private static int lerpColor(int c1, int c2, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        int a = (int) ((c1 >>> 24) + (((c2 >>> 24) - (c1 >>> 24)) * t));
        int b = (int) (((c1 >> 16) & 0xFF) + ((((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t));
        int g = (int) (((c1 >> 8) & 0xFF) + ((((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t));
        int r = (int) ((c1 & 0xFF) + (((c2 & 0xFF) - (c1 & 0xFF)) * t));
        return abgr(clamp255(a), clamp255(r), clamp255(g), clamp255(b));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ============================ 变体键推导 ============================

    /**
     * 按物品栈选形状——「描述驱动形状」优先：产物 {@code CUSTOM_NAME} 命中关键词
     * （如「平底锅」「镰刀」「弓」）时直接画对应形状；否则按物品 id + 产物属性特征——
     * 「换模型的及时创作」：
     * 同一把金属刃，高攻低攻速→巨剑、快攻速→匕首、默认→标准剑；
     * 属性变 → 形状变 → 纹理 hash 变 → 即时换肤。与 EF 动作分类同阈值，外观与动作匹配。
     */
    private static String shapeFor(ItemStack stack, String itemPath, ComposedAttributes attr) {
        // 形态事实源优先：组件 AppearanceData.form → 映射表形状（锻造时一次推导写入）
        if (attr != null) {
            var profile = com.qianxiang.combat.WeaponFormProfile.of(attr.form());
            if (profile != null) return profile.shape();
        }
        String named = shapeFromCustomName(stack);
        if (named != null) return named;
        return switch (itemPath) {
            case "bone_blade" -> "katana";
            case "phase_staff" -> "staff";
            case "phase_shield", "phase_helmet", "phase_chestplate", "phase_leggings", "phase_boots" -> "shield";
            case "phase_hoe" -> "axe";
            case "phase_watering_can" -> "hammer";
            case "spell_book" -> "book";
            default -> {
                // 金属刃（ember_blade 及兜底）：按攻击/速度特征换模型（阈值集中在 WeaponFormProfile）
                if (attr != null) {
                    double dmg = attr.attackDamage();
                    double spd = attr.attackSpeed();
                    if (dmg >= com.qianxiang.combat.WeaponFormProfile.FALLBACK_HEAVY_DAMAGE
                            && spd <= com.qianxiang.combat.WeaponFormProfile.FALLBACK_HEAVY_MAX_SPEED) {
                        yield "greatsword"; // 重型 → 巨剑
                    }
                    if (spd >= com.qianxiang.combat.WeaponFormProfile.LIGHT_MIN_SPEED) {
                        yield "dagger";     // 快速 → 匕首
                    }
                    if (dmg >= com.qianxiang.combat.WeaponFormProfile.FALLBACK_KATANA_DAMAGE) {
                        yield "katana";     // 中攻 → 太刀
                    }
                }
                yield "sword";
            }
        };
    }

    /**
     * 命名关键词 → 形状（「我要平底锅就画个平底锅」）。
     * 匹配顺序即优先级：专名/长词在前，避免「菜刀」被「刀」、「巨剑」被「剑」、
     * 「pickaxe」被「axe」截胡；「锤」按需求优先归 mace 而非 hammer。
     * 大小写不敏感；匹配不到返回 null，走原属性特征逻辑。
     */
    private static String shapeFromCustomName(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) return null;
        String text = name.getString();
        if (text.isEmpty()) return null;
        String s = text.toLowerCase(Locale.ROOT);
        if (containsAny(s, "平底锅", "锅", "pan")) return "pan";
        if (containsAny(s, "菜刀", "cleaver")) return "cleaver";
        if (containsAny(s, "镰", "scythe")) return "scythe";
        if (containsAny(s, "弓", "bow")) return "bow";
        if (containsAny(s, "钉锤", "锤", "mace")) return "mace";
        if (containsAny(s, "魔杖", "wand")) return "wand";
        if (containsAny(s, "镐", "pickaxe", "pick")) return "pickaxe";
        if (containsAny(s, "锹", "铲", "shovel")) return "shovel";
        if (containsAny(s, "斧", "axe")) return "axe";
        if (containsAny(s, "枪", "矛", "spear")) return "spear";
        if (containsAny(s, "巨剑", "greatsword")) return "greatsword";
        if (containsAny(s, "匕首", "dagger")) return "dagger";
        if (containsAny(s, "太刀", "katana")) return "katana";
        if (containsAny(s, "刀", "剑", "sword", "blade")) return "sword";
        return null;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    /** 主导效果颜色键：按优先级挑最抢眼的信号。 */
    private static String colorKeyFor(ComposedAttributes attr) {
        if (hasGranted(attr, "minecraft:wither")) return "wither";
        if (attr.igniteLevel() > 0) return "ignite";
        if (attr.lifestealLevel() > 0) return "lifesteal";
        if (attr.effects().poison() > 0 || hasGranted(attr, "minecraft:poison")) return "poison";
        if (attr.effects().frost() > 0) return "frost";
        if (attr.slowLevel() > 0 || "shadow".equals(attr.appearanceKey())) return "shadow";
        if ("arcane".equals(attr.appearanceKey()) || attr.effects().levitation() > 0) return "mana";
        if (attr.healLevel() > 0 || attr.effects().regeneration() > 0) return "holy";
        return "none";
    }

    private static boolean hasGranted(ComposedAttributes attr, String effectId) {
        return attr.grantedEffects().entrySet().stream()
                .anyMatch(e -> effectId.equals(e.getKey().toString()) && e.getValue() != null && e.getValue() > 0);
    }

    /**
     * 稀有度 tier（0 普通 / 1 稀有 / 2 史诗 / 3 传奇）：优先按产物最高效果等级推导——
     * 经典五效果 + 扩展 {@code EffectLevels} + 自由 {@code grantedEffects} 的最高等级，
     * ≥4 传奇、=3 史诗、≥1 稀有；没有任何效果等级时用 {@code powerScore} 兜底映射。
     */
    private static int tierFor(ComposedAttributes attr) {
        if (attr == null) return 0;
        int maxLevel = Math.max(attr.igniteLevel(), Math.max(attr.lifestealLevel(),
                Math.max(attr.thornsLevel(), Math.max(attr.slowLevel(), attr.healLevel()))));
        maxLevel = Math.max(maxLevel, attr.effects().maxLevel());
        for (Integer level : attr.grantedEffects().values()) {
            if (level != null) maxLevel = Math.max(maxLevel, level);
        }
        if (maxLevel >= 4) return 3;
        if (maxLevel == 3) return 2;
        if (maxLevel >= 1) return 1;
        return tierFor(attr.powerScore());
    }

    /** powerScore 兜底档位：≥T3 传奇，≥T2 史诗，≥T1 稀有，否则普通。 */
    private static int tierFor(double powerScore) {
        if (powerScore >= T3) return 3;
        if (powerScore >= T2) return 2;
        if (powerScore >= T1) return 1;
        return 0;
    }

    // ============================ 调色板与形状 ============================

    /** 效果调色板：主色（刃缘）/ 亮色（宝石·符文·光晕）/ 暗色（书面等）。 */
    private record Palette(int main, int bright, int dark) {
    }

    private static final Map<String, Palette> PALETTES = Map.of(
            "ignite", new Palette(abgr(0xE0, 0x5A, 0x1E), abgr(0xFF, 0xB0, 0x60), abgr(0x7A, 0x2E, 0x0E)),
            "poison", new Palette(abgr(0x5A, 0xA0, 0x3C), abgr(0x9B, 0xE0, 0x70), abgr(0x2E, 0x52, 0x20)),
            "frost", new Palette(abgr(0x6F, 0xC0, 0xE8), abgr(0xC8, 0xEC, 0xFF), abgr(0x2E, 0x6A, 0x8A)),
            "lifesteal", new Palette(abgr(0xB0, 0x14, 0x14), abgr(0xFF, 0x5A, 0x5A), abgr(0x5E, 0x08, 0x08)),
            "shadow", new Palette(abgr(0x5A, 0x2A, 0x72), abgr(0x9A, 0x5A, 0xC8), abgr(0x2A, 0x12, 0x38)),
            "holy", new Palette(abgr(0xE8, 0xCE, 0x6E), abgr(0xFF, 0xF6, 0xC0), abgr(0x8A, 0x74, 0x34)),
            "mana", new Palette(abgr(0x9B, 0x59, 0xD0), abgr(0xD8, 0xA8, 0xFF), abgr(0x4E, 0x26, 0x70)),
            "wither", new Palette(abgr(0x3A, 0x3A, 0x42), abgr(0x7A, 0x7A, 0x8A), abgr(0x16, 0x16, 0x1A)),
            "none", new Palette(abgr(0xA8, 0xAE, 0xB6), abgr(0xD8, 0xDE, 0xE4), abgr(0x5A, 0x60, 0x66))
    );

    /** 基底材质色：B 主体 / 把手 / 缠绕。书面（coverFromEffect=true）用主导效果暗色，其余用固定材质色。 */
    private record ShapeBase(int color, int handle, int wrap, boolean coverFromEffect) {
        int body(Palette palette) {
            return coverFromEffect ? palette.dark() : color;
        }

        ShapeBase(int color, int handle, int wrap) {
            this(color, handle, wrap, false);
        }
    }

    private static ShapeBase bookBase() {
        return new ShapeBase(0, abgr(0x6B, 0x4A, 0x2F), abgr(0xE8, 0xE0, 0xC8), true);
    }

    private static final Map<String, ShapeBase> BASES = Map.ofEntries(
            Map.entry("sword", new ShapeBase(abgr(0x9A, 0xA0, 0xA6), abgr(0x6B, 0x4A, 0x2F), abgr(0x3E, 0x2A, 0x1A))),
            Map.entry("greatsword", new ShapeBase(abgr(0x8A, 0x90, 0x96), abgr(0x5A, 0x3E, 0x28), abgr(0x32, 0x22, 0x14))),
            Map.entry("dagger", new ShapeBase(abgr(0xA8, 0xAE, 0xB4), abgr(0x4A, 0x32, 0x20), abgr(0x2E, 0x20, 0x12))),
            Map.entry("katana", new ShapeBase(abgr(0xB8, 0xBE, 0xC4), abgr(0x3A, 0x2A, 0x1E), abgr(0x24, 0x18, 0x10))),
            Map.entry("axe", new ShapeBase(abgr(0x9A, 0xA0, 0xA6), abgr(0x7A, 0x52, 0x30), abgr(0x3E, 0x2A, 0x1A))),
            Map.entry("hammer", new ShapeBase(abgr(0x8E, 0x94, 0x9A), abgr(0x6B, 0x4A, 0x2F), abgr(0x3E, 0x2A, 0x1A))),
            Map.entry("spear", new ShapeBase(abgr(0xA8, 0xAE, 0xB4), abgr(0x6B, 0x4A, 0x2F), abgr(0x4A, 0x36, 0x24))),
            Map.entry("bone", new ShapeBase(abgr(0xD8, 0xD0, 0xBC), abgr(0xA8, 0x9F, 0x8A), abgr(0x6B, 0x4A, 0x2F))),
            Map.entry("staff", new ShapeBase(abgr(0x7A, 0x52, 0x30), abgr(0x5A, 0x3A, 0x22), abgr(0xD8, 0xB2, 0x4A))),
            Map.entry("shield", new ShapeBase(abgr(0x8A, 0x90, 0x98), abgr(0x6B, 0x4A, 0x2F), abgr(0xE8, 0xE0, 0xC8))),
            Map.entry("hoe", new ShapeBase(abgr(0x9A, 0xA0, 0xA6), abgr(0x7A, 0x52, 0x30), abgr(0x3E, 0x2A, 0x1A))),
            Map.entry("book", bookBase()),
            Map.entry("pan", new ShapeBase(abgr(0x56, 0x5A, 0x60), abgr(0x6B, 0x4A, 0x2F), abgr(0x3E, 0x2A, 0x1A))),
            Map.entry("cleaver", new ShapeBase(abgr(0xB4, 0xBA, 0xC0), abgr(0x5A, 0x3E, 0x28), abgr(0x32, 0x22, 0x14))),
            Map.entry("scythe", new ShapeBase(abgr(0xA8, 0xAE, 0xB4), abgr(0x6B, 0x4A, 0x2F), abgr(0x3E, 0x2A, 0x1A))),
            Map.entry("bow", new ShapeBase(abgr(0x8A, 0x62, 0x38), abgr(0x4E, 0x36, 0x20), abgr(0xE8, 0xE0, 0xC8))),
            Map.entry("mace", new ShapeBase(abgr(0x8E, 0x94, 0x9A), abgr(0x6B, 0x4A, 0x2F), abgr(0x3E, 0x2A, 0x1A))),
            Map.entry("wand", new ShapeBase(abgr(0x5A, 0x3A, 0x22), abgr(0x3E, 0x2A, 0x1A), abgr(0xD8, 0xB2, 0x4A))),
            Map.entry("pickaxe", new ShapeBase(abgr(0x9A, 0xA0, 0xA6), abgr(0x7A, 0x52, 0x30), abgr(0x3E, 0x2A, 0x1A))),
            Map.entry("shovel", new ShapeBase(abgr(0x9A, 0xA0, 0xA6), abgr(0x7A, 0x52, 0x30), abgr(0x3E, 0x2A, 0x1A)))
    );

    /**
     * 形状模板：SIZE 行 × SIZE 字符（当前 32×32 手绘）。'.' 空；
     * 新词表见 {@link #roleColor}（o 描边 / D·B·L 刃体三档 / e·E·X 刃缘三档 /
     * G 宝石 / R 符文 / T·t 金 / H·h 柄 / W 缠绕），旧 16×16 符号（b 等）仍兼容。
     * 行长度在类加载时校验补齐，永不因模板笔误崩渲染。
     * <p>尚未手绘重制的旧 16×16 模板（bone/pan/cleaver/bow/wand/pickaxe/shovel）
     * 由 {@link #upscale16to32} 最近邻 ×2 放大兜底，视觉等同旧版，留待后续逐张重绘。</p>
     */
    private static String[] template(String shape) {
        String[] t = switch (shape) {
            case "greatsword" -> GREATSWORD;
            case "dagger" -> DAGGER;
            case "katana" -> KATANA;
            case "axe" -> AXE;
            case "hammer" -> HAMMER;
            case "spear" -> SPEAR;
            case "bone" -> BONE;
            case "staff" -> STAFF;
            case "shield" -> SHIELD;
            case "hoe" -> HOE;
            case "book" -> BOOK;
            case "pan" -> PAN;
            case "cleaver" -> CLEAVER;
            case "scythe" -> SCYTHE;
            case "bow" -> BOW;
            case "mace" -> MACE;
            case "wand" -> WAND;
            case "pickaxe" -> PICKAXE;
            case "shovel" -> SHOVEL;
            default -> SWORD;
        };
        return t.length == SIZE ? t : upscale16to32(t);
    }

    /** 旧 16×16 模板最近邻 ×2 放大兜底：每像素复制成 2×2，观感与旧版一致（待逐张手绘重制）。 */
    private static String[] upscale16to32(String[] t) {
        String[] out = new String[t.length * 2];
        for (int y = 0; y < t.length; y++) {
            StringBuilder row = new StringBuilder(t[y].length() * 2);
            for (int x = 0; x < t[y].length(); x++) {
                row.append(t[y].charAt(x)).append(t[y].charAt(x));
            }
            out[y * 2] = out[y * 2 + 1] = row.toString();
        }
        return out;
    }

    private static final String[] SWORD = {
            "................................",
            "................................",
            "............................ooo.",
            "..........................oooXo.",
            ".........................ooEEoo.",
            "........................ooEEDo..",
            ".......................ooEEDoo..",
            ".....................oooEBDoo...",
            "....................ooEEBDoo....",
            "...................ooEELDoo.....",
            "..................ooEELDDo......",
            ".................ooELLDDoo......",
            "...............oooELLLDoo.......",
            "..............ooEELLLDoo........",
            ".............ooEELLLDoo.........",
            "............ooEELLLDoo..........",
            "...........ooEELLLDDo...........",
            "......ooooooEELLLDDoo...........",
            "......ottooEELLLDDoo............",
            "......otTtoELLLDDoo.............",
            "......ooTTtoLLDDoo..............",
            ".......ooTTtoDDoo...............",
            "......ooHHTTtooo................",
            ".....ooHHHhTTtoo................",
            "....ooWWHhhoTTto................",
            "...ooHHWWhoootto................",
            "..ootHHhWoo.oooo................",
            ".oottthhoo......................",
            ".ottTttoo.......................",
            ".ootttoo........................",
            "..ootoo.........................",
            "...ooo.........................."
    };

    private static final String[] GREATSWORD = {
            "................................",
            "............................oooo",
            "...........................ooXXo",
            "..........................ooXEXo",
            "........................oooEEDoo",
            ".......................ooEEBDoo.",
            "......................ooEELDoo..",
            "....................oooEELDDo...",
            "...................ooEELLDDoo...",
            "..................ooEELLLDoo....",
            ".................ooEELLLDoo.....",
            "...............oooEBLLLDDo......",
            "..............ooEEBLLLDDoo......",
            ".............ooEEBLLLBDoo.......",
            "............ooEEBLLLBDoo........",
            "......ooo..ooEEBLLLBDoo.........",
            ".....ootooooEEBLLLBDDo..........",
            "....ootttooEBBLLLBDDoo..........",
            "....ottTttoBBLLLBDDoo...........",
            "....ooTTTttoLLLBDDoo............",
            ".....ooTTTttoLBBDoo.............",
            "......ooTTTttoBDoo..............",
            "......ooHTTTttooo...............",
            ".....ooHHHTTTttoo...............",
            "....ooWWHhhTTTttoo..............",
            "...ooHHWWhooTTTtto..............",
            "..ooHHHhWooooTttoo..............",
            ".ootWHhhoo..ootoo...............",
            "ootGtWhoo....ooo................",
            "otGGGtoo........................",
            "ootGtoo.........................",
            ".ootoo.........................."
    };

    private static final String[] DAGGER = {
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
            ".......................ooo......",
            ".....................oooEo......",
            "....................ooEEoo......",
            "...................ooEEDo.......",
            ".................oooEEDoo.......",
            "................ooEEBDoo........",
            "...............ooEEBDoo.........",
            "..............ooEELDDo..........",
            ".............ooEELDDoo..........",
            "............ooELLDDoo...........",
            ".........ooooELLLDoo............",
            "........ootoELLLDoo.............",
            "........otttoLLDoo..............",
            "........ooTTtoDoo...............",
            "........ooHTTtoo................",
            ".......ooHHhTtto................",
            "......ooWHhootoo................",
            "....oooHWWooooo.................",
            "....ottthoo.....................",
            "....otTtoo......................",
            "....ottto.......................",
            "....ooooo.......................",
            "................................",
            "................................",
            "................................"
    };

    private static final String[] KATANA = {
            "................................",
            "................................",
            "..........................ooo...",
            ".........................ooXo...",
            ".........................oXEo...",
            "........................ooEoo...",
            ".......................ooEDo....",
            "......................ooEDoo....",
            ".....................ooEEDo.....",
            "....................ooEEDoo.....",
            "...................ooEEDoo......",
            "..................ooEEDoo.......",
            ".................ooEEDDo........",
            "................ooEEDDoo........",
            "...............ooEEBDoo.........",
            "..............ooEEBDoo..........",
            "............oooELLDoo...........",
            "...........ooEELLDoo............",
            "..........ooEELDDoo.............",
            "........oooELLDDoo..............",
            "........ottLLDDoo...............",
            ".......ootTTDDoo................",
            "......ooHtTTtoo.................",
            ".....ooWHtttto..................",
            "....ooHWWhhooo..................",
            "...ooHHHWWoo....................",
            "...oWWHhhoo.....................",
            "...ooWWhoo......................",
            "....ooWoo.......................",
            ".....ooo........................",
            "................................",
            "................................"
    };

    private static final String[] AXE = {
            "................................",
            "................................",
            "................................",
            "........................oooo....",
            "...............ooooooooooBEoo...",
            "...............oBBBBBBBBLEEEoo..",
            "...............oBLLLLLLLLBEEXoo.",
            "...............oBLBBBBBBBBBEEXo.",
            "...............ooBBBBBBBBBBEEEoo",
            "................oBBBBBBBBBBBEEEo",
            "................oBTTBBBBBBBBBEEo",
            "................oTTTTBBBBBBBBEEo",
            "................oTTTTBBBBBBBEEoo",
            "...............oohTTBBBBBBBBEEo.",
            "..............ooWHBBBBBBBBBDEEo.",
            ".............oohWWBBBBBBDDDDBBo.",
            "............oohHHWooBDDDDBBBooo.",
            "...........ooWhHHooooDBBBBooo...",
            "..........oohWWHoo..oooBooo.....",
            ".........oohhHHWo.....ooo.......",
            ".........oWWHHHoo...............",
            "........oohWWHoo................",
            ".......oohHHWoo.................",
            "......ooWHHHoo..................",
            ".....oohWWHoo...................",
            "....oohhHWoo....................",
            "....oWhHHoo.....................",
            "....oWWWHo......................",
            "....ooHWoo......................",
            ".....oooo.......................",
            "................................",
            "................................"
    };

    private static final String[] HAMMER = {
            "................ooEooo..........",
            "...............ooEEEBoo.........",
            "..............ooEEELEEoo........",
            ".............ooEEELLLBBoo.......",
            ".............oEEELLLBBBBoo......",
            ".............ooELLLBBBBBBoo.....",
            "..............oLLLBBBTTBBBoo....",
            "..............ooLBBBTTTTBBBoo...",
            "...............ooBBBTTTTBBBEoo..",
            "................ooBBBTTBBBDEEo..",
            ".................ooBBBBBBDDDEoo.",
            "................ooooBBBBDDDEEEo.",
            "...............oohoooBBDDDEEEoo.",
            "...............ohhHHooDDDEEEoo..",
            "..............ooWHHHoooDEEEoo...",
            ".............oohWWHoo.oooEoo....",
            "............oohHHWoo....ooo.....",
            "...........ooWHHHoo.............",
            "..........oohWWHoo..............",
            ".........oohhHHoo...............",
            ".........oWWHHHo................",
            "........oohWWHoo................",
            ".......oohHHWoo.................",
            "......ooWHHHoo..................",
            ".....oohWWHoo...................",
            "....oohhHWoo....................",
            "....oWhHHoo.....................",
            "....oWWWHo......................",
            "....ooHWoo......................",
            ".....oooo.......................",
            "................................",
            "................................"
    };

    private static final String[] SPEAR = {
            "................................",
            "............................oooo",
            "..........................oooEEo",
            ".........................ooEEEEo",
            ".......................oooEELEoo",
            "......................ooEELLEEo.",
            "......................oEELLLEoo.",
            "....................oooELLLEoo..",
            "....................ottoLLEEo...",
            "....................otTtoEEoo...",
            "...................oohTTtooo....",
            "..................ooWWHtto......",
            ".................oohHWoooo......",
            "................oohHHoo.........",
            "...............oohHHoo..........",
            "..............ooWWHoo...........",
            ".............oohHWoo............",
            "............oohHHoo.............",
            "...........oohHHoo..............",
            "..........ooWHHoo...............",
            ".........oohWWoo................",
            "........oohHHoo.................",
            ".......oohHHoo..................",
            "......ooWHHoo...................",
            ".....oohWWoo....................",
            "....oohHHoo.....................",
            "...oohHHoo......................",
            "..oohHHoo.......................",
            "..oWWHoo........................",
            "..ooWoo.........................",
            "...ooo..........................",
            "................................"
    };

    private static final String[] BONE = {
            ".............EE.",
            "............EBB.",
            "...........EBb..",
            "..........EBBb..",
            ".........EBb....",
            "........EBBB....",
            ".......EBb......",
            "......EBBb......",
            ".....EBb........",
            "....EBBB........",
            "...EBb..........",
            "..BBBB..........",
            "..HH............",
            ".WH.............",
            ".WW.............",
            "................"
    };

    private static final String[] STAFF = {
            "...................ooeoo........",
            "..................ooeXeooo......",
            "................oooeXeeeeoo.....",
            "...............ooeeeeGoeeeo.....",
            "...............oeeeLLGGoeeo.....",
            "...............oeeGLGGGGeeo.....",
            "...............oeeGGeGeGeeo.....",
            "..............ooeooGeGeoeeo.....",
            "..............oeeTTeGGoeeeo.....",
            "..............oeeTTTToooeeo.....",
            "..............oeeTTTTo.oeeo.....",
            "..............ooTTTTTo.oooo.....",
            "...............ooTTToo..........",
            "...............oohHHo...........",
            "...............ohHHHo...........",
            "..............ooWHHoo...........",
            "..............ohRRRo............",
            ".............oohRRoo............",
            ".............ohRRRo.............",
            "............oohHHoo.............",
            "............ohhHHo..............",
            "...........ooRHHoo..............",
            "...........oRRRWo...............",
            "...........ohRRRo...............",
            "..........oohHRoo...............",
            "..........ohHHoo................",
            ".........oohHHo.................",
            ".........ohHHHo.................",
            ".........oWWHoo.................",
            ".........ohWWo..................",
            ".........ooooo..................",
            "................................"
    };

    private static final String[] SHIELD = {
            "................................",
            "................................",
            "................................",
            ".....oooooooooooooooooooooo.....",
            ".....oTTTTTTTTTTTTTTtttttto.....",
            ".....oTBBBBBBBBBBBBBBBBBtto.....",
            ".....oTBBBBBBBBBBBBBBBBBtto.....",
            ".....oTBBLLLLLLBBBBBBBBBtto.....",
            ".....oTBBLLLLLGGGBBBBBBBtto.....",
            ".....oTBBLLLLGGGGGBBBBBBtto.....",
            ".....oTBBLLLGGGGGGGBBBBBtto.....",
            ".....oTBBLLLGGGGGGGBBBBBtto.....",
            ".....oTBBLLLGGGGGGGBBBBBtto.....",
            ".....oTTBLLLLGGGGGBBBDBBtto.....",
            ".....ooTBBBBBBGGGBBBDDBttoo.....",
            "......oTTBBBBBBBBBBDDDBtto......",
            "......ootBBBBBBBBBDDDDttoo......",
            ".......ottBBBBBBBBDDDDtto.......",
            ".......ootBBBBBBBBDDDttoo.......",
            "........ottBBBBBBDDDBtto........",
            "........oottBBBBBDDBttoo........",
            ".........ottBBBBBDBttto.........",
            ".........oottBBBBBtttoo.........",
            "..........otttBBBBttoo..........",
            "..........oottBBBttoo...........",
            "...........oottBttto............",
            "............oottttoo............",
            ".............otttoo.............",
            ".............ootoo..............",
            "..............ooo...............",
            "................................",
            "................................"
    };

    private static final String[] HOE = {
            "................................",
            "................................",
            "................................",
            ".........................ooo....",
            "........................ooBoo...",
            ".......................ooBBBoo..",
            "......................ooLBBBBooo",
            ".....................ooLLLBXBEEo",
            "....................ooLLLBBBXEBo",
            "...................ooLLLBBBBEEoo",
            "..................ooBLLBBBBEEBo.",
            ".................ooBLLLBBBBEEoo.",
            "...............oooBLLLBBBBEEoo..",
            "..............ootBLLLBBBBBEEo...",
            "..............otTTBLBBBBBEEoo...",
            ".............ootTTBBBBBBBEEo....",
            "............oohWttoBBDBBEEoo....",
            "...........oohHHWooBDDDBEEo.....",
            "..........ooWHHHoooooDBEEoo.....",
            ".........oohWWWoo...oooBoo......",
            "........oohhHHoo......ooo.......",
            "........oWWHHoo.................",
            ".......oohWWHo..................",
            "......oohHHWoo..................",
            ".....ooWHHHoo...................",
            "....oohWWHoo....................",
            "...oohhHWoo.....................",
            "...oWhHHoo......................",
            "...oWWWoo.......................",
            "...ooHWo........................",
            "....oooo........................",
            "................................"
    };

    private static final String[] BOOK = {
            "................................",
            "................................",
            "....oooooooooooooooooooooo......",
            "....oBBBBBBBBBBBBBBBBBBBBo......",
            "....oBBBBBBBBBBBBBBBBBBBWoooo...",
            "....oBTTTTTTTTTTTTTTTTttWWWWo...",
            "....oBTTTTTTTTTTTTTTTTttWWWWo...",
            "....oBTTLLLLLGGGBBBBBBttWWWWo...",
            "....oBTTLLLLGGGGGBBBBBttWWWWo...",
            "....oBTTBBBBGGGGGBBBBBttWWWWo...",
            "....oBTTBBBBGGGGGBBBBBttWWWWo...",
            "....oBTTBBBBBGGGBBBBBBttWWWWo...",
            "....oBTTBBBBBBBBBBBBBBttWWWWo...",
            "....oBTTBBRRRRRRRRRRBBttWWWWo...",
            "....oBTTBBRRRRRRRRRRBBttWWWWo...",
            "....oBTTBBBBBBBBBBBBBBttWWWWo...",
            "....oBTTBBBBBBBBBBBBBBttWWWWo...",
            "....oBTTBBRRRRRRRRRRBBttWWWWo...",
            "....oBTTBBRRRRRRRRRRBBttWWWWo...",
            "....oBTTBBBBBBBBBBBBBBttWWWWo...",
            "....oBTTBBBBBBBBBBBBBBttWWWWo...",
            "....oBTTBBBBBBBBBBBBBBttWWWWo...",
            "....oBTTBDDDDDDDDDDDDDttWWWWo...",
            "....oBTTBDDDDDDDDDDDDDttWWWWo...",
            "....oBttttttttttttttttttWWWWo...",
            "....oBtttttttttttttttttBWWWWo...",
            "....oBWWWWWWWWWWWWWWWWWWWWWWo...",
            "....oooWWWWWWWWWWWWWWWWWWWWWo...",
            "......ooWWWWWWWWWWWWWWWWWWWWo...",
            ".......oooooooooooooooooooooo...",
            "................................",
            "................................"
    };

    private static final String[] PAN = {
            ".....EEEE.......",
            "...BBBBBBBBBB...",
            "..BBBBBBBBBBBB..",
            "..BbbbbbbbbbbB..",
            "...bbbbbbbbbb...",
            ".....bbbbbb.....",
            ".....BB.........",
            "....HH..........",
            "...HH...........",
            "..HH............",
            "..WH............",
            ".WH.............",
            ".WW.............",
            "................",
            "................",
            "................"
    };

    private static final String[] CLEAVER = {
            "..........BBBBBB",
            ".........EBBBBBb",
            "........EBBBBBb.",
            ".......EBBBBBb..",
            "......EBBBBBb...",
            ".....EBBBBBb....",
            "....EBBBBBb.....",
            "...EBBBBBb......",
            "..EBBBBBb.......",
            "..HH............",
            ".WH.............",
            ".WW.............",
            "................",
            "................",
            "................",
            "................"
    };

    private static final String[] SCYTHE = {
            "................................",
            "................................",
            "................................",
            "................................",
            "................oooo............",
            "...............oottooo..........",
            "...............oDDBBEo..........",
            "...............otDBBEo..........",
            "..............oohDDBEoo.........",
            "..............ohHHDBEEo.........",
            ".............oohHHDDBEoo........",
            ".............oWWHHoDDEEoo.......",
            "............oohWWoooDDEEooo.....",
            "............ohhHHo.ooDDEEEoooo..",
            "...........oohHHoo..oooDDEEXXo..",
            "...........ohhHHo.....oooooooo..",
            "...........oWHHoo...............",
            "..........ooWWWo................",
            "..........ohHHoo................",
            ".........oohHHo.................",
            ".........ohHHHo.................",
            "........oohHHoo.................",
            "........oWWWHo..................",
            ".......oohWWoo..................",
            ".......ohhHHo...................",
            "......oohHHoo...................",
            "......ohhHHo....................",
            "......oWHHoo....................",
            "......oWWWo.....................",
            "......ooHoo.....................",
            ".......ooo......................",
            "................................"
    };

    private static final String[] BOW = {
            "............E...",
            "...........EB.W.",
            "..........BB..W.",
            ".........BB...W.",
            "........BB....W.",
            ".......BB.....W.",
            "......BB......W.",
            ".....BB.......W.",
            ".....HH.......W.",
            "......BB......W.",
            ".......BB.....W.",
            "........BB....W.",
            ".........BB...W.",
            "..........BB..W.",
            "...........BE.W.",
            "............E..."
    };

    private static final String[] MACE = {
            "..................ooEEoo........",
            "...............ooooEEXEooooo....",
            "..............ooGoBEEEEBoGGoo...",
            "..............oGGGLLEEBBGGGGo...",
            "..............oGGGLLLBBBGGGGo...",
            "............oooLGLLLLLBBBGGooo..",
            "...........ooEEELLLLLLBBBBEEEoo.",
            "...........oEEEEELLLLLBBBEEEEEo.",
            "...........oEEEEELLLLBBDDEEEEEo.",
            "...........oEEEEELLLBBDDDEEEEEo.",
            "...........ooEEEBBBBBDDDDDEEEoo.",
            "............oooBGBBBBDDDDGGDoo..",
            "..............oGGGBBBDDDGGGGo...",
            "..............oGGGBBEEDDGGGGo...",
            ".............ootGTBEEEEDDGGoo...",
            ".............ohtTTtEEEEooooo....",
            "............oohttttoEEoo........",
            "...........ooWHHHoooooo.........",
            "..........oohWWWoo..............",
            ".........oohhHHoo...............",
            "........ooWWHHoo................",
            "........ohhWWHo.................",
            ".......oohHHWoo.................",
            "......ooWHHHoo..................",
            ".....oohWWHoo...................",
            "....oohHHWoo....................",
            "....oWhHHoo.....................",
            "....oWWWHo......................",
            "....ooHWoo......................",
            ".....oooo.......................",
            "................................",
            "................................"
    };

    private static final String[] WAND = {
            "............GG..",
            "...........GEEG.",
            "...........GEEG.",
            "............GG..",
            "...........TT...",
            "..........BB....",
            ".........BB.....",
            "........RB......",
            ".......BB.......",
            "......BB........",
            ".....RB.........",
            "....BB..........",
            "...BB...........",
            "..BB............",
            ".WB.............",
            "WW.............."
    };

    private static final String[] PICKAXE = {
            "......BBBB......",
            "....EBBBBBBBE...",
            "..EB...BB...BE..",
            ".EB....BB....BE.",
            ".EE....BB....EE.",
            ".......HB.......",
            "......HB........",
            "......HB........",
            ".....HB.........",
            ".....HB.........",
            "....HB..........",
            "....HB..........",
            "...WH...........",
            "...WH...........",
            "..WW............",
            "................"
    };

    private static final String[] SHOVEL = {
            ".........BBBBB..",
            "........EBBBBBb.",
            "........BBBBBBB.",
            "........BBBBBBB.",
            ".........BBBBB..",
            ".........BBB....",
            "........BB......",
            ".......HB.......",
            "......HB........",
            ".....HB.........",
            "....HB..........",
            "...HB...........",
            "..HB............",
            ".WB.............",
            "WW..............",
            "................"
    };

    static {
        // 模板自检：行数/行长不齐时补 '.'，保证渲染路径永不抛异常。
        normalize(SWORD);
        normalize(GREATSWORD);
        normalize(DAGGER);
        normalize(KATANA);
        normalize(AXE);
        normalize(HAMMER);
        normalize(SPEAR);
        normalize(BONE);
        normalize(STAFF);
        normalize(SHIELD);
        normalize(HOE);
        normalize(BOOK);
        normalize(PAN);
        normalize(CLEAVER);
        normalize(SCYTHE);
        normalize(BOW);
        normalize(MACE);
        normalize(WAND);
        normalize(PICKAXE);
        normalize(SHOVEL);
    }

    private static void normalize(String[] template) {
        for (int y = 0; y < template.length; y++) {
            String row = template[y];
            if (row.length() == SIZE) continue;
            if (row.length() > SIZE) {
                template[y] = row.substring(0, SIZE);
            } else {
                template[y] = row + ".".repeat(SIZE - row.length());
            }
        }
    }
}
