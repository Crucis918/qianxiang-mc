package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.menu.AlchemyTableMenu;
import com.qianxiang.menu.ForgeTableMenu;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端处理「材料列表点选取回」（{@link TableRetrievePayload}）。
 * <p>
 * 按玩家当前 openMenu 类型分派（锻造台 25 槽 / 炼金台 6 槽），
 * 把该槽物品移到玩家背包（溢出掉到脚下），随后 slotsChanged 重算预览。
 * 每玩家 ~200ms 节流，防改造客户端刷包。
 * </p>
 */
public final class TableRetrieveHandler {

    private TableRetrieveHandler() {}

    /** 同一玩家两次取回的最小间隔。 */
    private static final long RETRIEVE_COOLDOWN_MS = 200L;

    public static void handle(TableRetrievePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player == null || player.level().isClientSide) return;
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "table_retrieve", RETRIEVE_COOLDOWN_MS)) {
                return;
            }
            retrieve(player, player.containerMenu, payload.slotIndex(), payload.all());
        });
    }

    /** 旧三参入口（GameTest/兼容）：取回该槽全部。 */
    public static boolean retrieve(Player player, AbstractContainerMenu menu, int slotIndex) {
        return retrieve(player, menu, slotIndex, true);
    }

    /**
     * 执行取回（GameTest 可直接调用）。
     * slotIndex=-1 且 all=true = 全部取回（复用 {@link com.qianxiang.block.TableInteractions#retrieveAll}）。
     *
     * @return true = 有材料已移给玩家
     */
    public static boolean retrieve(Player player, AbstractContainerMenu menu, int slotIndex,
                                   boolean all) {
        int materialSlots;
        net.minecraft.world.Container container;
        if (menu instanceof ForgeTableMenu forgeMenu) {
            container = forgeMenu.getContainer();
            materialSlots = ForgeTableMenu.MATERIAL_SLOTS;
        } else if (menu instanceof AlchemyTableMenu alchemyMenu) {
            container = alchemyMenu.getContainer();
            materialSlots = AlchemyTableMenu.MATERIAL_SLOTS;
        } else {
            return false;
        }
        // 仪式中取回一律拒绝
        if (container instanceof com.qianxiang.block.RitualHost host && host.ritualState().active()) {
            com.qianxiang.block.RitualLogic.notifyBusy(player);
            return false;
        }
        if (slotIndex == -1) {
            // 「全部取回」按钮：只认 all=true（-1 单取无语义）
            if (!all) return false;
            boolean hadAny = false;
            for (int i = 0; i < materialSlots; i++) {
                if (!container.getItem(i).isEmpty()) { hadAny = true; break; }
            }
            if (!hadAny) return false;
            com.qianxiang.block.TableInteractions.retrieveAll(container, materialSlots,
                    player, player.level(), player.blockPosition());
            menu.slotsChanged(container);
            container.setChanged();
            return true;
        }
        if (slotIndex < 0 || slotIndex >= materialSlots) {
            Qianxiang.LOGGER.warn("[Qianxiang] 玩家 {} 请求取回非法材料槽 {}，已忽略",
                    player.getName().getString(), slotIndex);
            return false;
        }
        ItemStack stack = container.getItem(slotIndex);
        if (stack.isEmpty()) {
            return false;
        }
        // all=false 取 1 个（split 守恒），all=true 取整槽
        ItemStack taken = all ? stack.copy() : stack.split(1);
        if (all) {
            container.setItem(slotIndex, ItemStack.EMPTY);
        } else if (stack.isEmpty()) {
            container.setItem(slotIndex, ItemStack.EMPTY);
        } else {
            container.setChanged();
        }
        if (!player.getInventory().add(taken)) {
            Containers.dropContents(player.level(), player.blockPosition(),
                    net.minecraft.core.NonNullList.of(ItemStack.EMPTY, taken));
        }
        menu.slotsChanged(container);
        container.setChanged();
        return true;
    }
}
