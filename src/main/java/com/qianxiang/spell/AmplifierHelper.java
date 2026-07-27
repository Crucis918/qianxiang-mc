package com.qianxiang.spell;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.cap.PlayerSpellData;
import com.qianxiang.phase.ComposedAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 法术增幅器结算：法杖/魔法书「增幅器化」后，主手 + 副手物品的
 * {@link ComposedAttributes} 提供法术伤害加成（%）与法力上限加成（+X），
 * 两手同时生效、效果叠加（一手杖一手书双倍收益）。
 * <p>物品没有 {@code composed_attributes} 组件一律按 0 计，旧产物天然兼容。</p>
 */
public final class AmplifierHelper {

    private AmplifierHelper() {}

    /**
     * 法术伤害倍率：{@code 1 + Σ(主手+副手 spellPowerPercent) / 100}。
     * 供施法链路传给 {@link SpellEffectEngine#cast(CustomSpell, ServerPlayer, float)}。
     */
    public static double damageMultiplier(Player player) {
        return 1.0 + spellPowerPercent(player) / 100.0;
    }

    /** 主手+副手物品的法术伤害加成 % 合计（无组件按 0）。 */
    public static double spellPowerPercent(Player player) {
        if (player == null) return 0.0;
        return amplifierOf(player.getMainHandItem()).spellPowerPercent
                + amplifierOf(player.getOffhandItem()).spellPowerPercent;
    }

    /** 主手+副手物品的法力上限加成合计（无组件按 0）。 */
    public static int manaBonus(Player player) {
        if (player == null) return 0;
        return amplifierOf(player.getMainHandItem()).manaBonus
                + amplifierOf(player.getOffhandItem()).manaBonus;
    }

    /** 有效法力上限：{@code data.maxMana() + manaBonus(player)}（下限 1，防御负值配置）。 */
    public static int effectiveMaxMana(Player player, PlayerSpellData data) {
        int base = data == null ? PlayerSpellData.DEFAULT_MAX_MANA : data.maxMana();
        return Math.max(1, base + manaBonus(player));
    }

    private record Amp(double spellPowerPercent, int manaBonus) {
        static final Amp ZERO = new Amp(0.0, 0);
    }

    private static Amp amplifierOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Amp.ZERO;
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null) return Amp.ZERO;
        if (attr.spellPowerPercent() <= 0 && attr.manaBonus() <= 0) return Amp.ZERO;
        return new Amp(attr.spellPowerPercent(), attr.manaBonus());
    }
}
