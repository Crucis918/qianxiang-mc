package com.qianxiang.compat;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.QianxiangItems;
import com.qianxiang.combat.WeaponMoveset;
import com.qianxiang.item.QianxiangToolItem;
import com.qianxiang.item.QianxiangWeaponItem;
import com.qianxiang.phase.ComposedAttributes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.api.collider.Collider;
import yesman.epicfight.api.collider.MultiOBBCollider;
import yesman.epicfight.gameasset.ColliderPreset;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.item.WeaponCapability;
import yesman.epicfight.world.capabilities.item.WeaponCapabilityPresets;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;

/**
 * Epic Fight 动作真适配：按锻造产物「特征」在运行时动态指定武器动作类型，
 * 不再依赖固定物品 → 固定 EF 类型的静态 JSON。
 *
 * <h3>机制（读自 EF 21.15.6 字节码核实）</h3>
 * <ul>
 *   <li>EF 在 {@code RegisterCapabilitiesEvent} 里给注册表内<b>所有</b>物品注册
 *       {@code CommonItemCapabilityProvider}；它只对自己 map 里有的物品
 *       （datapack {@code capabilities/weapons/*.json} 命中的）返回非 null，
 *       未命中返回 null（已核实其 getCapability 字节码）。</li>
 *   <li>NeoForge {@code ItemCapability.getCapability} 按<b>注册顺序</b>遍历 provider，
 *       返回第一个非 null 结果（已核实字节码）。mod-bus 事件按 mod 依赖排序派发，
 *       因此 mods.toml 声明 {@code ordering = "BEFORE"} epicfight 后，
 *       本类的 provider 先于 EF 的注册，对千相产物优先生效。</li>
 *   <li>若排序失效（理论上），EF 静态 JSON 依旧兜底 → 行为退化为旧版，不会坏。</li>
 * </ul>
 *
 * <h3>特征 → 动作映射</h3>
 * <ul>
 *   <li>重型（attackDamage ≥ {@value #HEAVY_MIN_DAMAGE} 且攻速加成 ≤ {@value #HEAVY_MAX_SPEED}）→ greatsword</li>
 *   <li>轻型（攻速加成 ≥ {@value #LIGHT_MIN_SPEED}，或骨刃外观）→ dagger</li>
 *   <li>长柄（相锄等工具）→ spear</li>
 *   <li>法系相杖（EF 无杖类）→ dagger 快速动作</li>
 *   <li>盾 → shield</li>
 *   <li>标准金属刃兜底 → tachi（保持与旧静态 JSON 一致）；未来非刃类武器 → longsword</li>
 * </ul>
 *
 * <p>旧的 4 个静态 JSON（ember_blade/bone_blade/phase_shield/phase_staff + keyword）
 * 全部保留：它们现在只在本类被禁用或 provider 返回 null 时兜底。</p>
 *
 * <h3>CUSTOM_MOVESET（玩家描述的自定义攻击动作）</h3>
 * <p>堆叠带 {@code qianxiang:custom_moveset} 组件（{@link WeaponMoveset}，由 AI 按玩家
 * 描述从 EF 动画库挑选组合）时优先于特征分类：</p>
 * <ul>
 *   <li>底座：{@code category} 命中的 EF 预设（继承其属性/风格切换/握持动作）；</li>
 *   <li>连击：{@code combos} 经 {@code AnimationManager.byKey} 解析后，用
 *       {@code newStyleCombo} 覆盖 ONE_HAND/TWO_HAND/COMMON 三风格（已核实该方法
 *       是对 autoAttackMotionMap 的 put，即同风格整体替换）；</li>
 *   <li>判定盒：{@code colliderPreset} 引用 {@link ColliderPreset} 内置实例
 *       （{@code epicfight:tachi} 等），或 {@code custom:<段数>:<宽,高,长>:<中心x,y,z>}
 *       自定义 {@link MultiOBBCollider}。</li>
 * </ul>
 * <p>动画未加载（byKey 全 null）或 moveset 构建异常时不做负缓存、不置 {@link #failed}，
 * 回退特征分类，下次查询重试——无 moveset 的武器行为完全不变。</p>
 */
public final class QianxiangEFCompat {

    private QianxiangEFCompat() {}

    // ===================== 可调阈值（与 AttributeScheme 的基底值配套） =====================

    /** 重型：合成攻击力达到该值且攻速加成低 → greatsword（EDGE 基底 3.0/件，两件即 6.0）。 */
    public static final double HEAVY_MIN_DAMAGE = 6.0;
    /** 重型判定附带的攻速加成上限（BASE_METAL 每件 +0.4，单金属基底为 0.4）。 */
    public static final double HEAVY_MAX_SPEED = 0.6;
    /** 轻型：攻速加成达到该值 → dagger（两件金属基底 0.8）。 */
    public static final double LIGHT_MIN_SPEED = 0.8;

