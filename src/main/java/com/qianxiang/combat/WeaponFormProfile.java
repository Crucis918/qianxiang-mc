package com.qianxiang.combat;

import com.qianxiang.QianxiangItems;
import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.WeaponForm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 武器形态事实源：{@link WeaponForm} →（纹理形状 shape / EF 动作底座 efCategory /
 * 默认连段 defaultCombos / 判定盒 collider）映射表 + 锻造时形态推导。
 * <p>
 * 「填充顺序即布局」：投料从中心向外（{@code SLOT_FILL_ORDER}，第一个料=核心），
 * compose 输入 list 下标=槽位号，形态按布局一次推导写入组件，下游统一按形走。
 * </p>
 * <h3>推导评分规则（确定性，逐项累加后取最高）</h3>
 * <ul>
 *   <li>规模：非空零件 ≤4 → DAGGER+2（小型）；外圈槽占用 ≥8 → GREATSWORD+3、HAMMER+2（重型）。</li>
 *   <li>算子：EDGE≥2 且攻速加成 ≥0.8 → DAGGER+2、KATANA+2（否则 KATANA+1）；
 *       STRENGTH 或攻击 ≥6.0 → AXE+2、HAMMER+2；LEVITATION/SLOW → SPEAR+2、SCYTHE+1；
 *       POISON/FROST → SCYTHE+2、KATANA+1；IGNITE → SWORD+2、AXE+1；BASE_BONE → KATANA+1。</li>
 *   <li>SWORD 恒 +1（标准刃偏置）。并列按 PRIORITY 序取先；
 *       最高 ≤1（无规则命中）回退旧属性阈值（见 FALLBACK_* 常量）。</li>
 *   <li>仅武器类产物（ember_blade/bone_blade）推导；法杖/书=物品固有形态（STAFF/BOOK，
 *       非推导）；盾/工具/护甲不写形态（空串）。</li>
 * </ul>
 */
public final class WeaponFormProfile {

    private WeaponFormProfile() {}

    // ===================== 回退阈值（旧 shapeFor/classify 两处重复，集中到这里） =====================
    /** 重型回退：攻击 ≥ 此值且攻速 ≤ FALLBACK_HEAVY_MAX_SPEED → 巨剑。 */
    public static final double FALLBACK_HEAVY_DAMAGE = 6.0;
    public static final double FALLBACK_HEAVY_MAX_SPEED = 0.6;
    /** 轻型回退：攻速加成 ≥ 此值 → 匕首。 */
    public static final double LIGHT_MIN_SPEED = 0.8;
    /** 中型回退：攻击 ≥ 此值 → 太刀。 */
    public static final double FALLBACK_KATANA_DAMAGE = 4.5;

    /** 一条形态映射：纹理形状 / EF 底座 / 默认连段（AnimationLibrary 短路径）/ 判定盒。 */
    public record Profile(String shape, String efCategory, List<String> defaultCombos, String collider) {}

    private static final Map<WeaponForm, Profile> PROFILES = new EnumMap<>(WeaponForm.class);
    static {
        put(WeaponForm.SWORD, "sword", "sword",
                List.of("sword_auto1", "sword_auto2", "sword_auto3"), "sword");
        put(WeaponForm.GREATSWORD, "greatsword", "greatsword",
                List.of("greatsword_auto1", "greatsword_auto2", "greatsword_dash"), "greatsword");
        put(WeaponForm.DAGGER, "dagger", "dagger",
                List.of("dagger_auto1", "dagger_auto2", "dagger_auto3", "dagger_dash"),
                "custom:2:0.4,0.4,0.6:0,0,-0.1");
        put(WeaponForm.KATANA, "katana", "tachi",
                List.of("tachi_auto1", "tachi_auto2", "tachi_auto3", "tachi_dash"), "tachi");
        put(WeaponForm.SPEAR, "spear", "spear",
                List.of("trident_auto1", "trident_auto2", "trident_auto3", "spear_dash"),
                "custom:2:0.4,0.5,1.2:0,0,-0.4");
        put(WeaponForm.AXE, "axe", "axe",
                List.of("axe_auto1", "axe_auto2", "axe_dash"), "axe");
        put(WeaponForm.HAMMER, "hammer", "greatsword",
                List.of("greatsword_auto1", "greatsword_auto2", "greatsword_airslash"),
                "custom:1:0.9,0.7,0.6:0,0,-0.1");
        put(WeaponForm.SCYTHE, "scythe", "spear",
                List.of("uchigatana_auto1", "uchigatana_auto2", "uchigatana_auto3", "uchigatana_dash"),
                "custom:3:1.2,0.5,0.8:0,0,-0.2");
        put(WeaponForm.MACE, "mace", "axe",
                List.of("fist_auto1", "axe_auto1", "fist_auto2", "axe_auto2"),
                "custom:1:0.6,0.5,0.5:0,0,-0.1");
        put(WeaponForm.STAFF, "staff", "spear",
                List.of("trident_auto1", "trident_auto2", "trident_auto3", "spear_dash"),
                "custom:2:0.4,0.4,1.0:0,0,-0.3");
        put(WeaponForm.BOOK, "book", "fist",
                List.of("fist_auto1", "fist_auto2", "fist_auto3", "fist_dash"),
                "custom:1:0.5,0.4,0.4:0,0,-0.05");
    }

