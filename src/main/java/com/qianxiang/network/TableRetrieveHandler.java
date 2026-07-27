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
            retrieve(player, player.containerMenu, payload.slotIndex());
        });
    }

    /**
     * 执行取回（GameTest 可直接调用）。
     *
     * @return true = 该槽有物品且已移给玩家
     */
    public static boolean retrieve(Player player, AbstractContainerMenu menu, int slotIndex) {
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
        if (slotIndex < 0 || slotIndex >= materialSlots) {
            Qianxiang.LOGGER.warn("[Qianxiang] 玩家 {} 请求取回非法材料槽 {}，已忽略",
                    player.getName().getString(), slotIndex);
            return false;
        }
        ItemStack stack = container.getItem(slotIndex);
        if (stack.isEmpty()) {
            return false;
        }
        container.setItem(slotIndex, ItemStack.EMPTY);
        if (!player.getInventory().add(stack)) {
            Containers.dropContents(player.level(), player.blockPosition(),
                    net.minecraft.core.NonNullList.of(ItemStack.EMPTY, stack));
        }
        menu.slotsChanged(container);
        container.setChanged();
        return true;
    }
}