    /** 设为 false 可整体关闭动态适配，回退到纯静态 JSON 行为。 */
    public static final boolean ENABLED = true;

    /** EF 动作原型（对应 WeaponCapabilityPresets 的同名预设）。 */
    private enum Archetype { GREATSWORD, DAGGER, SPEAR, LONGSWORD, TACHI, SHIELD }

    /** 已构建的 capability 缓存：preset 与具体堆叠无关，构建一次复用即可。 */
    private static final Map<Archetype, CapabilityItem> CACHE = new EnumMap<>(Archetype.class);

    /** 异常只记一次，避免刷屏。 */
    private static boolean failed = false;

    /** 在 mod 构造期挂到 modEventBus（Qianxiang 主类调用）。 */
    public static void register(RegisterCapabilitiesEvent event) {
        if (!ENABLED) return;
        event.registerItem(
                EpicFightCapabilities.CAPABILITY_ITEM,
                QianxiangEFCompat::provide,
                QianxiangItems.EMBER_BLADE.get(),
                QianxiangItems.BONE_BLADE.get(),
                QianxiangItems.PHASE_STAFF.get(),
                QianxiangItems.PHASE_SHIELD.get(),
                QianxiangItems.PHASE_HOE.get());
        Qianxiang.LOGGER.info("[Qianxiang] EpicFight 动态武器动作适配已注册（特征→动作，静态 JSON 兜底）。");
    }

    /** NeoForge capability provider：moveset 优先，其次按堆叠特征；不接管时返回 null 交给 EF 静态 JSON。 */
    private static CapabilityItem provide(ItemStack stack, Void context) {
        if (!ENABLED || failed) return null;
        try {
            // 玩家描述的自定义动作（CUSTOM_MOVESET 组件）优先于特征分类
            WeaponMoveset moveset = stack.get(QianxiangDataComponents.CUSTOM_MOVESET.get());
            if (moveset != null && !moveset.combos().isEmpty()) {
                CapabilityItem custom = provideMoveset(stack, moveset);
                if (custom != null) return custom;
                // null：动画未加载或构建失败 → 继续走特征分类，不至于没动作
            }
            Archetype archetype = classify(stack);
            if (archetype == null) return null;
            CapabilityItem cached = CACHE.get(archetype);
            if (cached == null) {
                cached = build(archetype, stack.getItem());
                CACHE.put(archetype, cached);
            }
            return cached;
        } catch (Throwable t) {
            // 吞异常：动态适配失败不该影响游戏，回退到 EF 静态 JSON
            failed = true;
            Qianxiang.LOGGER.warn("[Qianxiang] EpicFight 动态武器动作适配失败，回退静态 JSON：{}", t.toString());
            return null;
        }
    }

    /** 特征分类：返回 null 表示本类不接管（交给 EF 既有逻辑）。 */
    private static Archetype classify(ItemStack stack) {
        Item item = stack.getItem();

        // 固定外形载体优先：盾 / 法杖 / 长柄工具
        if (item == QianxiangItems.PHASE_SHIELD.get()) return Archetype.SHIELD;
        if (item == QianxiangItems.PHASE_STAFF.get()) return Archetype.DAGGER; // EF 无杖类，用快速动作
        if (item instanceof QianxiangToolItem) return Archetype.SPEAR;          // 相锄等长柄

        if (!(item instanceof QianxiangWeaponItem)) return null;

        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr != null) {
            // 重型：高攻 + 慢速 → 大剑
            if (attr.attackDamage() >= HEAVY_MIN_DAMAGE && attr.attackSpeed() <= HEAVY_MAX_SPEED) {
                return Archetype.GREATSWORD;
            }
            // 轻型：快攻速或骨质外观 → 匕首
            if (attr.attackSpeed() >= LIGHT_MIN_SPEED || "bone".equals(attr.appearanceKey())) {
                return Archetype.DAGGER;
            }
        }

