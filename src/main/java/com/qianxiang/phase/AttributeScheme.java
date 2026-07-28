package com.qianxiang.phase;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「材料 → 物品属性」的组合方案（纯逻辑，无副作用）。
 * <p>
 * 输入一组 {@link MaterialInput}（每条 = 材料的功能算子集合 + 强度档），
 * 输出 {@link ComposedAttributes}（最终产物的属性与特殊效果等级）。
 * </p>
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>每个 {@link PhaseFunction} 贡献某项数值基底值（见下方常量区，全部 12 个 function 都有归属）。</li>
 *   <li>{@link PhaseTier} 乘数放大基底值：COMMON=1.0 / RARE=1.5 / EPIC=2.2 / LEGENDARY=3.2。</li>
 *   <li>特殊效果（IGNITE 等）的「等级」= 该 function 出现的次数（不同材料同效果可叠加等级）。</li>
 *   <li>{@code powerScore} = 各材料贡献经 tier 乘之后的加权求和，供概念期协商展示。</li>
 * </ul>
 *
 * <h3>所有可调常量都集中在类顶部</h3>
 * （档位乘数、各 function 的基底值、powerScore 权重），方便后续平衡调档。
 */
public final class AttributeScheme {

    private AttributeScheme() {}

    // ===================== 可调常量区（档位乘数） =====================

    /** 强度档乘数：COMMON / RARE / EPIC / LEGENDARY。 */
    public static final double TIER_COMMON = 1.0;
    public static final double TIER_RARE = 1.5;
    public static final double TIER_EPIC = 2.2;
    public static final double TIER_LEGENDARY = 3.2;

    // ===================== 可调常量区（各 function 基底值） =====================
    // 设计：「基底」类材料补耐久/速度；攻击类材料加伤害；特殊类材料定效果等级。
    // 全部值均按「COMMON 档下、单材料」基准设定，会被 TIER_*_MULTIPLIER 放大。

    // BASE_METAL：武器骨架 → 耐久 + 攻击速度微调（攻击速度数值含义见 buildWeaponModifiers）
    public static final double BASE_METAL_DURABILITY = 120.0;
    public static final double BASE_METAL_ATTACK_SPEED_BONUS = 0.4;
    public static final double BASE_METAL_POWER = 1.0;

    // BASE_WOOD：廉价基底 → 耐久
    public static final double BASE_WOOD_DURABILITY = 60.0;
    public static final double BASE_WOOD_POWER = 0.5;

    // 无相骨架：无 BASE_* 组合兜底用的虚拟基底贡献——
    // 相当于 COMMON BASE_WOOD 的 60% 耐久（60 × 0.6 = 36），无攻击速度等其他加成
    public static final double FORMLESS_BASE_DURABILITY = 36.0;

    // BASE_BONE：硬质基底 → 耐久 + 击退抗性
    public static final double BASE_BONE_DURABILITY = 80.0;
    public static final double BASE_BONE_KNOCKBACK_RESISTANCE = 0.15;
    public static final double BASE_BONE_POWER = 0.8;

    // BASE_HIDE：护甲基底 → 护甲路径
    /**
     * 皮制基底耐久。介于木质(60)与骨制(80)之间——护甲本就比武器脆，
     * 但必须有值：否则纯皮革组合的 durability 恒为 0，护甲回落到固定 150，
     * 「耐久靠材料」在整条护甲线上不成立（传奇皮甲和普通皮甲一样耐用）。
     */
    public static final double BASE_HIDE_DURABILITY = 70.0;

    public static final double BASE_HIDE_ARMOR = 2.0;
    public static final double BASE_HIDE_POWER = 0.6;

    // EDGE：锋刃 → 攻击力
    public static final double EDGE_ATTACK_DAMAGE = 3.0;
    public static final double EDGE_POWER = 1.2;

    // DEFENSE：防御 → 护甲
    public static final double DEFENSE_ARMOR = 3.0;
    public static final double DEFENSE_POWER = 1.0;

    // MANA：法力 → 护甲韧性（魔抗感）+ 法力上限加成（增幅器数值，见 AmplifierHelper）
    public static final double MANA_ARMOR_TOUGHNESS = 2.0;
    /** 每份 MANA 算子贡献的法力上限加成基底（× 档位系数取整）：增幅器化后 MANA 材料的主收益。 */
    public static final double MANA_BONUS = 8.0;
    public static final double MANA_POWER = 1.0;

