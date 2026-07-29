package com.qianxiang.phase;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.QianxiangItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 传奇装备升级树：资格判定、喂料折算、升级组合（纯逻辑，无副作用）。
 * <p>
 * <b>资格</b>：核心槽（下标 12）的产物带 {@code COMPOSED_ATTRIBUTES} 且
 * {@code powerScore} ≥ {@link #LEGENDARY_POWER_THRESHOLD}（与 ForgeTableMenu 的
 * forge_legendary 门槛同值）即「传奇装备」，锻造台进入升级模式（menu 在
 * slotsChanged 里分流到本类，普通材料组合不受影响）。
 * </p>
 * <p>
 * <b>防复利方案（显式基底存储，弃用「现值/(1+0.06L)」反推）</b>：
 * L0 主属性基底在<b>锻造完成时</b>由 {@link ForgeComposer} 写入
 * {@code AppearanceData.base*} 字段（随组件持久化，optionalFieldOf 兼容旧存档）；
 * 升级结果 = 基底 × (1 + 0.06 × 新等级)，每一次升级都从<b>同一份 L0 基底</b>重算，
 * 乘法永不叠加在上一级的结果上（L2 == L0×1.12，不是 L1×1.06）。
 * 旧存档/外部构造的产物基底字段为 0：首次升级时以当前值补记为基底——
 * 对从未升过级的装备（level==0），「当前值」就是 L0 原值，语义自洽。
 * 反推方案在浮点连除后会漂移（1.12/1.06≈1.0566≠1.06），故弃用。
 * </p>
 * <p>
 * <b>升级效果（每级 +6%，按类别的主属性）</b>：武器/工具=attackDamage（攻速不乘）、
 * 防具=armor+armorToughness、杖书=spellPowerPercent+manaBonus——统一实现为
 * 五个字段各自从自己的基底缩放（未用字段基底为 0 自然不生效），无需类别判断。
 * </p>
 */
public final class UpgradeRules {

    private UpgradeRules() {}

    /** 传奇资格阈值（与 ForgeTableMenu 的 forge_legendary 门槛同源同值）。 */
    public static final double LEGENDARY_POWER_THRESHOLD = 12.0;
    /** 每级主属性加成（乘法，基底 × (1 + PER_LEVEL_BONUS × level)）。 */
    public static final double PER_LEVEL_BONUS = 0.06;
    /** 封顶等级。 */
    public static final int MAX_LEVEL = 5;
    /** 累计经验阈值：L2/L3/L4/L5（L1 = 任意喂料）。 */
    private static final int XP_L2 = 10, XP_L3 = 25, XP_L4 = 45, XP_L5 = 70;

    /** 累计经验 → 等级（0=未升级；xp<10 最高 L1）。 */
    public static int levelForXp(int xp) {
        if (xp >= XP_L5) return 5;
        if (xp >= XP_L4) return 4;
        if (xp >= XP_L3) return 3;
        if (xp >= XP_L2) return 2;
        if (xp >= 1) return 1;
        return 0;
    }

    /**
     * 喂一件料的升级经验：warden_core=8，否则按强度档 COMMON 1 / RARE 2 / EPIC 3 / LEGENDARY 5。
     * 档位来源与 ForgeComposer 同源（PhaseData 组件 → 数据包定义 → 默认 COMMON），
     * 不走 PhaseFunctionResolver（并行改造区）。
     */
    public static int feedXp(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.is(QianxiangItems.WARDEN_CORE.get())) return 8;
        PhaseTier tier = PhaseTier.COMMON;
        var pd = stack.get(QianxiangDataComponents.PHASE_DATA.get());
        if (pd != null && pd.tier() != null) {
            tier = pd.tier();
        } else {
            var dataPd = PhaseMaterialRegistry.phaseData(stack.getItem());
            if (dataPd != null && dataPd.tier() != null) tier = dataPd.tier();
        }
        return switch (tier) {
            case COMMON -> 1;
            case RARE -> 2;
            case EPIC -> 3;
            case LEGENDARY -> 5;
        };
    }

    /** 该物品是否是可升级的传奇装备（带组件的千相产物且 powerScore 过阈）。 */
    public static boolean isUpgradeable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null || attr.powerScore() < LEGENDARY_POWER_THRESHOLD) return false;
        // 只认千相产物（材料不带 COMPOSED_ATTRIBUTES，双保险限命名空间）
        return "qianxiang".equals(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
    }

    /**
     * tooltip「传奇 +N」行（各产物 Item 的 appendHoverText 调用）：有等级才显示，
     * L5 显示「传奇 MAX」（金）。lang 键在 docs/lang-pending-upgrade.json 待合并，
     * 缺键期用 translatableWithFallback 的中文兜底。
     */
    public static void appendLegendaryLine(ItemStack stack,
                                           java.util.List<net.minecraft.network.chat.Component> tooltip) {
        if (stack == null || stack.isEmpty()) return;
        var attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null) return;
        int level = attr.upgradeLevel();
        if (level <= 0) return;
        tooltip.add((level >= MAX_LEVEL
                ? net.minecraft.network.chat.Component.translatableWithFallback(
                        "qianxiang.upgrade.tooltip.max", "传奇 MAX")
                : net.minecraft.network.chat.Component.translatableWithFallback(
                        "qianxiang.upgrade.tooltip.level", "传奇 +%d", level))
                .withStyle(net.minecraft.ChatFormatting.GOLD));
    }

    /**
     * 升级组合：核心槽传奇装备 + 周围喂料 → 升级后的产物副本。
     * 喂料零经验或不涨级（经验不足以跨阈）返回 {@link ForgeComposer.Composition#empty()}——
     * 结果槽空 = 仪式不可触发 = 料不会被白吞。
     * 仪式流程零改动：升级产物进结果槽即 pendingResult，核心旧装备随材料一起
     * FLYING 消耗（旧的不在，新的留下——这就是「升级」）。
     */
    public static ForgeComposer.Composition composeUpgrade(ItemStack coreItem, List<ItemStack> feeds) {
        if (!isUpgradeable(coreItem)) return ForgeComposer.Composition.empty();
        var attr = coreItem.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());

        int gained = 0;
        for (ItemStack feed : feeds) {
            gained += feedXp(feed);
        }
        if (gained <= 0) return ForgeComposer.Composition.empty();

        int newXp = attr.upgradeXp() + gained;
        int newLevel = Math.min(MAX_LEVEL, levelForXp(newXp));
        if (newLevel <= attr.upgradeLevel()) {
            return ForgeComposer.Composition.empty(); // 未跨阈不涨级：不产出，料不吞
        }

        // L0 基底：已记录优先；全零（旧存档/外部构造）以当前值补记——
        // 未升过级的装备「当前值」就是 L0 原值。
        double baseAtk = attr.baseAttackDamage() != 0.0 ? attr.baseAttackDamage() : attr.attackDamage();
        double baseArmor = attr.baseArmor() != 0.0 ? attr.baseArmor() : attr.armor();
        double baseTough = attr.baseArmorToughness() != 0.0
                ? attr.baseArmorToughness() : attr.armorToughness();
        double baseSpell = attr.baseSpellPowerPercent() != 0.0
                ? attr.baseSpellPowerPercent() : attr.spellPowerPercent();
        double baseMana = attr.baseManaBonus() != 0.0 ? attr.baseManaBonus() : attr.manaBonus();

        double mult = 1.0 + PER_LEVEL_BONUS * newLevel;
        var upgraded = attr
                .withPrimaryStats(baseAtk * mult, baseArmor * mult, baseTough * mult,
                        baseSpell * mult, (int) Math.round(baseMana * mult))
                .withUpgradeProgress(newLevel, newXp, baseAtk, baseArmor, baseTough, baseSpell, baseMana);

        ItemStack out = coreItem.copy();
        out.setCount(1);
        out.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(), upgraded);
        // 手持修饰符按新数值重写；防具（护甲槽动态供给）与法术书（纯增幅器）不写——
        // 与 ForgeComposer 正常产物的跳过口径一致。
        if (!(out.getItem() instanceof com.qianxiang.item.QianxiangArmorItem)
                && !out.is(QianxiangItems.SPELL_BOOK.get())) {
            AttributeScheme.applyModifiersToStack(out, upgraded);
        }
        return new ForgeComposer.Composition(out, upgraded);
    }
}