    private static void put(WeaponForm form, String shape, String efCategory,
                            List<String> combos, String collider) {
        PROFILES.put(form, new Profile(shape, efCategory, combos, collider));
    }

    /** 按形态取映射；未知/无形态返回 null（调用方走回退路径）。 */
    public static Profile of(WeaponForm form) {
        return form == null ? null : PROFILES.get(form);
    }

    /** 按字符串 id 取映射（组件里存的是 id）。 */
    public static Profile of(String formId) {
        return of(WeaponForm.byId(formId));
    }

    public static Map<WeaponForm, Profile> all() {
        return java.util.Collections.unmodifiableMap(PROFILES);
    }

    // ===================== 手持 3D 剖面参数（代码生成几何，零新资产） =====================

    /**
     * 形态 3D 剖面参数：手持（第一/第三人称）上下文的参数化几何挤出配置。
     * <p>
     * 厚度单位为像素，z 以 8 为中心向两侧挤出；像素按角色分区——
     * B/b/E/G/T/R（刃体/护手）用 {@link #bladeThickness}，H/W（柄/缠绕）用
     * {@link #handleThickness}。GUI/GROUND/FIXED 仍走 2D 片，不消费本配置。
     * </p>
     */
    public record ExtrusionProfile(float bladeThickness, float handleThickness) {}

    /** 未配置形状的默认剖面（标准薄片 1px，与旧挤出观感一致）。 */
    private static final ExtrusionProfile DEFAULT_EXTRUSION = new ExtrusionProfile(1.0f, 1.0f);

    private static final Map<String, ExtrusionProfile> EXTRUSIONS = Map.ofEntries(
            Map.entry("sword", new ExtrusionProfile(1.5f, 1.0f)),
            Map.entry("greatsword", new ExtrusionProfile(2.5f, 1.0f)),  // 厚刃
            Map.entry("dagger", new ExtrusionProfile(0.75f, 0.75f)),    // 薄片
            Map.entry("katana", new ExtrusionProfile(1.5f, 1.0f)),
            Map.entry("spear", new ExtrusionProfile(1.0f, 1.0f)),       // 细长杆（柄部收窄）
            Map.entry("axe", new ExtrusionProfile(2.5f, 1.0f)),         // 头部加厚
            Map.entry("hammer", new ExtrusionProfile(2.5f, 1.0f)),      // 头部加厚
            Map.entry("scythe", new ExtrusionProfile(1.0f, 1.5f)),      // 刃 1px 杆 1.5px
            Map.entry("mace", new ExtrusionProfile(2.5f, 1.0f)),        // 球头加厚
            Map.entry("staff", new ExtrusionProfile(1.5f, 1.0f)),       // 细长杆（头 1.5 杆 1.0）
            Map.entry("book", new ExtrusionProfile(2.5f, 2.5f))         // 厚书（整本均厚）
    );

    /** 按纹理形状名取剖面；未配置（bone/shield/hoe/pan 等）回退默认薄片。 */
    public static ExtrusionProfile extrusionFor(String shape) {
        return EXTRUSIONS.getOrDefault(shape == null ? "" : shape, DEFAULT_EXTRUSION);
    }

    /** 默认剖面（测试断言用）。 */
    public static ExtrusionProfile defaultExtrusion() {
        return DEFAULT_EXTRUSION;
    }

    /** 评分并列时的取用顺序（重型/风格化优先，标准刃垫底）。 */
    private static final WeaponForm[] PRIORITY = {
            WeaponForm.GREATSWORD, WeaponForm.HAMMER, WeaponForm.AXE, WeaponForm.SPEAR,
            WeaponForm.SCYTHE, WeaponForm.KATANA, WeaponForm.DAGGER, WeaponForm.MACE,
            WeaponForm.SWORD
    };

    /** 外圈槽位下标（5×5 网格的最外环 16 格；布局「外圈≥8 占用=重型」的依据）。 */
    private static final int[] OUTER_SLOTS = {
            0, 1, 2, 3, 4, 5, 9, 10, 14, 15, 19, 20, 21, 22, 23, 24
    };