    // 攻击向算子（EDGE/IGNITE/LIFESTEAL/POISON/FROST/STRENGTH/LEVITATION，
    // 与 ForgeComposer.hasWeaponTrait 同口径）→ 法术伤害加成 %（增幅器数值）
    /** 每份攻击向算子贡献的法术伤害加成基底 %（× 档位系数）。 */
    public static final double SPELL_POWER_PERCENT = 6.0;

    // HEAL：治疗术 → 特殊效果等级（每出现一次 +1 级）+ 少量生命
    public static final double HEAL_MAX_HEALTH_BONUS = 2.0;
    public static final double HEAL_POWER = 0.8;

    // SLOW：减速 → 特殊效果等级（每出现一次 +1 级）
    public static final double SLOW_POWER = 0.8;

    // REFLECT：反伤 → 特殊效果等级（每出现一次 +1 级）
    public static final double REFLECT_POWER = 0.9;

    // IGNITE：点燃 → 特殊效果等级（每出现一次 +1 级）+ 少量伤害
    public static final double IGNITE_ATTACK_DAMAGE_BONUS = 0.5;
    public static final double IGNITE_POWER = 0.9;

    // LIFESTEAL：吸血 → 特殊效果等级（每出现一次 +1 级）
    public static final double LIFESTEAL_POWER = 1.0;

    // ===== 扩展效果算子（EffectLevels）：每出现一次对应等级 +1 =====
    // POISON：中毒
    public static final double POISON_POWER = 0.9;
    // FROST：霜冻
    public static final double FROST_POWER = 0.9;
    // LEVITATION：漂浮
    public static final double LEVITATION_POWER = 0.9;
    // STRENGTH：力量
    public static final double STRENGTH_POWER = 1.0;
    // NIGHT_VISION：夜视
    public static final double NIGHT_VISION_POWER = 0.7;
    // SPEED_BOOST：迅捷
    public static final double SPEED_BOOST_POWER = 0.8;
    // JUMP_BOOST：跳跃
    public static final double JUMP_BOOST_POWER = 0.7;
    // RESISTANCE：抗性
    public static final double RESISTANCE_POWER = 1.0;
    // FIRE_RESIST：抗火
    public static final double FIRE_RESIST_POWER = 0.8;
    // WATER_BREATH：水下呼吸
    public static final double WATER_BREATH_POWER = 0.7;
    // REGENERATION：再生
    public static final double REGENERATION_POWER = 1.0;
    // GROWTH：催熟
    public static final double GROWTH_POWER = 0.8;
    // AREA_HARVEST：广域收获
    public static final double AREA_HARVEST_POWER = 1.0;

    // ===================== 可调常量区（powerScore 权重） =====================
    // powerScore = 基底贡献 × TIER × POWER_WEIGHT。
    // 让统筹者可单独调 powerScore 与实际数值强度的相对比重。

    public static final double POWER_WEIGHT_DAMAGE = 1.0;
    public static final double POWER_WEIGHT_DURABILITY = 0.01;  // 耐久数值大，权重压低
    public static final double POWER_WEIGHT_ARMOR = 0.8;
    public static final double POWER_WEIGHT_ARMOR_TOUGHNESS = 0.8;
    public static final double POWER_WEIGHT_KNOCKBACK_RESISTANCE = 2.0;
    public static final double POWER_WEIGHT_ATTACK_SPEED = 1.0;
    public static final double POWER_WEIGHT_MAX_HEALTH = 0.5;
    public static final double POWER_WEIGHT_SPECIAL_LEVEL = 3.0;  // 每点特殊效果等级贡献
    public static final double POWER_WEIGHT_SPELL_POWER_PERCENT = 0.15;  // 每点法术伤害加成 % 贡献
    public static final double POWER_WEIGHT_MANA_BONUS = 0.1;  // 每点法力上限加成贡献

    // ===================== MaterialInput（外部调用方传的小 record） =====================

    /** 一条输入材料：功能算子集合 + 强度档。 */
    public record MaterialInput(Set<PhaseFunction> functions, PhaseTier tier) {
        /** 便捷构造。 */
        public static MaterialInput of(PhaseTier tier, PhaseFunction... functions) {
            var set = java.util.EnumSet.noneOf(PhaseFunction.class);
            java.util.Collections.addAll(set, functions);
            return new MaterialInput(java.util.Collections.unmodifiableSet(set), tier);
        }
    }

