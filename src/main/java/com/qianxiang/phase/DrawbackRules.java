package com.qianxiang.phase;

import java.util.ArrayList;
import java.util.List;

/**
 * 代价生成规则：「效果好坏平衡」——产物强度超阈值或正面效果过多时，
 * 自动生成 1~2 个代价效果（{@link ComposedAttributes.DrawbackLevels}）。
 *
 * <h3>触发条件与数量</h3>
 * <ul>
 *   <li>{@code powerScore} 在 (8, 12] → 1 个 1 级代价；</li>
 *   <li>{@code powerScore} &gt; 12 → 2 个 1 级代价（映射候选不足 2 种时退化为 1 个 2 级）；</li>
 *   <li>正面效果种类 ≥ 3（经典五效果 + 扩展十三效果 + 自由 grantedEffects）→ 必带至少 1 个代价；</li>
 *   <li>{@code powerScore} ≤ 8 且正面效果 &lt; 3 种 → 无代价（普通产物不受罚）。</li>
 * </ul>
 *
 * <h3>代价映射（按主导效果）</h3>
 * <ul>
 *   <li>吸血 → draining 耗力（以战养战要吃饭）；</li>
 *   <li>高攻（attackDamage ≥ 6 或力量效果）→ heavy 沉重；</li>
 *   <li>全效果（正面效果 ≥ 3 种）→ unstable 不稳 / cursed 诅咒；</li>
 *   <li>兜底序：unstable → heavy → frail → cursed → draining，保证总能给出代价。</li>
 * </ul>
 *
 * <p>只在 {@link ForgeComposer} 组合最终产物时调用一次；
 * 纯翻译层 {@link AttributeScheme}（含 powerScore 试算）不受影响，旧存档解码恒为全零。</p>
 */
public final class DrawbackRules {

    /** powerScore 超过此值（不含）开始带 1 个代价。 */
    public static final double POWER_THRESHOLD_ONE = 8.0;
    /** powerScore 超过此值（不含）带 2 个代价（或 1 个 2 级）。 */
    public static final double POWER_THRESHOLD_TWO = 12.0;
    /** 正面效果种类达到此数必带 1 个代价。 */
    public static final int POSITIVE_KINDS_THRESHOLD = 3;
    /** 视为「高攻」的攻击力阈值（约两份稀有级 EDGE）。 */
    public static final double HIGH_ATTACK_THRESHOLD = 6.0;

    private DrawbackRules() {}

    /**
     * 按最终组合属性生成代价等级；无代价时返回 {@link ComposedAttributes.DrawbackLevels#empty()}。
     */
    public static ComposedAttributes.DrawbackLevels generate(ComposedAttributes attr) {
        if (attr == null) {
            return ComposedAttributes.DrawbackLevels.empty();
        }
        int kinds = positiveEffectKinds(attr);
        double power = attr.powerScore();

        // —— 代价「点数」预算：1 = 一个 1 级；2 = 两个 1 级（或一个 2 级）——
        int budget;
        if (power > POWER_THRESHOLD_TWO) {
            budget = 2;
        } else if (power > POWER_THRESHOLD_ONE) {
            budget = 1;
        } else {
            budget = 0;
        }
        if (kinds >= POSITIVE_KINDS_THRESHOLD && budget < 1) {
            budget = 1; // 全效果堆砌必带代价
        }
        if (budget <= 0) {
            return ComposedAttributes.DrawbackLevels.empty();
        }

        List<String> mapped = mappedCandidates(attr, kinds);
        if (budget == 1 || mapped.size() < 2) {
            // 1 点预算，或映射候选不足 2 种 → 单个代价，等级 = 预算
            return withLevel(mapped.get(0), budget);
        }
        // 2 点预算且映射候选 ≥ 2 种 → 两个不同的 1 级代价
        return withLevel(mapped.get(0), 1).max(withLevel(mapped.get(1), 1));
    }

    /** 正面效果种类数：经典五效果 + 扩展十三效果 + 自由 grantedEffects，逐种计数。 */
    public static int positiveEffectKinds(ComposedAttributes attr) {
        int kinds = 0;
        if (attr.igniteLevel() > 0) kinds++;
        if (attr.lifestealLevel() > 0) kinds++;
        if (attr.thornsLevel() > 0) kinds++;
        if (attr.slowLevel() > 0) kinds++;
        if (attr.healLevel() > 0) kinds++;
        ComposedAttributes.EffectLevels fx = attr.effects();
        if (fx != null) {
            if (fx.poison() > 0) kinds++;
            if (fx.frost() > 0) kinds++;
            if (fx.levitation() > 0) kinds++;
            if (fx.strength() > 0) kinds++;
            if (fx.nightVision() > 0) kinds++;
            if (fx.speedBoost() > 0) kinds++;
            if (fx.jumpBoost() > 0) kinds++;
            if (fx.resistance() > 0) kinds++;
            if (fx.fireResist() > 0) kinds++;
            if (fx.waterBreath() > 0) kinds++;
            if (fx.regeneration() > 0) kinds++;
            if (fx.growth() > 0) kinds++;
            if (fx.areaHarvest() > 0) kinds++;
        }
        var granted = attr.grantedEffects();
        if (granted != null) {
            for (var e : granted.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) kinds++;
            }
        }
        return kinds;
    }

    /**
     * 按主导效果映射的代价候选（有序、去重）。
     * 映射项在前，兜底序补齐在后，保证列表非空。
     */
    private static List<String> mappedCandidates(ComposedAttributes attr, int kinds) {
        List<String> c = new ArrayList<>(4);
        // 吸血 → 耗力
        if (attr.lifestealLevel() > 0) addIfAbsent(c, "draining");
        // 高攻 → 沉重
        if (attr.attackDamage() >= HIGH_ATTACK_THRESHOLD
                || (attr.effects() != null && attr.effects().strength() > 0)) {
            addIfAbsent(c, "heavy");
        }
        // 全效果 → 不稳 / 诅咒
        if (kinds >= POSITIVE_KINDS_THRESHOLD) {
            addIfAbsent(c, "unstable");
            addIfAbsent(c, "cursed");
        }
        // 兜底序：保证任何情况下都能给出代价
        for (String f : new String[]{"unstable", "heavy", "frail", "cursed", "draining"}) {
            addIfAbsent(c, f);
        }
        return c;
    }

    private static void addIfAbsent(List<String> list, String name) {
        if (!list.contains(name)) list.add(name);
    }

    /** 造一个只有指定代价字段为 level、其余为 0 的 DrawbackLevels。 */
    private static ComposedAttributes.DrawbackLevels withLevel(String name, int level) {
        return switch (name) {
            case "frail" -> new ComposedAttributes.DrawbackLevels(level, 0, 0, 0, 0);
            case "heavy" -> new ComposedAttributes.DrawbackLevels(0, level, 0, 0, 0);
            case "draining" -> new ComposedAttributes.DrawbackLevels(0, 0, level, 0, 0);
            case "unstable" -> new ComposedAttributes.DrawbackLevels(0, 0, 0, level, 0);
            default -> new ComposedAttributes.DrawbackLevels(0, 0, 0, 0, level); // cursed
        };
    }
}
