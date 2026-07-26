package com.qianxiang.spell;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 千相法术数据类：一个法术的定义。
 * <p>
 * 字段：
 * <ul>
 *   <li>{@code id} —— 法术唯一 id（如 qianxiang:fireball）。</li>
 *   <li>{@code manaCost} —— 施放一次消耗的 mana。</li>
 *   <li>{@code cooldownTicks} —— 冷却 tick，施放后多久才能再施放。</li>
 *   <li>{@code type} —— 效果类型（fireball/heal/shield/slow）。</li>
 * </ul>
 * <p>
 * 所有法术静态注册在 {@link #REGISTRY} 中，可用 {@link #byId(ResourceLocation)} 查询。
 *
 * @deprecated <b>已封存的旧法术系统</b>：法术双轨收敛后，新产物一律走 {@link CustomSpell}
 *             （AI 组合法术），本表只为旧存档物品上的 {@code qianxiang:spell} 组件保留读取路径
 *             （{@link SpellCastHandler} 优先级②）。不要在新代码中引用；四个硬编码法术
 *             已无任何获取途径。
 */
@Deprecated
public record Spell(ResourceLocation id, int manaCost, int cooldownTicks, SpellEffectType type) {

    private static final Map<ResourceLocation, Spell> REGISTRY = new HashMap<>();

    public static final Spell FIREBALL = register("fireball", 15, 40, SpellEffectType.FIREBALL);
    public static final Spell HEAL = register("heal", 20, 80, SpellEffectType.HEAL);
    public static final Spell SHIELD = register("shield", 25, 120, SpellEffectType.SHIELD);
    public static final Spell SLOW = register("slow", 15, 60, SpellEffectType.SLOW);

    private static Spell register(String path, int manaCost, int cooldownTicks, SpellEffectType type) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("qianxiang", path);
        Spell spell = new Spell(id, manaCost, cooldownTicks, type);
        REGISTRY.put(id, spell);
        return spell;
    }

    /** 按 id 查询法术；找不到时返回火球术作为兜底，避免空指针崩溃。 */
    public static Spell byId(ResourceLocation id) {
        return REGISTRY.getOrDefault(id, FIREBALL);
    }

    /** 返回只读的法术注册表。 */
    public static Map<ResourceLocation, Spell> registry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    /** 法术显示名称的翻译键。 */
    public String translationKey() {
        return "spell." + id.getNamespace() + "." + id.getPath();
    }
}