    // ===================== 主入口 =====================

    /** 输入一组 MaterialInput，产出组合后的属性。 */
    public static ComposedAttributes compose(List<MaterialInput> inputs) {
        ComposedAttributes acc = ComposedAttributes.empty();
        if (inputs == null || inputs.isEmpty()) {
            return acc;
        }
        for (MaterialInput in : inputs) {
            if (in == null || in.functions() == null || in.functions().isEmpty()) {
                continue;
            }
            acc = acc.add(composeOne(in.functions(), in.tier()));
        }
        return acc;
    }

    /** 重载：直接吃 Iterable<PhaseData>，方便从 ItemStack 上的 PhaseData 调用。 */
    public static ComposedAttributes compose(Iterable<PhaseData> phaseDatas) {
        ComposedAttributes acc = ComposedAttributes.empty();
        if (phaseDatas == null) {
            return acc;
        }
        for (PhaseData pd : phaseDatas) {
            if (pd == null) {
                continue;
            }
            acc = acc.add(composeOne(pd.functions(), pd.tier()));
        }
        return acc;
    }

    /**
     * 「无相骨架」虚拟基底贡献：仅耐久（{@link #FORMLESS_BASE_DURABILITY}），
     * 无攻击速度/护甲/效果/powerScore 等其他加成。
     * <p>供 ForgeComposer 在无 BASE_* 的组合上手动补一个默认普通基底，
     * 保证「无基底也能出产物，只是耐久低些」。</p>
     */
    public static ComposedAttributes formlessBaseContribution() {
        return new ComposedAttributes(
                0.0, 0.0, (int) Math.round(FORMLESS_BASE_DURABILITY),
                0.0, 0.0, 0.0, 0.0, 0.0,
                0, 0, 0, 0, 0,
                0.0, 0,
                0.0,
                ComposedAttributes.AppearanceData.empty(),
                ComposedAttributes.ExtraEffects.empty()
        );
    }

    // ===================== 单材料翻译 =====================

