package com.qianxiang.phase;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * 反转材料表：同一材料的「攻击效果 → 防具抗性」映射。
 * <p>
 * 核心立意：负面材料不该只能做武器。凋零玫瑰做武器 = 攻击附加凋零；
 * 同一块材料缝进防具 = 穿戴者免疫凋零。本表是唯一口径来源，
 * {@link com.qianxiang.handler.ArmorPassiveHandler}（兑现）、
 * {@link com.qianxiang.client.MaterialCardHelper}（性质卡）、
 * {@link com.qianxiang.item.QianxiangArmorItem}（tooltip）都读这里。
 * </p>
 *
 * <h3>两种反转方式</h3>
 * <ul>
 *   <li><b>免疫（immunity）</b>：防具含该攻击效果时，穿戴处理器不再把负面效果
 *       施加给玩家自己，而是每刷新周期主动 {@code removeEffect} 该效果——
 *       凋零/中毒/迟缓/虚弱/失明/饥饿走这条。</li>
 *   <li><b>反制（counter）</b>：除免疫外，每刷新周期给玩家续杯一个对应的正面效果——
 *       漂浮 → 缓降（slow_falling）；点燃（固定字段 igniteLevel，非 MobEffect）→ 抗火
 *       （fire_resistance，见 {@link #IGNITE_COUNTER}）。</li>
 * </ul>
 *
 * <h3>键的口径</h3>
 * 表键 = MobEffect 注册 id（{@code minecraft:wither} 等），与
 * {@link ComposedAttributes#grantedEffects()} 的键一致。ignite 不是 MobEffect，
 * 不占用表键，由穿戴处理器单独按 igniteLevel 汇总兑现。
 */
public final class ReverseEffectTable {

    /** 一种反转：counterEffectId 非空时续杯该正面效果；immunity 为真时移除同名负面效果。 */
    public record Reversal(ResourceLocation counterEffectId, boolean immunity) {

        /** 纯免疫（移除负面效果，不附加正面效果）。 */
        static Reversal ofImmunity() {
            return new Reversal(null, true);
        }

        /** 免疫 + 反制正面效果。 */
        static Reversal ofCounter(String counterPath) {
            return new Reversal(ResourceLocation.withDefaultNamespace(counterPath), true);
        }
    }

    /** 点燃（igniteLevel）的反制正面效果：抗火。 */
    public static final ResourceLocation IGNITE_COUNTER =
            ResourceLocation.withDefaultNamespace("fire_resistance");

    /** 抗性本地化键前缀：完整键 = {@code qianxiang.reverse.<攻击效果 path>}。 */
    public static final String LANG_KEY_PREFIX = "qianxiang.reverse.";

    private static final Map<ResourceLocation, Reversal> TABLE = Map.of(
            ResourceLocation.withDefaultNamespace("wither"), Reversal.ofImmunity(),
            ResourceLocation.withDefaultNamespace("poison"), Reversal.ofImmunity(),
            ResourceLocation.withDefaultNamespace("slowness"), Reversal.ofImmunity(),
            ResourceLocation.withDefaultNamespace("weakness"), Reversal.ofImmunity(),
            ResourceLocation.withDefaultNamespace("blindness"), Reversal.ofImmunity(),
            ResourceLocation.withDefaultNamespace("hunger"), Reversal.ofImmunity(),
            ResourceLocation.withDefaultNamespace("levitation"), Reversal.ofCounter("slow_falling")
    );

    private ReverseEffectTable() {}

    /** 查反转方式；该攻击效果无反转（如正面效果）时返回 null。 */
    public static Reversal get(ResourceLocation attackEffectId) {
        return attackEffectId == null ? null : TABLE.get(attackEffectId);
    }

    /** 该攻击效果是否有防具反转用途。 */
    public static boolean hasReversal(ResourceLocation attackEffectId) {
        return get(attackEffectId) != null;
    }

    /** 抗性显示名的本地化键（tooltip/性质卡用），如 {@code qianxiang.reverse.wither}。 */
    public static String resistanceNameKey(ResourceLocation attackEffectId) {
        String path = attackEffectId == null ? "unknown" : attackEffectId.getPath();
        return LANG_KEY_PREFIX + path;
    }
}
