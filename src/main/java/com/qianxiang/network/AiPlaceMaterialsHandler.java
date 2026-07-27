package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.menu.ForgeTableMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端处理「把 AI 推荐材料放入锻造台材料槽」的请求。
 */
public final class AiPlaceMaterialsHandler {

    private AiPlaceMaterialsHandler() {}

    public static void handle(AiPlaceMaterialsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player == null) return;

            // 锻造台 / 炼金台共用放料逻辑：按菜单类型取容器与材料槽数。
            AbstractContainerMenu menu = player.containerMenu;
            net.minecraft.world.Container container;
            int materialSlots;
            String noMaterialsKey;
            String slotsFullKey;
            if (menu instanceof ForgeTableMenu forgeMenu) {
                container = forgeMenu.getContainer();
                materialSlots = ForgeTableMenu.MATERIAL_SLOTS;
                noMaterialsKey = "qianxiang.forge_table.msg.no_materials";
                slotsFullKey = "qianxiang.forge_table.msg.slots_full";
            } else if (menu instanceof com.qianxiang.menu.AlchemyTableMenu alchemyMenu) {
                container = alchemyMenu.getContainer();
                materialSlots = com.qianxiang.menu.AlchemyTableMenu.MATERIAL_SLOTS;
                noMaterialsKey = "qianxiang.alchemy_table.msg.no_materials";
                slotsFullKey = "qianxiang.alchemy_table.msg.slots_full";
            } else {
                Qianxiang.LOGGER.warn("[Qianxiang] 玩家发送 AI 放料请求时未打开功能台菜单");
                return;
            }

            List<Item> wanted = new ArrayList<>();
            for (String name : payload.materialNames()) {
                ResourceLocation id = ResourceLocation.tryParse(name);
                if (id == null) continue;
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item != null && BuiltInRegistries.ITEM.getKey(item).equals(id)) {
                    wanted.add(item);
                }
            }

            if (wanted.isEmpty()) {
                player.displayClientMessage(Component.translatable(noMaterialsKey), false);
                return;
            }

            boolean anyPlaced = false;
            for (Item item : wanted) {
                int invSlot = findInInventory(player, item);
                if (invSlot < 0) continue;

                int materialSlot = findMaterialSlot(container, item, materialSlots);
                if (materialSlot < 0) {
                    // 材料槽已满，提示背包高亮（由客户端自己画）
                    player.displayClientMessage(Component.translatable(slotsFullKey), false);
                    continue;
                }

                ItemStack invStack = player.getInventory().getItem(invSlot);
                if (invStack.isEmpty()) continue;

                ItemStack move = invStack.split(1);
                ItemStack existing = container.getItem(materialSlot);
                if (existing.isEmpty()) {
                    container.setItem(materialSlot, move);
                } else if (ItemStack.isSameItemSameComponents(existing, move)
                        && existing.getCount() < existing.getMaxStackSize()) {
                    existing.grow(1);
                    container.setItem(materialSlot, existing);
                } else {
                    invStack.grow(1); // 不可堆叠，撤销
                    continue;
                }
                anyPlaced = true;
            }

            if (anyPlaced) {
                // 闭合「建议 → 采纳」链路：玩家真的把 AI 推荐的材料放上台了。
                // 这是判断 AI 质量的唯一客观信号（调用次数说明不了任何问题）。
                com.qianxiang.ai.AIGateway.logAdoption(
                        com.qianxiang.ai.AIGateway.currentRequestId(),
                        payload.materialNames(),
                        player.getUUID().toString());
                menu.slotsChanged(container);
                container.setChanged();
                player.getInventory().setChanged();
            }
        });
    }

    /** 只扫主背包 36 格：getContainerSize() 是 41，会把身上穿的盔甲/副手也当材料取走。 */
    private static int findInInventory(Player player, Item item) {
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(item)) return i;
        }
        return -1;
    }

    /** 测试入口：直接对容器做槽位选择（GameTest 无需构造完整菜单）。 */
    public static int findMaterialSlotForTest(net.minecraft.world.Container container, Item item) {
        return findMaterialSlot(container, item, ForgeTableMenu.MATERIAL_SLOTS);
    }

    /**
     * 找可放入指定物品的材料槽：优先空槽，其次可堆叠的同种物品槽。
     * 若找不到返回 -1（材料槽已满）。
     */
    private static int findMaterialSlot(net.minecraft.world.Container container, Item item, int materialSlots) {
        // 必须真·优先空槽：composer 按「占用的槽数」计零件，数量无关。
        // 堆到同一槽的话，AI 承诺的 [铁锭,铁锭,煤] 实际只算 2 个零件，
        // 与蓝图路径（逐槽铺开）结果不一致。
        Integer stackable = null;
        for (int i = 0; i < materialSlots; i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty()) {
                return i;
            }
            if (stackable == null && s.is(item) && s.getCount() < s.getMaxStackSize()
                    && ItemStack.isSameItemSameComponents(s, new ItemStack(item))) {
                stackable = i;
            }
        }
        return stackable == null ? -1 : stackable;
    }
}
