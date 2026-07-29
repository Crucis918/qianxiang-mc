package com.qianxiang.cap;

import com.qianxiang.spell.CustomSpell;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 主职业内核的效果结算与设定/转职（数据查询纯函数 + 写入入口）。
 * <p>
 * <b>调平常量（全部集中在类顶部）</b>：
 * 内核元素法术 伤害 ×{@link #CORE_SPELL_DAMAGE}、蓝耗 ×{@link #CORE_SPELL_MANA}；
 * 非内核元素法术 伤害 ×{@link #OFF_SPELL_DAMAGE}、蓝耗 ×{@link #OFF_SPELL_MANA}（可用但弱）；
 * 内核形态武器 近战 ×{@link #CORE_MELEE_DAMAGE}；
 * 非内核形态武器 近战 ×{@link #OFF_MELEE_DAMAGE}（形态读产物 AppearanceData.form，
 * 空串视为非内核）；未设内核（旧存档）全部 ×1.0。
 * </p>
 * <p>
 * 设定/转职：首次免费，之后每次 {@link #RESPEC_EMERALD_COST} 绿宝石；
 * 写入后记相谱一条并全量同步（与洗点同范式）。
 * </p>
 */
public final class ClassCoreHelper {

    private ClassCoreHelper() {}

    // ===================== 调平常量区 =====================

    /** 内核元素法术伤害倍率。 */
    public static final double CORE_SPELL_DAMAGE = 1.15;
    /** 内核元素法术蓝耗倍率。 */
    public static final double CORE_SPELL_MANA = 0.9;
    /** 非内核元素法术伤害倍率。 */
    public static final double OFF_SPELL_DAMAGE = 0.45;
    /** 非内核元素法术蓝耗倍率。 */
    public static final double OFF_SPELL_MANA = 1.25;
    /** 内核形态武器近战伤害倍率。 */
    public static final double CORE_MELEE_DAMAGE = 1.05;
    /** 非内核形态武器近战伤害倍率。 */
    public static final double OFF_MELEE_DAMAGE = 0.6;
    /** 转职费用（绿宝石；首次设定免费）。 */
    public static final int RESPEC_EMERALD_COST = 10;

    // ===================== 效果查询（未设内核全 1.0） =====================

    private static ClassCore coreOf(Player player) {
        if (player == null) return ClassCore.EMPTY;
        return player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA).classCore();
    }

    /** 法术伤害倍率：内核元素 ×1.15 / 非内核 ×0.45 / 未设内核 ×1.0。 */
    public static double spellDamageMult(Player player, CustomSpell spell) {
        ClassCore core = coreOf(player);
        if (!core.isSet() || spell == null) return 1.0;
        return core.hasElement(spell.element()) ? CORE_SPELL_DAMAGE : OFF_SPELL_DAMAGE;
    }

    /** 法术蓝耗倍率：内核元素 ×0.9 / 非内核 ×1.25 / 未设内核 ×1.0。 */
    public static double spellManaCostMult(Player player, CustomSpell spell) {
        ClassCore core = coreOf(player);
        if (!core.isSet() || spell == null) return 1.0;
        return core.hasElement(spell.element()) ? CORE_SPELL_MANA : OFF_SPELL_MANA;
    }

    /** 近战形态倍率：内核形态 ×1.05 / 非内核（含空串形态）×0.6 / 未设内核 ×1.0。 */
    public static double meleeFormMult(Player player, String form) {
        ClassCore core = coreOf(player);
        if (!core.isSet()) return 1.0;
        return core.hasForm(form == null ? "" : form) ? CORE_MELEE_DAMAGE : OFF_MELEE_DAMAGE;
    }

    // ===================== 设定 / 转职 =====================

    /**
     * 设定或更换主职业（首次免费，转职扣 {@link #RESPEC_EMERALD_COST} 绿宝石）。
     * 校验：已开启修行 + 内核合法（两元素互不相同且在白名单、形态在白名单）；
     * 成功后写入 attachment、记相谱、全量同步。
     *
     * @return true = 已写入
     */
    public static boolean setClassCore(ServerPlayer player, ClassCore core) {
        if (player == null || core == null || !core.valid()) {
            return false;
        }
        PlayerProficiencyData d = player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        if (!d.unlocked()) {
            player.displayClientMessage(Component.translatable("qianxiang.classcore.locked"), true);
            return false;
        }
        boolean firstTime = !d.classCore().isSet();
        if (!firstTime && !takeEmeralds(player, RESPEC_EMERALD_COST)) {
            player.displayClientMessage(
                    Component.translatable("qianxiang.classcore.no_emerald"), true);
            return false;
        }
        player.setData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA, d.withClassCore(core));
        var saga = player.getData(QianxiangAttachments.SAGA_DATA);
        player.setData(QianxiangAttachments.SAGA_DATA, saga.withEntry(
                (firstTime ? "§e[主职业] §r立下内核：" : "§e[转职] §r改立内核：")
                        + core.elementA() + " + " + core.elementB() + " · " + core.form()));
        player.displayClientMessage(Component.translatable("qianxiang.classcore.set_done"), true);
        ProficiencyHelper.sync(player);
        return true;
    }

    /** 从背包精确扣除 count 个绿宝石（不足则一颗不扣），返回是否扣成。 */
    private static boolean takeEmeralds(ServerPlayer player, int count) {
        var inv = player.getInventory();
        int have = 0;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            var s = inv.getItem(i);
            if (s.is(net.minecraft.world.item.Items.EMERALD)) have += s.getCount();
        }
        if (have < count) return false;
        int remaining = count;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE && remaining > 0; i++) {
            var s = inv.getItem(i);
            if (!s.is(net.minecraft.world.item.Items.EMERALD)) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            inv.setItem(i, s);
            remaining -= take;
        }
        return true;
    }
}