    /**
     * 锻造时形态推导（规则见类文档）。
     *
     * @param materialStacks 材料槽内容（list 下标=槽位号）
     * @param union          材料功能算子并集
     * @param attr           组合属性（攻速/攻击读数）
     * @param result         产物物品（仅 ember_blade/bone_blade 推导；staff/book 固有；其余空串）
     * @return 形态 id（{@link WeaponForm#id()}），无形态返回空串
     */
    public static String deriveForm(List<ItemStack> materialStacks, Set<PhaseFunction> union,
                                    ComposedAttributes attr, Item result) {
        if (result == QianxiangItems.PHASE_STAFF.get()) return WeaponForm.STAFF.id();
        if (result == QianxiangItems.SPELL_BOOK.get()) return WeaponForm.BOOK.id();
        if (result != QianxiangItems.EMBER_BLADE.get() && result != QianxiangItems.BONE_BLADE.get()) {
            return ""; // 盾/工具/护甲不写形态（注释：非武器载体，形态无意义）
        }

        EnumMap<WeaponForm, Integer> scores = new EnumMap<>(WeaponForm.class);
        int occupied = 0;
        int outerOccupied = 0;
        for (int i = 0; i < materialStacks.size(); i++) {
            ItemStack s = materialStacks.get(i);
            if (s == null || s.isEmpty()) continue;
            occupied++;
        }
        for (int slot : OUTER_SLOTS) {
            if (slot < materialStacks.size() && !materialStacks.get(slot).isEmpty()) {
                outerOccupied++;
            }
        }
        int edgeCount = 0;
        for (ItemStack s : materialStacks) {
            if (s == null || s.isEmpty()) continue;
            if (com.qianxiang.phase.PhaseFunctionResolver.get(s).contains(PhaseFunction.EDGE)) {
                edgeCount++;
            }
        }

        // 规模
        if (occupied <= 4) add(scores, WeaponForm.DAGGER, 2);
        if (outerOccupied >= 8) {
            add(scores, WeaponForm.GREATSWORD, 3);
            add(scores, WeaponForm.HAMMER, 2);
        }
        // 算子加权
        if (edgeCount >= 2) {
            if (attr.attackSpeed() >= LIGHT_MIN_SPEED) {
                add(scores, WeaponForm.DAGGER, 2);
                add(scores, WeaponForm.KATANA, 2);
            } else {
                add(scores, WeaponForm.KATANA, 1);
            }
        }
        if (union.contains(PhaseFunction.STRENGTH) || attr.attackDamage() >= FALLBACK_HEAVY_DAMAGE) {
            add(scores, WeaponForm.AXE, 2);
            add(scores, WeaponForm.HAMMER, 2);
        }
        if (union.contains(PhaseFunction.LEVITATION) || union.contains(PhaseFunction.SLOW)) {
            add(scores, WeaponForm.SPEAR, 2);
            add(scores, WeaponForm.SCYTHE, 1);
        }
        if (union.contains(PhaseFunction.POISON) || union.contains(PhaseFunction.FROST)) {
            add(scores, WeaponForm.SCYTHE, 2);
            add(scores, WeaponForm.KATANA, 1);
        }
        if (union.contains(PhaseFunction.IGNITE)) {
            add(scores, WeaponForm.SWORD, 2);
            add(scores, WeaponForm.AXE, 1);
        }
        if (union.contains(PhaseFunction.BASE_BONE)) {
            add(scores, WeaponForm.KATANA, 1);
        }
        add(scores, WeaponForm.SWORD, 1); // 标准刃偏置

        int max = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max <= 1) {
            // 无规则命中：回退旧属性阈值（与 shapeFor/classify 的旧逻辑同口径）
            return fallbackByAttributes(attr).id();
        }
        for (WeaponForm form : PRIORITY) {
            if (scores.getOrDefault(form, 0) == max) {
                return form.id();
            }
        }
        return WeaponForm.SWORD.id();
    }

    /**
     * 核心材料基底族推导（「填充顺序即布局」：中心槽 12 为核心，空则按填充序第一个非空）。
     *
     * @return metal/bone/wood/hide 之一；核心料无 BASE_* 族或槽全空返回空串（渲染回退写死色）
     */
    public static String deriveBaseFamily(List<ItemStack> materialStacks) {
        int coreSlot = -1;
        for (int slot : com.qianxiang.menu.ForgeTableMenu.SLOT_FILL_ORDER) {
            if (slot < materialStacks.size() && !materialStacks.get(slot).isEmpty()) {
                coreSlot = slot;
                break;
            }
        }
        if (coreSlot < 0) return "";
        Set<PhaseFunction> functions =
                com.qianxiang.phase.PhaseFunctionResolver.get(materialStacks.get(coreSlot));
        if (functions.contains(PhaseFunction.BASE_METAL)) return "metal";
        if (functions.contains(PhaseFunction.BASE_BONE)) return "bone";
        if (functions.contains(PhaseFunction.BASE_WOOD)) return "wood";
        if (functions.contains(PhaseFunction.BASE_HIDE)) return "hide";
        return "";
    }

    /** 旧属性阈值回退（无规则命中时）：重→巨剑、快→匕首、中→太刀、默认标准剑。 */
    public static WeaponForm fallbackByAttributes(ComposedAttributes attr) {
        if (attr != null) {
            double dmg = attr.attackDamage();
            double spd = attr.attackSpeed();
            if (dmg >= FALLBACK_HEAVY_DAMAGE && spd <= FALLBACK_HEAVY_MAX_SPEED) {
                return WeaponForm.GREATSWORD;
            }
            if (spd >= LIGHT_MIN_SPEED) return WeaponForm.DAGGER;
            if (dmg >= FALLBACK_KATANA_DAMAGE) return WeaponForm.KATANA;
        }
        return WeaponForm.SWORD;
    }

    private static void add(EnumMap<WeaponForm, Integer> scores, WeaponForm form, int delta) {
        scores.merge(form, delta, Integer::sum);
    }
}