        // 标准刃兜底：保持旧静态 JSON 的 tachi 手感；骨刃空壳也按匕首
        if (item == QianxiangItems.BONE_BLADE.get()) return Archetype.DAGGER;
        return Archetype.TACHI;
    }

    /** 用 EF 官方预设构建 capability（首次调用时游戏已进世界，技能/动画注册表就绪）。 */
    private static CapabilityItem build(Archetype archetype, Item item) {
        return switch (archetype) {
            case GREATSWORD -> WeaponCapabilityPresets.GREATSWORD.apply(item).build();
            case DAGGER -> WeaponCapabilityPresets.DAGGER.apply(item).build();
            case SPEAR -> WeaponCapabilityPresets.SPEAR.apply(item).build();
            case LONGSWORD -> WeaponCapabilityPresets.LONGSWORD.apply(item).build();
            case TACHI -> WeaponCapabilityPresets.TACHI.apply(item).build();
            case SHIELD -> WeaponCapabilityPresets.SHIELD.apply(item).build();
        };
    }

    // ===================== CUSTOM_MOVESET：玩家描述的自定义攻击动作 =====================

    /**
     * category 名（小写）→ EF 预设，作为自定义 moveset 的底座
     * （属性/风格切换/握持动作/骑马攻击都继承底座，只覆盖连击与判定盒）。
     * shield 不是 WeaponCapability，不在此列——moveset 对盾无意义。
     */
    private static final Map<String, Function<Item, WeaponCapability.Builder>> CATEGORY_PRESETS = Map.of(
            "greatsword", WeaponCapabilityPresets.GREATSWORD,
            "dagger", WeaponCapabilityPresets.DAGGER,
            "spear", WeaponCapabilityPresets.SPEAR,
            "longsword", WeaponCapabilityPresets.LONGSWORD,
            "tachi", WeaponCapabilityPresets.TACHI,
            "uchigatana", WeaponCapabilityPresets.UCHIGATANA,
            "sword", WeaponCapabilityPresets.SWORD,
            "axe", WeaponCapabilityPresets.AXE,
            "fist", WeaponCapabilityPresets.FIST);

    /** 已构建的 moveset capability 缓存：同一 moveset（record equals）复用同一实例。 */
    private static final Map<WeaponMoveset, CapabilityItem> MOVESET_CACHE = new HashMap<>();

    /** 构建时抛过硬异常的 moveset 黑名单：不再重试，直接回退特征分类（动画未加载不在此列）。 */
    private static final Set<WeaponMoveset> MOVESET_BLACKLIST = new HashSet<>();

    /** 已警告过的缺失动画 id / 非法判定盒，避免日志刷屏。 */
    private static final Set<String> MOVESET_WARNED = new HashSet<>();

    /** moveset 分支：成功返回自定义 capability；返回 null 表示本分支不接管（回退特征分类）。 */
    private static CapabilityItem provideMoveset(ItemStack stack, WeaponMoveset moveset) {
        if (MOVESET_BLACKLIST.contains(moveset)) return null;
        CapabilityItem cached = MOVESET_CACHE.get(moveset);
        if (cached != null) return cached;
        try {
            CapabilityItem built = buildMoveset(moveset, stack);
            if (built != null) MOVESET_CACHE.put(moveset, built);
            // built == null：动画还没加载（byKey 全 miss）——不做负缓存，下次查询重试
            return built;
        } catch (Throwable t) {
            // 单个坏 moveset 不该拖垮整个动态适配：拉黑该 moveset，不动 failed 总开关
            MOVESET_BLACKLIST.add(moveset);
            Qianxiang.LOGGER.warn("[Qianxiang] 自定义武器动作构建失败，该产物回退特征分类：{}（{}）",
                    moveset, t.toString());
            return null;
        }
    }

    /**
     * 用 moveset 构建 capability：预设做底 + 覆盖连击序列 + 覆盖 category/判定盒。
     * 返回 null 仅表示「连击动画一个都解析不出来」（通常是动画尚未加载），调用方重试。
     */
    private static CapabilityItem buildMoveset(WeaponMoveset moveset, ItemStack stack) {
        String catKey = moveset.category().toLowerCase(Locale.ROOT);

        // 1) 底座预设：category 命中用对应预设；未命中退回特征分类的原型
        Function<Item, WeaponCapability.Builder> presetFn = CATEGORY_PRESETS.get(catKey);
        if (presetFn == null) {
            Archetype fallback = classify(stack);
            presetFn = fallback == null ? null : weaponPresetFor(fallback);
            if (presetFn == null) return null; // 盾等无武器预设：不接管
        }

        // 2) 解析连击动画（AnimationManager.byKey 是 map 查找，动画未加载时返回 null）
        List<AnimationManager.AnimationAccessor<? extends AttackAnimation>> resolved = new ArrayList<>();
        for (ResourceLocation id : moveset.combos()) {
            AnimationManager.AnimationAccessor<AttackAnimation> accessor = AnimationManager.byKey(id);
            if (accessor == null) {
                warnOnce("anim:" + id, "[Qianxiang] 自定义武器动作找不到动画 {}，已跳过该段", id);
                continue;
            }
            resolved.add(accessor);
        }
        if (resolved.isEmpty()) return null;

        WeaponCapability.Builder builder = presetFn.apply(stack.getItem());

        // 3) 覆盖连击：单手/双手/通用三风格都给同一序列，任何持法普攻都是这套自定义连段
        //    （newStyleCombo 是对 autoAttackMotionMap 的 put，同风格整体替换预设连段）
        @SuppressWarnings({"unchecked", "rawtypes"})
        AnimationManager.AnimationAccessor<? extends AttackAnimation>[] comboArr =
                resolved.toArray(new AnimationManager.AnimationAccessor[0]);
        builder.newStyleCombo(CapabilityItem.Styles.ONE_HAND, comboArr);
        builder.newStyleCombo(CapabilityItem.Styles.TWO_HAND, comboArr);
        builder.newStyleCombo(CapabilityItem.Styles.COMMON, comboArr);

        // 4) category 覆盖（EF 的可扩展枚举按小写名解析；未知名跳过，保留底座的）
        try {
            builder.category(CapabilityItem.WeaponCategories.valueOf(catKey.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException | NoSuchElementException e) {
            warnOnce("category:" + catKey, "[Qianxiang] 自定义武器动作的 category {} 不是 EF 内置类别，沿用底座", catKey);
        }

        // 5) 判定盒：内置预设名或 custom: 参数；解析不出就沿用底座预设的判定盒
        Collider collider = resolveCollider(moveset.colliderPreset());
        if (collider != null) builder.collider(collider);

        return builder.build();
    }

    /** 特征分类原型 → 武器预设（SHIELD 无 WeaponCapability 预设，返回 null）。 */
    private static Function<Item, WeaponCapability.Builder> weaponPresetFor(Archetype archetype) {
        return switch (archetype) {
            case GREATSWORD -> WeaponCapabilityPresets.GREATSWORD;
            case DAGGER -> WeaponCapabilityPresets.DAGGER;
            case SPEAR -> WeaponCapabilityPresets.SPEAR;
            case LONGSWORD -> WeaponCapabilityPresets.LONGSWORD;
            case TACHI -> WeaponCapabilityPresets.TACHI;
            case SHIELD -> null;
        };
    }

    /**
     * 解析判定盒描述：
     * <ul>
     *   <li>空白 → null（沿用底座预设的判定盒）；</li>
     *   <li>内置预设名（{@code tachi} / {@code greatsword} / …，可带 {@code epicfight:} 命名空间）
     *       → {@link ColliderPreset#get} 对应实例；</li>
     *   <li>{@code custom:<段数>:<宽x,高y,长z>:<中心x,中心y,中心z>}
     *       → 自定义 {@link MultiOBBCollider}（参数与 EF datapack collider 的
     *       number/size/center 一致）。</li>
     * </ul>
     */
    private static Collider resolveCollider(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String s = spec.trim();
        if (s.startsWith("custom:")) {
            try {
                String[] parts = s.substring("custom:".length()).split(":");
                if (parts.length != 3) throw new IllegalArgumentException("需要 custom:<段数>:<宽,高,长>:<中心x,y,z>");
                int number = Integer.parseInt(parts[0].trim());
                String[] size = parts[1].split(",");
                String[] center = parts[2].split(",");
                if (number < 1 || size.length != 3 || center.length != 3) {
                    throw new IllegalArgumentException("段数须 ≥1，size/center 各 3 个分量");
                }
                return new MultiOBBCollider(number,
                        Double.parseDouble(size[0].trim()), Double.parseDouble(size[1].trim()),
                        Double.parseDouble(size[2].trim()),
                        Double.parseDouble(center[0].trim()), Double.parseDouble(center[1].trim()),
                        Double.parseDouble(center[2].trim()));
            } catch (Throwable t) {
                warnOnce("collider:" + s, "[Qianxiang] 自定义武器动作的判定盒参数 {} 非法，沿用底座（{}）", s, t.toString());
                return null;
            }
        }
        ResourceLocation key = ResourceLocation.tryParse(s.contains(":") ? s : "epicfight:" + s.toLowerCase(Locale.ROOT));
        Collider collider = key == null ? null : ColliderPreset.get(key);
        if (collider == null) {
            warnOnce("collider:" + s, "[Qianxiang] 自定义武器动作找不到判定盒预设 {}，沿用底座", s);
        }
        return collider;
    }

    /** 同一个问题只警告一次。 */
    private static void warnOnce(String key, String message, Object... args) {
        if (MOVESET_WARNED.add(key)) {
            Qianxiang.LOGGER.warn(message, args);
        }
    }
}