    private static ComposedAttributes composeOne(Set<PhaseFunction> functions, PhaseTier tier) {
        final double mult = tierMultiplier(tier);

        double attackDamage = 0.0;
        double attackSpeed = 0.0;
        int durability = 0;
        double armor = 0.0;
        double armorToughness = 0.0;
        double knockbackResistance = 0.0;
        double moveSpeed = 0.0;
        double maxHealth = 0.0;
        int igniteLevel = 0;
        int lifestealLevel = 0;
        int thornsLevel = 0;
        int slowLevel = 0;
        int healLevel = 0;
        double spellPowerPercent = 0.0;
        int manaBonus = 0;
        int poison = 0;
        int frost = 0;
        int levitation = 0;
        int strength = 0;
        int nightVision = 0;
        int speedBoost = 0;
        int jumpBoost = 0;
        int resistance = 0;
        int fireResist = 0;
        int waterBreath = 0;
        int regeneration = 0;
        int growth = 0;
        int areaHarvest = 0;
        double powerScore = 0.0;

        for (PhaseFunction fn : functions) {
            switch (fn) {
                case BASE_METAL -> {
                    durability += scaledI(BASE_METAL_DURABILITY, mult);
                    attackSpeed += scaledF(BASE_METAL_ATTACK_SPEED_BONUS, mult);
                    powerScore += BASE_METAL_POWER * mult;
                }
                case BASE_WOOD -> {
                    durability += scaledI(BASE_WOOD_DURABILITY, mult);
                    powerScore += BASE_WOOD_POWER * mult;
                }
                case BASE_BONE -> {
                    durability += scaledI(BASE_BONE_DURABILITY, mult);
                    knockbackResistance += scaledF(BASE_BONE_KNOCKBACK_RESISTANCE, mult);
                    powerScore += BASE_BONE_POWER * mult;
                }
                case BASE_HIDE -> {
                    durability += scaledI(BASE_HIDE_DURABILITY, mult);
                    armor += scaledF(BASE_HIDE_ARMOR, mult);
                    powerScore += BASE_HIDE_POWER * mult;
                }
                case EDGE -> {
                    attackDamage += scaledF(EDGE_ATTACK_DAMAGE, mult);
                    spellPowerPercent += scaledF(SPELL_POWER_PERCENT, mult);
                    powerScore += EDGE_POWER * mult;
                }
                case DEFENSE -> {
                    armor += scaledF(DEFENSE_ARMOR, mult);
                    powerScore += DEFENSE_POWER * mult;
                }
                case MANA -> {
                    armorToughness += scaledF(MANA_ARMOR_TOUGHNESS, mult);
                    manaBonus += scaledI(MANA_BONUS, mult);
                    powerScore += MANA_POWER * mult;
                }
                case IGNITE -> {
                    igniteLevel += 1;
                    attackDamage += scaledF(IGNITE_ATTACK_DAMAGE_BONUS, mult);
                    spellPowerPercent += scaledF(SPELL_POWER_PERCENT, mult);
                    powerScore += IGNITE_POWER * mult;
                }
                case LIFESTEAL -> {
                    lifestealLevel += 1;
                    spellPowerPercent += scaledF(SPELL_POWER_PERCENT, mult);
                    powerScore += LIFESTEAL_POWER * mult;
                }
                case REFLECT -> {
                    thornsLevel += 1;
                    powerScore += REFLECT_POWER * mult;
                }
                case SLOW -> {
                    slowLevel += 1;
                    powerScore += SLOW_POWER * mult;
                }
                case HEAL -> {
                    healLevel += 1;
                    maxHealth += scaledF(HEAL_MAX_HEALTH_BONUS, mult);
                    powerScore += HEAL_POWER * mult;
                }
                case POISON -> {
                    poison += 1;
                    spellPowerPercent += scaledF(SPELL_POWER_PERCENT, mult);
                    powerScore += POISON_POWER * mult;
                }
                case FROST -> {
                    frost += 1;
                    spellPowerPercent += scaledF(SPELL_POWER_PERCENT, mult);
                    powerScore += FROST_POWER * mult;
                }
                case LEVITATION -> {
                    levitation += 1;
                    spellPowerPercent += scaledF(SPELL_POWER_PERCENT, mult);
                    powerScore += LEVITATION_POWER * mult;
                }
                case STRENGTH -> {
                    strength += 1;
                    spellPowerPercent += scaledF(SPELL_POWER_PERCENT, mult);
                    powerScore += STRENGTH_POWER * mult;
                }
                case NIGHT_VISION -> {
                    nightVision += 1;
                    powerScore += NIGHT_VISION_POWER * mult;
                }
                case SPEED_BOOST -> {
                    speedBoost += 1;
                    powerScore += SPEED_BOOST_POWER * mult;
                }
                case JUMP_BOOST -> {
                    jumpBoost += 1;
                    powerScore += JUMP_BOOST_POWER * mult;
                }
                case RESISTANCE -> {
                    resistance += 1;
                    powerScore += RESISTANCE_POWER * mult;
                }
                case FIRE_RESIST -> {
                    fireResist += 1;
                    powerScore += FIRE_RESIST_POWER * mult;
                }
                case WATER_BREATH -> {
                    waterBreath += 1;
                    powerScore += WATER_BREATH_POWER * mult;
                }
                case REGENERATION -> {
                    regeneration += 1;
                    powerScore += REGENERATION_POWER * mult;
                }
                case GROWTH -> {
                    growth += 1;
                    powerScore += GROWTH_POWER * mult;
                }
                case AREA_HARVEST -> {
                    areaHarvest += 1;
                    powerScore += AREA_HARVEST_POWER * mult;
                }
                case REVERSE -> {
                    // 反转器：纯机制开关，零数值贡献（不加属性/效果等级/powerScore），
                    // 由 ForgeComposer 检测后写入 ComposedAttributes 的反转标志。
                }
            }
        }

        // powerScore 的基底部分已在 switch 里累加（基于 POWER_*_MULTIPLIER 风格）；
        // 下面再把「数值/特殊等级」按权重转成 powerScore，保证属性堆得越多强度分越高。
        powerScore += durability * POWER_WEIGHT_DURABILITY;
        powerScore += attackDamage * POWER_WEIGHT_DAMAGE;
        powerScore += attackSpeed * POWER_WEIGHT_ATTACK_SPEED;
        powerScore += armor * POWER_WEIGHT_ARMOR;
        powerScore += armorToughness * POWER_WEIGHT_ARMOR_TOUGHNESS;
        powerScore += knockbackResistance * POWER_WEIGHT_KNOCKBACK_RESISTANCE;
        powerScore += maxHealth * POWER_WEIGHT_MAX_HEALTH;
        powerScore += (igniteLevel + lifestealLevel + thornsLevel + slowLevel + healLevel) * POWER_WEIGHT_SPECIAL_LEVEL;
        powerScore += spellPowerPercent * POWER_WEIGHT_SPELL_POWER_PERCENT;
        powerScore += manaBonus * POWER_WEIGHT_MANA_BONUS;

        ComposedAttributes.EffectLevels effects = new ComposedAttributes.EffectLevels(
                poison, frost, levitation, strength, nightVision, speedBoost,
                jumpBoost, resistance, fireResist, waterBreath, regeneration,
                growth, areaHarvest);
        powerScore += effects.totalLevels() * POWER_WEIGHT_SPECIAL_LEVEL;

        String dominantEffect = ComposedAttributes.pickDominantEffect(igniteLevel, lifestealLevel, thornsLevel, slowLevel, healLevel);
        String appearanceKey = ComposedAttributes.appearanceKeyFor(dominantEffect);
        return new ComposedAttributes(
                attackDamage, attackSpeed, durability,
                armor, armorToughness, knockbackResistance,
                moveSpeed, maxHealth,
                igniteLevel, lifestealLevel, thornsLevel, slowLevel, healLevel,
                spellPowerPercent, manaBonus,
                powerScore,
                new ComposedAttributes.AppearanceData(Set.of(), dominantEffect, appearanceKey, "", ""),
                new ComposedAttributes.ExtraEffects(effects, Map.of())
                // 自由状态效果由 ForgeComposer 扫描 effect tag 后经 withGrantedEffects 写入
        );
    }

