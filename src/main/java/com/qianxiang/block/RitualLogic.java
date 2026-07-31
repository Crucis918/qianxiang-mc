package com.qianxiang.block;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 合成仪式状态机推进（锻造台/炼金台共用逻辑，宿主能力见 {@link RitualHost}）。
 * <p>
 * 流程：{@link #startRitual}（校验 → 材料入 ritualInputs 不销毁 → pendingResult 记录产物 →
 * 关 GUI → FLYING）→ tick 推进 FLYING(30t) → FORMING(50t) → DONE
 * （此刻才真正消耗 ritualInputs，displayResult=产物，记相谱）→ 空手拾取回 NONE。
 * </p>
 * <p>
 * 仪式进行中（FLYING/FORMING）的右键拦截在 {@link #onRightClickBlock}：
 * 事件在方块 use 逻辑之前触发，取消后不再走到「开 GUI/投料/取回」任一分支，
 * 改为 actionbar 进度提示；DONE 不拦截（空手拾取产物的通路）。
 * </p>
 */
@EventBusSubscriber(modid = com.qianxiang.Qianxiang.MOD_ID)
public final class RitualLogic {

    private RitualLogic() {}

    /** 材料飞入阶段时长（tick）。 */
    public static final int FLYING_TICKS = 30;
    /** 创作成型阶段时长（tick）。 */
    public static final int FORMING_TICKS = 50;

    /**
     * 触发仪式。两个入口（GUI「开始创作」按钮 / 产物槽点击）都汇到这里；
     * 空手右键方块<b>不</b>触发仪式（开 GUI 取材料优先，见两台块的 useWithoutItem）。
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

    /**
     * 仪式进度百分比（0-100）：FLYING({@link #FLYING_TICKS}t) 与 FORMING({@link #FORMING_TICKS}t)
     * 合并折算；NONE/DONE 返回 -1（无进行中的仪式）。
     * <p>按基础时长估算，不含 adept 提速节点（客户端拿不到提速系数，提示性质够用）。</p>
     */
    public static int progressPercent(RitualHost host) {
        return switch (host.ritualState()) {
            case FLYING -> host.ritualProgress() * 100 / (FLYING_TICKS + FORMING_TICKS);
            case FORMING -> (FLYING_TICKS + host.ritualProgress()) * 100 / (FLYING_TICKS + FORMING_TICKS);
            default -> -1;
        };
    }

    /**
     * 仪式进行中（FLYING/FORMING）右键台子：取消交互，actionbar 报进度。
     * <p>
     * 此前玩家右键会看到「稍安勿躁」一句定性提示，且 DONE 之外的 busy 分支
     * 与开 GUI 分支并存容易困惑。本事件在方块 use 逻辑<b>之前</b>触发
     * （客户端 {@code MultiPlayerGameMode.performUseItemOn} 与服务端数据包入口
     * 都先过它），取消后端到端不再触碰开 GUI/投料/取回分支；
     * 方块类里原有的 active 拒绝分支留作兜底（绕过事件的路径，如自动化直接调用）。
     * <br>
     * 双端同码：客户端取消后本地即时提示（BE 的仪式状态/进度经 update tag 同步，
     * 与 BER 同一份数据源），服务端取消后发包提示——内容一致，后到的覆盖先到的。
     * DONE 明确放行：空手右键拾取产物是仪式收尾的唯一通路。
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof RitualHost host)) return;
        if (host.ritualState() != RitualState.FLYING && host.ritualState() != RitualState.FORMING) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.getEntity().displayClientMessage(Component.translatable(
                "qianxiang.table.ritual_progress", progressPercent(host) + "%"), true);
    }
}
