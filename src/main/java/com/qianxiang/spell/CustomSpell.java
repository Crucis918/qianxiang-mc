package com.qianxiang.spell;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qianxiang.Qianxiang;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自由法术：元素 × 形式 × 效果 × 修饰 的自由组合（自由法术系统的数据核心）。
 * <p>
 * 契约字段：{@code id / element / form / effect / modifiers / manaCost / cooldownTicks / power}，
 * 附 {@link #CODEC} 与 {@link #STREAM_CODEC}，可直接作为 DataComponent 持久化与网络同步
 * （见 {@code qianxiang:custom_spell} / {@code qianxiang:spellbook} 组件）。
 * </p>
 * <h3>三种来源</h3>
 * <ul>
 *   <li>预置法术：{@link #FIREBALL}/{@link #NATURE_HEAL}/{@link #ARCANE_MISSILES}（新法术书的默认内容，
 *       见 {@link SpellBookData#withDefaults()}），登记在 {@link #registry()}。</li>
 *   <li>材料映射/迁移：旧锻造台按材料算子生成的 qianxiang:forged_* 法术
 *       （锻造成法已改为增幅器化，旧产物经 {@code LegacySpellMigration} 迁入已学列表）。</li>
 *   <li>AI 自由法术：AI 响应的 spellJson 经 {@link #fromSpellJson(String, int)} 解析而来。</li>
 * </ul>
 * <h3>spellJson 格式（AI 输出）</h3>
 * <pre>{@code {"element":"fire","form":"projectile","effect":"damage",
 *   "modifiers":["piercing"],"power":3,"name":"炽焰之枪"}}</pre>
 * <ul>
 *   <li>{@code element/form/effect} 缺一不可，且必须落在 {@link #ELEMENTS}/{@link #FORMS}/{@link #EFFECTS}
 *       白名单内，否则整条视为无效 spellJson（返回 null，调用方回退材料映射逻辑）。
 *       spellJson 可能来自客户端回传（不可信），本方法是唯一校验入口。</li>
 *   <li>{@code power} 缺省为 1；最终 power = clamp(max(spellJson.power, 最高材料 tier.ordinal()+1), 1, 10)，
 *       下限由 {@link #fromSpellJson(String, int)} 的 {@code minPower} 参数兑现。</li>
 *   <li>{@code name}（可选）不进入 CustomSpell，用 {@link #extractName(String)} 单独取（截断到
 *       {@link #MAX_CUSTOM_NAME_LENGTH} 字符），写给产物 CUSTOM_NAME。</li>
 * </ul>
 */
public record CustomSpell(ResourceLocation id, String element, String form, String effect,
                          List<String> modifiers, int manaCost, int cooldownTicks, int power) {

    public CustomSpell {
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
    }

    public static final Codec<CustomSpell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(CustomSpell::id),
            Codec.STRING.fieldOf("element").forGetter(CustomSpell::element),
            Codec.STRING.fieldOf("form").forGetter(CustomSpell::form),
            Codec.STRING.fieldOf("effect").forGetter(CustomSpell::effect),
            Codec.STRING.listOf().optionalFieldOf("modifiers", List.of()).forGetter(CustomSpell::modifiers),
            Codec.INT.optionalFieldOf("mana_cost", 0).forGetter(CustomSpell::manaCost),
            Codec.INT.optionalFieldOf("cooldown_ticks", 0).forGetter(CustomSpell::cooldownTicks),
            Codec.INT.optionalFieldOf("power", 1).forGetter(CustomSpell::power)
    ).apply(instance, CustomSpell::new));

    /**
     * 网络编解码：8 个字段超出 {@code StreamCodec.composite} 的重载上限（6 元），
     * 故用手写匿名 StreamCodec，字段顺序与 {@link #CODEC} 一致。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, CustomSpell> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public CustomSpell decode(RegistryFriendlyByteBuf buf) {
            ResourceLocation id = ResourceLocation.STREAM_CODEC.decode(buf);
            String element = ByteBufCodecs.STRING_UTF8.decode(buf);
            String form = ByteBufCodecs.STRING_UTF8.decode(buf);
            String effect = ByteBufCodecs.STRING_UTF8.decode(buf);
            List<String> modifiers = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf);
            int manaCost = ByteBufCodecs.VAR_INT.decode(buf);
            int cooldownTicks = ByteBufCodecs.VAR_INT.decode(buf);
            int power = ByteBufCodecs.VAR_INT.decode(buf);
            return new CustomSpell(id, element, form, effect, modifiers, manaCost, cooldownTicks, power);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, CustomSpell value) {
            ResourceLocation.STREAM_CODEC.encode(buf, value.id());
            ByteBufCodecs.STRING_UTF8.encode(buf, value.element());
            ByteBufCodecs.STRING_UTF8.encode(buf, value.form());
            ByteBufCodecs.STRING_UTF8.encode(buf, value.effect());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, value.modifiers());
            ByteBufCodecs.VAR_INT.encode(buf, value.manaCost());
            ByteBufCodecs.VAR_INT.encode(buf, value.cooldownTicks());
            ByteBufCodecs.VAR_INT.encode(buf, value.power());
        }
    };

    // ============================ 预置法术 ============================

    private static final Map<ResourceLocation, CustomSpell> REGISTRY = new LinkedHashMap<>();

    /** 预置：火球（火/投射/伤害）。 */
    public static final CustomSpell FIREBALL =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "fireball"),
                    "fire", "projectile", "damage", List.of(), 15, 40, 3));
    /** 预置：自然治愈（自然/自身/治疗）。 */
    public static final CustomSpell NATURE_HEAL =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "nature_heal"),
                    "nature", "self", "heal", List.of(), 25, 80, 3));
    /** 预置：奥术飞弹（奥术/投射/伤害，追踪）。 */
    public static final CustomSpell ARCANE_MISSILES =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "arcane_missiles"),
                    "arcane", "projectile", "damage", List.of("homing"), 12, 20, 2));
    /** 预置：冰锥（寒霜/投射/伤害，穿透）。 */
    public static final CustomSpell ICE_SHARD =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "ice_shard"),
                    "frost", "projectile", "damage", List.of("piercing"), 25, 40, 3));
    /** 预置：雷链（雷电/范围/伤害，连锁）。 */
    public static final CustomSpell LIGHTNING_CHAIN =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "lightning_chain"),
                    "lightning", "aoe", "damage", List.of("chain"), 25, 60, 4));
    /** 预置：暗影箭（暗影/投射/伤害）。 */
    public static final CustomSpell SHADOW_BOLT =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "shadow_bolt"),
                    "shadow", "projectile", "damage", List.of(), 15, 40, 3));
    /** 预置：圣光术（神圣/范围/治疗，延展）。 */
    public static final CustomSpell HOLY_LIGHT =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "holy_light"),
                    "holy", "aoe", "heal", List.of("extended"), 30, 120, 4));
    /** 预置：血祭（鲜血/自身/增益，增幅）。 */
    public static final CustomSpell BLOOD_SACRIFICE =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "blood_sacrifice"),
                    "blood", "self", "buff", List.of("amplified"), 30, 200, 4));
    /** 预置：末影闪现（末影/自身/效用）。 */
    public static final CustomSpell ENDER_BLINK =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "ender_blink"),
                    "ender", "self", "utility", List.of(), 20, 60, 1));
    /** 预置：追踪火球（火焰/投射/伤害，追踪+增幅）。 */
    public static final CustomSpell HOMING_FIREBALL =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "homing_fireball"),
                    "fire", "projectile", "damage", List.of("homing", "amplified"), 35, 60, 5));
    /** 预置：寒霜新星（寒霜/范围/减益，延展）。 */
    public static final CustomSpell FROST_NOVA =
            register(new CustomSpell(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "frost_nova"),
                    "frost", "aoe", "debuff", List.of("extended"), 30, 80, 4));

    /** 按 id 查询预置法术；找不到（材料/AI 生成的法术不入表）返回 null。 */
    public static CustomSpell byId(ResourceLocation id) {
        return REGISTRY.get(id);
    }

    /** 允许的取值集合（element/form/effect/modifiers 的白名单，供校验与 UI 使用）。 */
    public static final java.util.Set<String> ELEMENTS = java.util.Set.of(
            "fire", "frost", "lightning", "nature", "shadow", "holy", "blood", "ender", "arcane");
    public static final java.util.Set<String> FORMS = java.util.Set.of(
            "projectile", "self", "aoe", "beam", "touch");
    public static final java.util.Set<String> EFFECTS = java.util.Set.of(
            "damage", "heal", "buff", "debuff", "utility");
    public static final java.util.Set<String> MODIFIERS = java.util.Set.of(
            "homing", "piercing", "extended", "amplified", "chain");

    /** power 上限：manaCost/伤害都随 power 线性放大，上限同时是数值天花板与防溢出闸门。 */
    public static final int MAX_POWER = 10;
    /** 自定义名长度上限（字符）。 */
    public static final int MAX_CUSTOM_NAME_LENGTH = 40;

    private static CustomSpell register(CustomSpell spell) {
        REGISTRY.put(spell.id(), spell);
        return spell;
    }

    /** 预置法术注册表（只读）。材料/AI 生成的法术不入表，数据完整存在物品组件里。 */
    public static Map<ResourceLocation, CustomSpell> registry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    // ============================ 显示名 ============================

    /**
     * 法术显示名：优先翻译键 {@code spell.<namespace>.<path>}（预置/锻造法术可配 lang），
     * 没有翻译时回退「元素·效果」的组合描述，AI 法术也不至于裸显示翻译键。
     */
    public Component displayName() {
        return Component.translatableWithFallback(
                "spell." + id.getNamespace() + "." + id.getPath(),
                element + " " + effect);
    }

    /** 元素显示名（fire/frost/lightning/nature/shadow/holy/blood/ender/arcane），未知词原样回退。 */
    public static Component elementName(String element) {
        return Component.translatableWithFallback("qianxiang.custom_spell.element." + element, element);
    }

    /** 形式显示名（projectile/self/aoe/beam/touch），未知词原样回退。 */
    public static Component formName(String form) {
        return Component.translatableWithFallback("qianxiang.custom_spell.form." + form, form);
    }

    /** 效果显示名（damage/heal/buff/debuff/utility），未知词原样回退。 */
    public static Component effectName(String effect) {
        return Component.translatableWithFallback("qianxiang.custom_spell.effect." + effect, effect);
    }

    /** 修饰显示名（homing/piercing/extended/amplified/chain），未知词原样回退。 */
    public static Component modifierName(String modifier) {
        return Component.translatableWithFallback("qianxiang.custom_spell.modifier." + modifier, modifier);
    }

    /**
     * 修饰词归一化：别名（历史 prompt 用过的 duration/empower 等）折算到引擎消费词，
     * 白名单外的词丢弃（返回 null）。保证组件里存的修饰词一定被 {@code SpellEffectEngine} 认识。
     */
    public static String normalizeModifier(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String m = raw.trim().toLowerCase(java.util.Locale.ROOT);
        m = switch (m) {
            case "duration", "lasting" -> "extended";
            case "empower", "empowered", "overcharge" -> "amplified";
            case "seeking" -> "homing";
            case "pierce", "penetrating" -> "piercing";
            default -> m;
        };
        return MODIFIERS.contains(m) ? m : null;
    }

    // ============================ AI spellJson 解析 ============================

    /**
     * 把 AI 输出的 spellJson 解析为 {@link CustomSpell}——spellJson 的唯一校验入口。
     * <p>
     * spellJson 可能经客户端回传（不可信），故一律按恶意输入处理：
     * element/form/effect 必须落在白名单内，否则整条拒绝；modifiers 逐个过白名单（非法丢弃）；
     * power 夹到 [1, {@link #MAX_POWER}]（manaCost/伤害随 power 线性放大，不设上限会溢出）。
     *
     * @param spellJson AI 响应附带的法术描述 JSON（可空/可空白）
     * @param minPower  强度下限：最高材料 tier.ordinal()+1（契约：power 与材料档位联动）
     * @return 解析结果；JSON 非法、缺 element/form/effect 或任一越出白名单时返回 null（调用方回退材料映射逻辑）
     */
    public static CustomSpell fromSpellJson(String spellJson, int minPower) {
        return fromSpellJson(spellJson, minPower, MAX_POWER);
    }

    /**
     * 同上，但额外接受<b>材料预算上限</b>。
     * <p>
     * 为什么需要它：模组的核心理念是「强度靠材料」，而此前法术轨是漏的——
     * 材料档位只决定 power 的<b>下限</b>，上限是全局常量 {@link #MAX_POWER}=10。
     * 于是拿一堆垃圾材料 + 客户端/AI 报 power=10 完全合法，法术强度与材料脱钩。
     * 现在由调用方按材料最高档位换算预算（炼金台法术铭刻链路复用同一预算口径），
     * power 被夹在 [minPower, maxPower] 内。
     *
     * @param maxPower 本次锻造的材料预算上限（会再夹进 [1, {@link #MAX_POWER}]）
     */
    public static CustomSpell fromSpellJson(String spellJson, int minPower, int maxPower) {
        JsonObject raw = parse(spellJson);
        if (raw == null) return null;
        // 先按数据自带版本号升级 schema（见 SpellJsonMigrations）：
        // 历史数据无 v 字段 → v0，走修饰词旧名折算；
        // 版本高于本模组所知 → 返回 null，宁可拒绝也不按旧规则误解析。
        JsonObject obj = SpellJsonMigrations.migrateToCurrent(raw);
        if (obj == null) return null;
        try {
            String element = optString(obj, "element").toLowerCase(java.util.Locale.ROOT);
            String form = optString(obj, "form").toLowerCase(java.util.Locale.ROOT);
            String effect = optString(obj, "effect").toLowerCase(java.util.Locale.ROOT);
            if (element.isEmpty() || form.isEmpty() || effect.isEmpty()) return null;
            if (!ELEMENTS.contains(element) || !FORMS.contains(form) || !EFFECTS.contains(effect)) {
                return null;
            }

            List<String> modifiers = new java.util.ArrayList<>();
            if (obj.has("modifiers") && obj.get("modifiers").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("modifiers")) {
                    if (el.isJsonPrimitive()) {
                        String m = normalizeModifier(el.getAsString());
                        if (m != null && !modifiers.contains(m)) modifiers.add(m);
                    }
                }
            }

            int power = 1;
            if (obj.has("power") && obj.get("power").isJsonPrimitive()) {
                try {
                    power = obj.get("power").getAsInt();
                } catch (Throwable ignored) {
                    power = 1;
                }
            }
            // 契约：power = clamp(max(spellJson.power, 材料下限), 1, 材料预算上限)
            // 上下限都由材料决定 —— 这是「强度靠材料」在法术轨的兑现。
            int budget = net.minecraft.util.Mth.clamp(maxPower, 1, MAX_POWER);
            int floor = net.minecraft.util.Mth.clamp(minPower, 1, budget);
            power = net.minecraft.util.Mth.clamp(Math.max(power, floor), floor, budget);

            int manaCost = 10 + 5 * power;
            int cooldownTicks = 40 + 20 * power;
            return new CustomSpell(aiId(spellJson), element, form, effect,
                    modifiers, manaCost, cooldownTicks, power);
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] spellJson 解析失败（回退材料映射逻辑）：{}",
                    t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * 取 spellJson 里可选的 {@code name} 字段（AI 给产物起的自定义名），
     * 经 {@link #sanitizeCustomName(String)} 消毒。
     *
     * @return 自定义名；没有或 JSON 非法时返回空串
     */
    public static String extractName(String spellJson) {
        JsonObject obj = parse(spellJson);
        if (obj == null) return "";
        try {
            return sanitizeCustomName(optString(obj, "name"));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 自定义名消毒：去首尾空白并截断到 {@link #MAX_CUSTOM_NAME_LENGTH} 字符
     * （名字可能经客户端回传，不设上限会把超长串写进产物 CUSTOM_NAME 组件）。
     */
    public static String sanitizeCustomName(String raw) {
        if (raw == null) return "";
        String name = raw.trim();
        return name.length() <= MAX_CUSTOM_NAME_LENGTH ? name : name.substring(0, MAX_CUSTOM_NAME_LENGTH);
    }

    /** 容错解析：空串/非 JSON/非对象都返回 null，绝不抛异常。 */
    private static JsonObject parse(String spellJson) {
        if (spellJson == null || spellJson.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(spellJson.trim());
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String optString(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) return "";
        String v = obj.get(key).getAsString();
        return v == null ? "" : v.trim();
    }

    /** AI 法术没有注册表 id，用 spellJson 内容哈希生成一个稳定合法的 id。 */
    private static ResourceLocation aiId(String spellJson) {
        return ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID,
                "ai_" + Integer.toHexString(spellJson.hashCode()));
    }
}
