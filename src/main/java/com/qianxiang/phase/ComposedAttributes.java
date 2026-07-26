package com.qianxiang.phase;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 「材料即零件」的最终产物：把若干材料的(功能算子+档位)翻译成的一坨属性。
 * <p>
 * 由 {@link AttributeScheme#compose} 产出，存于产物的
 * {@code qianxiang:composed_attributes} DataComponent。
 * </p>
 * <p>
 * 字段分三类：
 * <ul>
 *   <li>数值属性：attackDamage / attackSpeed / durability / armor / armorToughness /
 *       knockbackResistance / moveSpeed / maxHealth —— 由 EDGE/DEFENSE/BASE_* 等贡献。</li>
 *   <li>特殊效果等级（int）：igniteLevel / lifestealLevel / thornsLevel / slowLevel / healLevel
 *       —— 由 IGNITE/LIFESTEAL/REFLECT/SLOW/HEAL 贡献，等级 ≥1 才生效。</li>
 *   <li>外观驱动：{@link AppearanceData}（主导相性/效果/外观键）——
 *       由 {@link ForgeComposer} 根据材料并集生成，供
 *       {@link com.qianxiang.item.QianxiangWeaponItem} 切换粒子/光泽/tooltip。</li>
 *   <li>自由状态效果：grantedEffects（MobEffect registry id → 等级）——
 *       由带 {@code qianxiang:materials/effect/<effect_path>} tag 的材料贡献，
 *       见 {@link EffectMaterialResolver}；与固定算子系统并存，支持任意 MC 状态效果。</li>
 * </ul>
 * {@link #powerScore} = 强度总分，概念期协商时给玩家/AI 估强度用。
 * </p>
 * <p>设计原则：强度来自材料稀有度；但当强度总分超阈值或正面效果过多时，
 * 由 {@link DrawbackRules} 自动生成 1~2 个代价效果写入 {@link DrawbackLevels}
 * （嵌在 {@link ExtraEffects} 子记录里），「强力必有代价」维持平衡。</p>
 */
public record ComposedAttributes(
        double attackDamage,
        double attackSpeed,
        int durability,
        double armor,
        double armorToughness,
        double knockbackResistance,
        double moveSpeed,
        double maxHealth,
        int igniteLevel,
        int lifestealLevel,
        int thornsLevel,
        int slowLevel,
        int healLevel,
        double powerScore,
        AppearanceData appearance,
        ExtraEffects extraEffects
) {

    /** 规范构造：extraEffects 归一化为非 null，旧存档/手写构造传 null 也安全。 */
    public ComposedAttributes {
        if (extraEffects == null) extraEffects = ExtraEffects.empty();
    }

    /** 外观数据：主导相性、主导效果、外观键。单独抽成记录避免 ComposedAttributes 字段超过 16 个。 */
    public record AppearanceData(Set<Phase> dominantPhases, String dominantEffect, String appearanceKey) {
        public static final Codec<AppearanceData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Phase.SET_CODEC.optionalFieldOf("dominant_phases", Set.of()).forGetter(AppearanceData::dominantPhases),
                Codec.STRING.optionalFieldOf("dominant_effect", "none").forGetter(AppearanceData::dominantEffect),
                Codec.STRING.optionalFieldOf("appearance_key", "plain").forGetter(AppearanceData::appearanceKey)
        ).apply(instance, AppearanceData::new));

        public static AppearanceData empty() {
            return new AppearanceData(Set.of(), "none", "plain");
        }
    }

    /**
     * 扩展效果等级（13 个新算子：中毒/霜冻/漂浮/力量/夜视/迅捷/跳跃/抗性/抗火/水下呼吸/再生/催熟/广域）。
     * 全部 optionalFieldOf 默认 0，旧存档无此字段时解码为全零，保持兼容。
     * 合并规则与经典五效果一致：取大（同效果不无限叠加）。
     */
    public record EffectLevels(
            int poison,
            int frost,
            int levitation,
            int strength,
            int nightVision,
            int speedBoost,
            int jumpBoost,
            int resistance,
            int fireResist,
            int waterBreath,
            int regeneration,
            int growth,
            int areaHarvest
    ) {
        public static final Codec<EffectLevels> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("poison", 0).forGetter(EffectLevels::poison),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("frost", 0).forGetter(EffectLevels::frost),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("levitation", 0).forGetter(EffectLevels::levitation),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("strength", 0).forGetter(EffectLevels::strength),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("night_vision", 0).forGetter(EffectLevels::nightVision),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("speed_boost", 0).forGetter(EffectLevels::speedBoost),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("jump_boost", 0).forGetter(EffectLevels::jumpBoost),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("resistance", 0).forGetter(EffectLevels::resistance),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("fire_resist", 0).forGetter(EffectLevels::fireResist),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("water_breath", 0).forGetter(EffectLevels::waterBreath),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("regeneration", 0).forGetter(EffectLevels::regeneration),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("growth", 0).forGetter(EffectLevels::growth),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("area_harvest", 0).forGetter(EffectLevels::areaHarvest)
        ).apply(instance, EffectLevels::new));

        public static EffectLevels empty() {
            return new EffectLevels(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        /** 合并：每项取大。 */
        public EffectLevels max(EffectLevels other) {
            if (other == null) return this;
            return new EffectLevels(
                    Math.max(poison, other.poison),
                    Math.max(frost, other.frost),
                    Math.max(levitation, other.levitation),
                    Math.max(strength, other.strength),
                    Math.max(nightVision, other.nightVision),
                    Math.max(speedBoost, other.speedBoost),
                    Math.max(jumpBoost, other.jumpBoost),
                    Math.max(resistance, other.resistance),
                    Math.max(fireResist, other.fireResist),
                    Math.max(waterBreath, other.waterBreath),
                    Math.max(regeneration, other.regeneration),
                    Math.max(growth, other.growth),
                    Math.max(areaHarvest, other.areaHarvest)
            );
        }

        /** 任一扩展效果等级 > 0。 */
        public boolean anyPositive() {
            return poison > 0 || frost > 0 || levitation > 0 || strength > 0
                    || nightVision > 0 || speedBoost > 0 || jumpBoost > 0 || resistance > 0
                    || fireResist > 0 || waterBreath > 0 || regeneration > 0
                    || growth > 0 || areaHarvest > 0;
        }

        /** 最高单项等级（外观稀有度 tier 推导用）。 */
        public int maxLevel() {
            int m = Math.max(poison, Math.max(frost, Math.max(levitation, strength)));
            m = Math.max(m, Math.max(nightVision, Math.max(speedBoost, Math.max(jumpBoost, resistance))));
            m = Math.max(m, Math.max(fireResist, Math.max(waterBreath, Math.max(regeneration, growth))));
            return Math.max(m, areaHarvest);
        }

        /** 全部扩展效果等级之和（powerScore 加权用）。 */
        public int totalLevels() {
            return poison + frost + levitation + strength + nightVision + speedBoost
                    + jumpBoost + resistance + fireResist + waterBreath + regeneration
                    + growth + areaHarvest;
        }
    }

    /**
     * 代价效果等级：产物强度超阈值时由 {@link DrawbackRules} 自动生成（「强力必有代价」）。
     * 全部 optionalFieldOf 默认 0，旧存档无此字段时解码为全零——旧产物不会凭空多出代价。
     * <ul>
     *   <li><b>frail</b> 易碎：耐久消耗加倍（损耗处额外扣等级点）。</li>
     *   <li><b>heavy</b> 沉重：持有/穿戴时移速下降（弱效缓慢）。</li>
     *   <li><b>draining</b> 耗力：攻击时令玩家饥饿。</li>
     *   <li><b>unstable</b> 不稳：攻击时小概率反噬自己。</li>
     *   <li><b>cursed</b> 诅咒：持有/穿戴时随机获得短暂负面状态。</li>
     * </ul>
     * 兑现见 {@link com.qianxiang.combat.DrawbackHandler}。
     */
    public record DrawbackLevels(
            int frail,
            int heavy,
            int draining,
            int unstable,
            int cursed
    ) {
        public static final Codec<DrawbackLevels> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("frail", 0).forGetter(DrawbackLevels::frail),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("heavy", 0).forGetter(DrawbackLevels::heavy),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("draining", 0).forGetter(DrawbackLevels::draining),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("unstable", 0).forGetter(DrawbackLevels::unstable),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("cursed", 0).forGetter(DrawbackLevels::cursed)
        ).apply(instance, DrawbackLevels::new));

        public static DrawbackLevels empty() {
            return new DrawbackLevels(0, 0, 0, 0, 0);
        }

        /** 合并：每项取大（与 EffectLevels 同规则）。 */
        public DrawbackLevels max(DrawbackLevels other) {
            if (other == null) return this;
            return new DrawbackLevels(
                    Math.max(frail, other.frail),
                    Math.max(heavy, other.heavy),
                    Math.max(draining, other.draining),
                    Math.max(unstable, other.unstable),
                    Math.max(cursed, other.cursed)
            );
        }

        /** 任一代价等级 > 0。 */
        public boolean anyPositive() {
            return frail > 0 || heavy > 0 || draining > 0 || unstable > 0 || cursed > 0;
        }
    }

    /**
     * 效果打包子记录：固定 13 算子的 {@link EffectLevels} + 自由状态效果 grantedEffects
     * （MobEffect registry id → 等级，由 {@code qianxiang:materials/effect/*} tag 材料贡献）
     * + 代价效果 {@link DrawbackLevels} + 反转标志 {@code reversed}
     * （材料含逆相之核/REVERSE 算子时为真，见 {@link ComposedAttributes#isReversed()}）。
     * <p>
     * 单独抽成记录是因为 DFU {@code RecordCodecBuilder.group} 上限 16 元——
     * 主 record 加 grantedEffects 后 17 字段超限，遂把两类效果包成一层。
     * </p>
     * <h3>存档兼容</h3>
     * 主 Codec 仍以顶层键 {@code "effects"} 存本子记录。旧存档里该键的值是
     * <b>扁平的 EffectLevels</b>（{@code {"poison":3,...}}），新格式是嵌套的
     * {@code {"effects":{...},"granted_effects":{...}}}。解码时按是否出现
     * {@code "effects"}/{@code "granted_effects"} 子键区分新旧格式，
     * 旧扁平格式自动包成 {@code ExtraEffects(effectLevels, Map.of())}，旧产物不丢效果。
     */
    public record ExtraEffects(EffectLevels effects, Map<ResourceLocation, Integer> grantedEffects,
                               DrawbackLevels drawbacks, boolean reversed) {

        /** 规范构造：三个分量都归一化为非 null。 */
        public ExtraEffects {
            if (effects == null) effects = EffectLevels.empty();
            grantedEffects = grantedEffects == null ? Map.of() : Map.copyOf(grantedEffects);
            if (drawbacks == null) drawbacks = DrawbackLevels.empty();
        }

        /** 兼容旧的两参构造（无代价、无反转）：供既有调用方（如 AttributeScheme）不改动地编译。 */
        public ExtraEffects(EffectLevels effects, Map<ResourceLocation, Integer> grantedEffects) {
            this(effects, grantedEffects, DrawbackLevels.empty(), false);
        }

        /** 兼容旧的三参构造（无反转）：reversed 默认 false。 */
        public ExtraEffects(EffectLevels effects, Map<ResourceLocation, Integer> grantedEffects,
                            DrawbackLevels drawbacks) {
            this(effects, grantedEffects, drawbacks, false);
        }

        /** 新格式（嵌套）的结构化 Codec。drawbacks/reversed optionalFieldOf 默认全零/false，旧存档兼容。 */
        private static final Codec<ExtraEffects> STRUCT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EffectLevels.CODEC.optionalFieldOf("effects", EffectLevels.empty()).forGetter(ExtraEffects::effects),
                Codec.unboundedMap(ResourceLocation.CODEC, Codec.INT)
                        .optionalFieldOf("granted_effects", Map.of()).forGetter(ExtraEffects::grantedEffects),
                DrawbackLevels.CODEC.optionalFieldOf("drawbacks", DrawbackLevels.empty()).forGetter(ExtraEffects::drawbacks),
                Codec.BOOL.optionalFieldOf("reversed", false).forGetter(ExtraEffects::reversed)
        ).apply(instance, ExtraEffects::new));

        /** 兼容新旧两种线格式的 Codec：编码恒写新格式；解码自动识别旧扁平格式。 */
        public static final Codec<ExtraEffects> CODEC = new Codec<>() {
            @Override
            public <T> DataResult<Pair<ExtraEffects, T>> decode(DynamicOps<T> ops, T input) {
                boolean nested = ops.getMap(input).result()
                        .map(m -> m.get(ops.createString("granted_effects")) != null
                                || m.get(ops.createString("effects")) != null)
                        .orElse(false);
                if (nested) {
                    return STRUCT_CODEC.decode(ops, input);
                }
                // 旧格式：值本身就是扁平的 EffectLevels
                return EffectLevels.CODEC.decode(ops, input)
                        .map(p -> p.mapFirst(fx -> new ExtraEffects(fx, Map.of())));
            }

            @Override
            public <T> DataResult<T> encode(ExtraEffects value, DynamicOps<T> ops, T prefix) {
                return STRUCT_CODEC.encode(value, ops, prefix);
            }
        };

        public static ExtraEffects empty() {
            return new ExtraEffects(EffectLevels.empty(), Map.of());
        }

        /** 合并：EffectLevels 逐项取大；grantedEffects 同效果取大；drawbacks 逐项取大；reversed 任一为真即真。 */
        public ExtraEffects max(ExtraEffects other) {
            if (other == null) return this;
            return new ExtraEffects(
                    this.effects.max(other.effects),
                    ComposedAttributes.mergeGrantedEffects(this.grantedEffects, other.grantedEffects),
                    this.drawbacks.max(other.drawbacks),
                    this.reversed || other.reversed);
        }
    }

    // ============================ Codec ============================

    public static final Codec<ComposedAttributes> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.doubleRange(0.0, Double.MAX_VALUE).optionalFieldOf("attack_damage", 0.0).forGetter(ComposedAttributes::attackDamage),
            Codec.doubleRange(0.0, Double.MAX_VALUE).optionalFieldOf("attack_speed", 0.0).forGetter(ComposedAttributes::attackSpeed),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("durability", 0).forGetter(ComposedAttributes::durability),
            Codec.doubleRange(0.0, Double.MAX_VALUE).optionalFieldOf("armor", 0.0).forGetter(ComposedAttributes::armor),
            Codec.doubleRange(0.0, Double.MAX_VALUE).optionalFieldOf("armor_toughness", 0.0).forGetter(ComposedAttributes::armorToughness),
            Codec.doubleRange(0.0, Double.MAX_VALUE).optionalFieldOf("knockback_resistance", 0.0).forGetter(ComposedAttributes::knockbackResistance),
            Codec.doubleRange(0.0, Double.MAX_VALUE).optionalFieldOf("move_speed", 0.0).forGetter(ComposedAttributes::moveSpeed),
            Codec.doubleRange(0.0, Double.MAX_VALUE).optionalFieldOf("max_health", 0.0).forGetter(ComposedAttributes::maxHealth),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("ignite_level", 0).forGetter(ComposedAttributes::igniteLevel),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("lifesteal_level", 0).forGetter(ComposedAttributes::lifestealLevel),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("thorns_level", 0).forGetter(ComposedAttributes::thornsLevel),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("slow_level", 0).forGetter(ComposedAttributes::slowLevel),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("heal_level", 0).forGetter(ComposedAttributes::healLevel),
            Codec.doubleRange(0.0, Double.MAX_VALUE).optionalFieldOf("power_score", 0.0).forGetter(ComposedAttributes::powerScore),
            AppearanceData.CODEC.optionalFieldOf("appearance", AppearanceData.empty()).forGetter(ComposedAttributes::appearance),
            ExtraEffects.CODEC.optionalFieldOf("effects", ExtraEffects.empty()).forGetter(ComposedAttributes::extraEffects)
    ).apply(instance, ComposedAttributes::new));

    /** 全零起点。 */
    public static ComposedAttributes empty() {
        return new ComposedAttributes(
                0.0, 0.0, 0,
                0.0, 0.0, 0.0,
                0.0, 0.0,
                0, 0, 0, 0, 0,
                0.0,
                AppearanceData.empty(),
                ExtraEffects.empty()
        );
    }

    /**
     * 合并两条属性：
     * <ul>
     *   <li>数值字段：相加（耐久也相加——多材料补强，符合「材料即零件」）。</li>
     *   <li>特殊效果等级：取大（同种效果不无限叠加，避免数值失控）。</li>
     *   <li>powerScore：相加（强度本身就是累加估算）。</li>
     *   <li>外观字段：相性取并集；主导效果按合并后的等级重新判定。</li>
     * </ul>
     */
    public ComposedAttributes add(ComposedAttributes other) {
        int ignite = Math.max(this.igniteLevel, other.igniteLevel);
        int lifesteal = Math.max(this.lifestealLevel, other.lifestealLevel);
        int thorns = Math.max(this.thornsLevel, other.thornsLevel);
        int slow = Math.max(this.slowLevel, other.slowLevel);
        int heal = Math.max(this.healLevel, other.healLevel);
        String dominantEffect = pickDominantEffect(ignite, lifesteal, thorns, slow, heal);
        ExtraEffects extra = this.extraEffects.max(other.extraEffects);
        return new ComposedAttributes(
                this.attackDamage + other.attackDamage,
                this.attackSpeed + other.attackSpeed,
                this.durability + other.durability,
                this.armor + other.armor,
                this.armorToughness + other.armorToughness,
                this.knockbackResistance + other.knockbackResistance,
                this.moveSpeed + other.moveSpeed,
                this.maxHealth + other.maxHealth,
                ignite, lifesteal, thorns, slow, heal,
                this.powerScore + other.powerScore,
                mergeAppearance(other, dominantEffect),
                extra
        );
    }

    /** 合并 grantedEffects：同效果取大（与特殊效果等级同规则，不无限叠加）。 */
    private static Map<ResourceLocation, Integer> mergeGrantedEffects(
            Map<ResourceLocation, Integer> a, Map<ResourceLocation, Integer> b) {
        if ((a == null || a.isEmpty()) && (b == null || b.isEmpty())) return Map.of();
        Map<ResourceLocation, Integer> merged = new HashMap<>();
        if (a != null) a.forEach((id, lv) -> { if (lv != null && lv > 0) merged.merge(id, lv, Math::max); });
        if (b != null) b.forEach((id, lv) -> { if (lv != null && lv > 0) merged.merge(id, lv, Math::max); });
        return merged.isEmpty() ? Map.of() : Collections.unmodifiableMap(merged);
    }

    /** 任一特殊效果等级 > 0 或带有自由效果即视为带特殊效果。 */
    public boolean hasSpecialEffect() {
        return igniteLevel > 0 || lifestealLevel > 0 || thornsLevel > 0
                || slowLevel > 0 || healLevel > 0
                || effects().anyPositive()
                || !grantedEffects().isEmpty();
    }

    /** 用外部计算好的外观信息覆盖当前外观字段（数值属性保持不变）。 */
    public ComposedAttributes withAppearance(Set<Phase> phases, String dominantEffect, String appearanceKey) {
        return new ComposedAttributes(
                this.attackDamage, this.attackSpeed, this.durability,
                this.armor, this.armorToughness, this.knockbackResistance,
                this.moveSpeed, this.maxHealth,
                this.igniteLevel, this.lifestealLevel, this.thornsLevel, this.slowLevel, this.healLevel,
                this.powerScore,
                new AppearanceData(copyPhases(phases), dominantEffect, appearanceKey),
                this.extraEffects
        );
    }

    /**
     * 追加自由状态效果（effect tag 材料贡献），同效果取大；其余字段保持不变。
     * 供 {@link ForgeComposer} 在组合完成后写入。
     */
    public ComposedAttributes withGrantedEffects(Map<ResourceLocation, Integer> extra) {
        if (extra == null || extra.isEmpty()) return this;
        return new ComposedAttributes(
                this.attackDamage, this.attackSpeed, this.durability,
                this.armor, this.armorToughness, this.knockbackResistance,
                this.moveSpeed, this.maxHealth,
                this.igniteLevel, this.lifestealLevel, this.thornsLevel, this.slowLevel, this.healLevel,
                this.powerScore,
                this.appearance,
                new ExtraEffects(this.effects(), mergeGrantedEffects(this.grantedEffects(), extra),
                        this.drawbacks(), this.extraEffects.reversed())
        );
    }

    /**
     * 写入/合并代价效果（取大合并），其余字段保持不变。
     * 供 {@link ForgeComposer} 在组合完成后按 {@link DrawbackRules} 的结果写入。
     */
    public ComposedAttributes withDrawbacks(DrawbackLevels drawbacks) {
        if (drawbacks == null || !drawbacks.anyPositive()) return this;
        return new ComposedAttributes(
                this.attackDamage, this.attackSpeed, this.durability,
                this.armor, this.armorToughness, this.knockbackResistance,
                this.moveSpeed, this.maxHealth,
                this.igniteLevel, this.lifestealLevel, this.thornsLevel, this.slowLevel, this.healLevel,
                this.powerScore,
                this.appearance,
                new ExtraEffects(this.effects(), this.grantedEffects(), this.drawbacks().max(drawbacks),
                        this.extraEffects.reversed())
        );
    }

    /**
     * 写入反转标志（逆相之核/REVERSE 算子在场）：其余字段保持不变。
     * 供 {@link ForgeComposer} 在组合完成后按材料槽检测结果写入。
     * <p>已反转时再调无副作用（幂等）。</p>
     */
    public ComposedAttributes withReversed() {
        if (this.extraEffects.reversed()) return this;
        return new ComposedAttributes(
                this.attackDamage, this.attackSpeed, this.durability,
                this.armor, this.armorToughness, this.knockbackResistance,
                this.moveSpeed, this.maxHealth,
                this.igniteLevel, this.lifestealLevel, this.thornsLevel, this.slowLevel, this.healLevel,
                this.powerScore,
                this.appearance,
                new ExtraEffects(this.effects(), this.grantedEffects(), this.drawbacks(), true)
        );
    }

    // ============================ 便捷访问 ============================

    /** 固定 13 算子的扩展效果等级（ExtraEffects 子记录的便捷直达）。 */
    public EffectLevels effects() {
        return extraEffects.effects();
    }

    /** 自由状态效果：MobEffect registry id → 等级（ExtraEffects 子记录的便捷直达）。 */
    public Map<ResourceLocation, Integer> grantedEffects() {
        return extraEffects.grantedEffects();
    }

    /** 代价效果等级（ExtraEffects 子记录的便捷直达）；永不为 null。 */
    public DrawbackLevels drawbacks() {
        return extraEffects.drawbacks();
    }

    /**
     * 是否已反转（材料槽含逆相之核/REVERSE 算子）。
     * <ul>
     *   <li>防具：false=携带效果兑现为「接触反伤」（攻击者中效果+反弹伤害）；
     *       true=负面效果兑现为「抗性/免疫」（{@link ReverseEffectTable}）。</li>
     *   <li>武器：true 时伤害型效果极性反转（点燃→冰冻、中毒→攻击者再生、
     *       漂浮→加重迟缓、吸血→反吸血自伤），见 {@link com.qianxiang.combat.CombatEffectHandler}。</li>
     * </ul>
     * 旧存档无该字段，CODEC 默认 false（=接触反伤路线）。
     */
    public boolean isReversed() {
        return extraEffects.reversed();
    }

    public Set<Phase> dominantPhases() {
        return appearance == null ? Set.of() : appearance.dominantPhases();
    }

    public String dominantEffect() {
        return appearance == null ? "none" : appearance.dominantEffect();
    }

    public String appearanceKey() {
        return appearance == null ? "plain" : appearance.appearanceKey();
    }

    // ============================ 外观辅助 ============================

    private AppearanceData mergeAppearance(ComposedAttributes other, String dominantEffect) {
        Set<Phase> phases = unionPhases(this.dominantPhases(), other.dominantPhases());
        String effect = dominantEffect == null || "none".equals(dominantEffect)
                ? (other.appearance() == null ? this.appearance().dominantEffect() : other.appearance().dominantEffect())
                : dominantEffect;
        if (effect == null) effect = "none";
        return new AppearanceData(phases, effect, appearanceKeyFor(effect));
    }

    private static Set<Phase> unionPhases(Set<Phase> a, Set<Phase> b) {
        if ((a == null || a.isEmpty()) && (b == null || b.isEmpty())) return Set.of();
        var set = EnumSet.noneOf(Phase.class);
        if (a != null) set.addAll(a);
        if (b != null) set.addAll(b);
        return Collections.unmodifiableSet(set);
    }

    private static Set<Phase> copyPhases(Set<Phase> phases) {
        if (phases == null || phases.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(EnumSet.copyOf(phases));
    }

    /** 按等级从特殊效果中挑主导效果；无等级时返回 "none"。 */
    static String pickDominantEffect(int ignite, int lifesteal, int thorns, int slow, int heal) {
        if (ignite <= 0 && lifesteal <= 0 && thorns <= 0 && slow <= 0 && heal <= 0) {
            return "none";
        }
        // 优先序：点燃 > 吸血 > 反伤 > 迟缓 > 疗伤
        int max = Math.max(ignite, Math.max(lifesteal, Math.max(thorns, Math.max(slow, heal))));
        if (max == ignite) return "ignite";
        if (max == lifesteal) return "lifesteal";
        if (max == thorns) return "thorns";
        if (max == slow) return "slow";
        return "heal";
    }

    /** 由主导效果得到外观键；用于 tooltip、粒子与光泽映射。 */
    static String appearanceKeyFor(String dominantEffect) {
        return switch (dominantEffect) {
            case "ignite" -> "ember";
            case "lifesteal" -> "blood";
            case "thorns" -> "thorn";
            case "slow" -> "shadow";
            case "heal" -> "life";
            case "mana" -> "arcane";
            case "bone" -> "bone";
            case "defense" -> "bulwark";
            default -> "plain";
        };
    }
}