    private static double tierMultiplier(PhaseTier tier) {
        if (tier == null) return TIER_COMMON;
        return switch (tier) {
            case COMMON -> TIER_COMMON;
            case RARE -> TIER_RARE;
            case EPIC -> TIER_EPIC;
            case LEGENDARY -> TIER_LEGENDARY;
        };
    }

    /** 耐久类需要 int，单独转。 */
    private static int scaledI(double base, double mult) {
        return (int) Math.round(base * mult);
    }

    /** 数值类（attackDamage/armor/...）保留 double。 */
    private static double scaledF(double base, double mult) {
        return base * mult;
    }

    // ===================== → Minecraft ItemAttributeModifiers =====================

    /**
     * 把 {@link ComposedAttributes} 翻译成 MC 1.21.1 的 {@link ItemAttributeModifiers}，
     * 以便塞进 {@link ItemStack} 的 {@link DataComponents#ATTRIBUTE_MODIFIERS} 组件。
     * <p>
     * 处理细节：
     * <ul>
     *   <li>{@code attackDamage}：武器默认基底为 0，这里直接 +value（即「不叠在剑基底上」，
     *       与 MC 官方工具不同——官方剑常带 +某值的 modifier，我们这里 0 基底更直白）。</li>
     *   <li>{@code attackSpeed}：MC 默认玩家攻击速度基底 4.0。我们把它表达为「+bonus」，
     *       即加快攻击（数值越大越快）。若调用方希望用「penalty」表达减速，请传负值。</li>
     *   <li>{@code durability}：<b>MC 没有内置的 durability Attribute</b>，
     *       durability 来自 {@code Item.getMaxDamage(ItemStack)}——各产物 Item 子类
     *       （QianxiangWeaponItem/QianxiangArmorItem/QianxiangToolItem）override 它读
     *       ComposedAttributes.durability，因此本方法<b>不</b>生成 durability 的 entry。
     *       <p><b>前提</b>：产物注册时必须带 {@code Properties.durability(...)}，
     *       否则栈上没有 MAX_DAMAGE/DAMAGE 组件，{@code isDamageableItem()} 为 false，
     *       整条耐久链（含 frail 代价的 hurtAndBreak）全部空转，override 形同虚设。
     *       见 {@code QianxiangItems} 各产物注册处。</li>
     *   <li>特殊效果等级（ignite/lifesteal/thorns/slow/heal）：不是 attribute，
     *       由 Epic Fight 技能 / 自定义事件钩子读取 ComposedAttributes 决定行为，
     *       本方法也不生成对应 entry。</li>
     * </ul>
     *
     * @param attr 已组合好的属性
     * @return 可直接塞进 ItemStack 的 ItemAttributeModifiers
     */
    public static ItemAttributeModifiers buildWeaponModifiers(ComposedAttributes attr) {
        List<ItemAttributeModifiers.Entry> entries = new ArrayList<>();
        String ns = "qianxiang";

        if (attr.attackDamage() != 0.0) {
            entries.add(entry(
                    Attributes.ATTACK_DAMAGE,
                    new AttributeModifier(
                            ResourceLocation.fromNamespaceAndPath(ns, "composed.attack_damage"),
                            attr.attackDamage(),
                            AttributeModifier.Operation.ADD_VALUE
                    ),
                    EquipmentSlotGroup.MAINHAND
            ));
        }
        if (attr.attackSpeed() != 0.0) {
            entries.add(entry(
                    Attributes.ATTACK_SPEED,
                    new AttributeModifier(
                            ResourceLocation.fromNamespaceAndPath(ns, "composed.attack_speed"),
                            attr.attackSpeed(),
                            AttributeModifier.Operation.ADD_VALUE
                    ),
                    EquipmentSlotGroup.MAINHAND
            ));
        }
        if (attr.maxHealth() != 0.0) {
            entries.add(entry(
                    Attributes.MAX_HEALTH,
                    new AttributeModifier(
                            ResourceLocation.fromNamespaceAndPath(ns, "composed.max_health"),
                            attr.maxHealth(),
                            AttributeModifier.Operation.ADD_VALUE
                    ),
                    EquipmentSlotGroup.MAINHAND
            ));
        }
        if (attr.armor() != 0.0) {
            entries.add(entry(
                    Attributes.ARMOR,
                    new AttributeModifier(
                            ResourceLocation.fromNamespaceAndPath(ns, "composed.armor"),
                            attr.armor(),
                            AttributeModifier.Operation.ADD_VALUE
                    ),
                    EquipmentSlotGroup.MAINHAND
            ));
        }
        if (attr.armorToughness() != 0.0) {
            entries.add(entry(
                    Attributes.ARMOR_TOUGHNESS,
                    new AttributeModifier(
                            ResourceLocation.fromNamespaceAndPath(ns, "composed.armor_toughness"),
                            attr.armorToughness(),
                            AttributeModifier.Operation.ADD_VALUE
                    ),
                    EquipmentSlotGroup.MAINHAND
            ));
        }
        if (attr.knockbackResistance() != 0.0) {
            entries.add(entry(
                    Attributes.KNOCKBACK_RESISTANCE,
                    new AttributeModifier(
                            ResourceLocation.fromNamespaceAndPath(ns, "composed.knockback_resistance"),
                            attr.knockbackResistance(),
                            AttributeModifier.Operation.ADD_VALUE
                    ),
                    EquipmentSlotGroup.MAINHAND
            ));
        }
        if (attr.moveSpeed() != 0.0) {
            entries.add(entry(
                    Attributes.MOVEMENT_SPEED,
                    new AttributeModifier(
                            ResourceLocation.fromNamespaceAndPath(ns, "composed.move_speed"),
                            attr.moveSpeed(),
                            AttributeModifier.Operation.ADD_VALUE
                    ),
                    EquipmentSlotGroup.MAINHAND
            ));
        }

        // ItemAttributeModifiers(List<Entry> modifiers, boolean showInTooltip)
        return new ItemAttributeModifiers(entries, true);
    }

    /**
     * 把 {@link ComposedAttributes} 的数值部分写进一个 {@link ItemStack} 的
     * {@link DataComponents#ATTRIBUTE_MODIFIERS} 组件。
     * <p>
     * <b>注意</b>：
     * <ul>
     *   <li>本方法<b>不</b>处理 durability 的 Item.getMaxDamage() —— 调用方需在
     *       自定义 Item 子类里 override getMaxDamage(ItemStack) 读取
     *       ComposedAttributes.durability。</li>
     *   <li>本方法<b>不</b>写自定义 {@code composed_attributes} component ——
     *       统筹者在 {@link ItemStack} 上额外调用
     *       {@code stack.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES, attr)}
     *       （见集成契约），特殊效果等级才得以随产物携带。</li>
     * </ul>
     * </p>
     */
    public static void applyModifiersToStack(ItemStack stack, ComposedAttributes attr) {
        if (stack == null) return;
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, buildWeaponModifiers(attr));
    }

    private static ItemAttributeModifiers.Entry entry(
            Holder<Attribute> attribute, AttributeModifier modifier, EquipmentSlotGroup slot) {
        return new ItemAttributeModifiers.Entry(attribute, modifier, slot);
    }
}
