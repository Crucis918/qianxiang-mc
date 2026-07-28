package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.menu.AlchemyTableMenu;
import com.qianxiang.menu.ForgeTableMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端处理「GUI 背包左键投入」（{@link TableInsertPayload}）。
 * <p>
 * 按玩家当前 openMenu 类型分派（锻造台 25 槽 / 炼金台 6 槽），
 * 校验槽位属于该菜单的玩家背包区间（menu.slots 下标 RESULT_SLOT+1 起 36 格）、
 * 材料区未满、非仪式中；投入复用 {@link com.qianxiang.block.TableInteractions#insert}
 * （填充序优先空槽、物品守恒 split）。每玩家 ~200ms 节流，防改造客户端刷包。
 * </p>
 */
public final class TableInsertHandler {

    private TableInsertHandler() {}

    /** 同一玩家两次投入的最小间隔。 */
    private static final long INSERT_COOLDOWN_MS = 200L;

    public static void handle(TableInsertPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player == null || player.level().isClientSide) return;
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "table_insert", INSERT_COOLDOWN_MS)) {
                return;
            }
            insert(player, player.containerMenu, payload.slotIndex(), payload.wholeStack());
        });
    }

    /**
     * 执行投入（GameTest 可直接调用）。
     *
     * @return true = 至少投入 1 个（材料区满/仪式中/非法槽位均 false 且不消耗）
     */
    public static boolean insert(Player player, AbstractContainerMenu menu, int slotIndex,
                                 boolean wholeStack) {
        net.minecraft.world.Container container;
        int[] fillOrder;
        int invStart;
        if (menu instanceof ForgeTableMenu forgeMenu) {
            container = forgeMenu.getContainer();
            fillOrder = ForgeTableMenu.SLOT_FILL_ORDER;
            invStart = ForgeTableMenu.RESULT_SLOT + 1;
        } else if (menu instanceof AlchemyTableMenu alchemyMenu) {
            container = alchemyMenu.getContainer();
            fillOrder = AlchemyTableMenu.SLOT_FILL_ORDER;
            invStart = AlchemyTableMenu.RESULT_SLOT + 1;
        } else {
            return false;
        }
        // 槽位必须是本菜单的玩家背包区（含快捷栏 36 格）
        if (slotIndex < invStart || slotIndex >= invStart + 36) {
            Qianxiang.LOGGER.warn("[Qianxiang] 玩家 {} 请求从非法槽位 {} 投入，已忽略",
                    player.getName().getString(), slotIndex);
            return false;
        }
        // 仪式中投入一律拒绝
        if (container instanceof com.qianxiang.block.RitualHost host && host.ritualState().active()) {
            com.qianxiang.block.RitualLogic.notifyBusy(player);
            return false;
        }
        Slot slot = menu.getSlot(slotIndex);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return false;
        }
        int moved = com.qianxiang.block.TableInteractions.insert(container, fillOrder, stack, wholeStack);
        if (moved <= 0) {
            player.displayClientMessage(Component.translatable("qianxiang.table.full"), true);
            return false;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        menu.slotsChanged(container);
        container.setChanged();
        return true;
    }
}
