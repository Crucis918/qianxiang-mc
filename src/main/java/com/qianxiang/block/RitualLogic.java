package com.qianxiang.block;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 合成仪式状态机推进（锻造台/炼金台共用逻辑，宿主能力见 {@link RitualHost}）。
 * <p>
 * 流程：{@link #startRitual}（校验 → 材料入 ritualInputs 不销毁 → pendingResult 记录产物 →
 * 关 GUI → FLYING）→ tick 推进 FLYING(30t) → FORMING(50t) → DONE
 * （此刻才真正消耗 ritualInputs，displayResult=产物，记相谱）→ 空手拾取回 NONE。
 * </p>
 */
public final class RitualLogic {

    private RitualLogic() {}

    /** 材料飞入阶段时长（tick）。 */
    public static final int FLYING_TICKS = 30;
    /** 创作成型阶段时长（tick）。 */
    public static final int FORMING_TICKS = 50;

    /**
     * 触发仪式。三个入口（GUI「开始创作」按钮 / 产物槽点击 / 空手右键有产物）都汇到这里。
     *
     * @return true = 仪式启动；false = 仪式中/无有效产物（仪式中会发忙提示）
     */
    public static boolean startRitual(RitualHost host, ServerPlayer player) {
        if (host.ritualState().active()) {
            notifyBusy(player);
            return false;
        }
        ItemStack result = host.composedResult();
        if (result.isEmpty()) {
            return false;
        }
        // 材料槽移入 ritualInputs（不立即销毁，挖台照常掉落）；产物副本入 pendingResult
        host.collectInputsToRitual();
        host.setPendingResult(result.copy());
        host.clearComposedResult();
        host.setRitualOwner(player.getUUID());
        host.setRitualProgress(0);
        host.setRitualState(RitualState.FLYING);
        host.onRitualStarted(player, result);
        // 服务端关 GUI：仪式开始后玩家看台上的 VFX，不再交互
        player.closeContainer();
        host.markRitualDirty();
        return true;
    }

    /** BE tickServer 每 tick 调用：推进状态机。 */
    public static void tick(RitualHost host) {
        // 时长修正：adept 节点仪式提速 25%（时长 ×0.75；未分配/找不到玩家 = 1.0）
        double speedMult = 1.0;
        net.minecraft.world.entity.player.Player owner = findOwner(host);
        if (owner != null) {
            speedMult = com.qianxiang.cap.ProficiencyHelper.ritualSpeedMult(owner);
        }
        int flyingTicks = Math.max(1, (int) Math.round(FLYING_TICKS * speedMult));
        int formingTicks = Math.max(1, (int) Math.round(FORMING_TICKS * speedMult));

        switch (host.ritualState()) {
            case FLYING -> {
                if (host.ritualProgress() + 1 >= flyingTicks) {
                    host.setRitualProgress(0);
                    host.setRitualState(RitualState.FORMING);
                    host.markRitualDirty();
                } else {
                    host.setRitualProgress(host.ritualProgress() + 1);
                }
            }
            case FORMING -> {
                if (host.ritualProgress() + 1 >= formingTicks) {
                    // DONE：此刻才真正消耗材料（ritualInputs 清空）；产物写入 displayResult
                    // thrift 节点：按概率随机返还一件被消耗的材料（先拷贝再清空，严格守恒）
                    java.util.List<ItemStack> consumed = java.util.List.copyOf(host.ritualInputs());
                    host.ritualInputs().clear();
                    ItemStack result = host.pendingResult().copy();
                    host.setPendingResult(ItemStack.EMPTY);
                    host.setDisplayResultFromRitual(result);
                    host.setRitualProgress(0);
                    host.setRitualState(RitualState.DONE);
                    // 仪式完成即算创作成功（拾取只是拿取）：记相谱，与 onTake 同一口径
                    net.minecraft.server.level.ServerPlayer ownerPlayer = findOwner(host);
                    host.recordRitualCompleted(ownerPlayer, result);
                    // thrift 返还：锻造 6%（thrift2 12%）返一件材料到背包（守恒：返的还是那件）
                    if (ownerPlayer != null && !consumed.isEmpty()) {
                        double chance = com.qianxiang.cap.ProficiencyHelper.forgeRefundChance(ownerPlayer);
                        if (chance > 0 && host.ritualLevel().getRandom().nextDouble() < chance) {
                            ItemStack refund = consumed.get(
                                    host.ritualLevel().getRandom().nextInt(consumed.size())).copy();
                            refund.setCount(1);
                            if (!ownerPlayer.getInventory().add(refund)) {
                                ownerPlayer.drop(refund, false);
                            }
                        }
                    }
                    host.markRitualDirty();
                } else {
                    host.setRitualProgress(host.ritualProgress() + 1);
                }
            }
            default -> { }
        }
    }

    /** 仪式发起者（可能已下线/未注册，返回 null 由 recordRitualCompleted 容忍）。 */
    private static ServerPlayer findOwner(RitualHost host) {
        if (host.ritualOwner() == null || host.ritualLevel() == null
                || host.ritualLevel().getServer() == null) {
            return null;
        }
        return host.ritualLevel().getServer().getPlayerList().getPlayer(host.ritualOwner());
    }

    /** 仪式中忙提示（投料/取回/再次触发被拒时用，actionbar）。 */
    public static void notifyBusy(Player player) {
        player.displayClientMessage(Component.translatable("qianxiang.table.ritual_busy"), true);
    }
}
