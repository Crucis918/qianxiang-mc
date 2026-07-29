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
 * 服务端处理「把 AI 推荐材料放入功能台材料槽」的请求（锻造台/炼金台共用，按菜单类型分派）。
 * <p>
 * 放料核心抽成 {@link #placeMaterials}（GameTest 可直接断言）：
 * 返回放入数与「请求了但没放进」的缺料名单（背包没有 / 材料槽满），
 * 缺料不再静默——handler 把缺料物品 id 列表打包（{@link MissingMaterialsPayload}）
 * 发给客户端，由客户端按本地语言环境渲染「还缺：X、Y」actionbar。
 * </p>
 */
public final class AiPlaceMaterialsHandler {

    private AiPlaceMaterialsHandler() {}

    /** 放料结果：放入数量 + 已放入物品 + 缺料名单（请求了但没放进的）。 */
    public record PlaceResult(int placedCount, List<Item> placed, List<Item> missing) {}

    public static void handle(AiPlaceMaterialsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player == null) return;

            // 锻造台 / 炼金台共用放料逻辑：按菜单类型取容器与材料槽数。
            AbstractContainerMenu menu = player.containerMenu;
            net.minecraft.world.Container container;
            int[] fillOrder;
            String noMaterialsKey;
            if (menu instanceof ForgeTableMenu forgeMenu) {
                container = forgeMenu.getContainer();
                fillOrder = ForgeTableMenu.SLOT_FILL_ORDER;
                noMaterialsKey = "qianxiang.forge_table.msg.no_materials";
            } else if (menu instanceof com.qianxiang.menu.AlchemyTableMenu alchemyMenu) {
                container = alchemyMenu.getContainer();
                fillOrder = com.qianxiang.menu.AlchemyTableMenu.SLOT_FILL_ORDER;
                noMaterialsKey = "qianxiang.alchemy_table.msg.no_materials";
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

            PlaceResult result = placeMaterials(player, container, fillOrder, wanted, payload.replace());

            if (result.placedCount() > 0) {
                logAdoption(player, payload, result);
                menu.slotsChanged(container);
                container.setChanged();
                player.getInventory().setChanged();
            }
            // 缺料明示：请求了但没放进（背包没有/材料槽满）不再静默。
            // 只发物品 id 列表（S2C payload），物品名由客户端用本地语言环境的
            // ItemStack#getHoverName() 渲染——服务端拼 hoverName 会让中文客户端看到英文名。
            if (!result.missing().isEmpty() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                List<String> missingIds = new ArrayList<>();
                for (Item item : result.missing()) {
                    missingIds.add(BuiltInRegistries.ITEM.getKey(item).toString());
                }
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        sp, new MissingMaterialsPayload(missingIds));
            }
        });
    }

    /**
     * 闭合「建议 → 采纳」链路（WQ-71）：reqId 用客户端回传的真 id
     * （主线程读 ThreadLocal 是假 id，全服共用一个）；材料只记实际放入的
     * （此前放进 1 件也记整个请求名单，over-report）。抽出来供 GameTest 直接驱动。
     */
    public static void logAdoption(Player player, AiPlaceMaterialsPayload payload, PlaceResult result) {
        List<String> placedNames = new ArrayList<>();
        for (Item item : result.placed()) {
            placedNames.add(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        com.qianxiang.ai.AIGateway.logAdoption(
                payload.reqId(), placedNames, player.getUUID().toString());
    }

    /**
     * 把 wanted 逐件移入材料槽（每件 1 个）。
     * <p>
     * 取料按序翻 {@link com.qianxiang.phase.MaterialSources}：玩家背包 → 旅行背包 →
     * 台旁 r=4 容器（SSN/箱子）→ RS2 网络；全部来源都没有才算缺（missing 口径不变）。
     * 先查槽位再抽取，严格物品守恒（抽多少放多少，堆叠冲突的退回玩家背包）。
     * 纯逻辑无发包，GameTest 可直接断言返回值与容器状态。
     */
    public static PlaceResult placeMaterials(Player player, net.minecraft.world.Container container,
                                             int[] fillOrder, List<Item> wanted) {
        return placeMaterials(player, container, fillOrder, wanted, false);
    }

    /**
     * 把 wanted 逐件移入材料槽（每件 1 个），{@code replace}=true 时先把材料槽里的
     * 现有材料全部退回玩家背包（WQ-75：连点两张方案卡 = 替换而非叠加，
     * 产物强度才与卡片摘要一致）。
     */
    public static PlaceResult placeMaterials(Player player, net.minecraft.world.Container container,
                                             int[] fillOrder, List<Item> wanted, boolean replace) {
        if (replace) {
            returnExistingMaterials(player, container, fillOrder);
        }
        net.minecraft.core.BlockPos tablePos =
                container instanceof net.minecraft.world.level.block.entity.BlockEntity be
                        ? be.getBlockPos() : player.blockPosition();
        List<com.qianxiang.phase.MaterialSources.Source> sources =
                com.qianxiang.phase.MaterialSources.of(player, player.level(), tablePos);

        List<Item> placed = new ArrayList<>();
        List<Item> missing = new ArrayList<>();
        for (Item item : wanted) {
            int materialSlot = findMaterialSlot(container, item, fillOrder);
            if (materialSlot < 0) {
                missing.add(item); // 材料槽已满（先查槽再抽取，守恒）
                continue;
            }
            ItemStack move = extractFromSources(sources, item, 1);
            if (move.isEmpty()) {
                missing.add(item);
                continue;
            }
            ItemStack existing = container.getItem(materialSlot);
            if (existing.isEmpty()) {
                container.setItem(materialSlot, move);
            } else if (ItemStack.isSameItemSameComponents(existing, move)
                    && existing.getCount() < existing.getMaxStackSize()) {
                existing.grow(1);
                container.setItem(materialSlot, existing);
            } else {
                // 组件不一致无法堆叠：退回玩家背包（物品不丢），记为未放入
                missing.add(item);
                if (!player.getInventory().add(move)) {
                    player.drop(move, false);
                }
                continue;
            }
            placed.add(item);
        }
        return new PlaceResult(placed.size(), List.copyOf(placed), List.copyOf(missing));
    }

    /** 替换语义的先手：材料槽现有物品全部退回玩家背包（背包满则掉脚下，不丢物品）。 */
    private static void returnExistingMaterials(Player player, net.minecraft.world.Container container,
                                                int[] fillOrder) {
        for (int slot : fillOrder) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) continue;
            container.setItem(slot, ItemStack.EMPTY);
            if (!player.getInventory().add(existing)) {
                player.drop(existing, false);
            }
        }
    }

    /** 按序遍历来源抽取至多 count 个；全部来源都没有返回空栈。 */
    private static ItemStack extractFromSources(
            List<com.qianxiang.phase.MaterialSources.Source> sources, Item item, int count) {
        for (var source : sources) {
            if (source.count(item) <= 0) continue;
            ItemStack got = source.extract(item, count);
            if (!got.isEmpty()) return got;
        }
        return ItemStack.EMPTY;
    }

    /** 测试入口：直接对容器做槽位选择（GameTest 无需构造完整菜单）。 */
    public static int findMaterialSlotForTest(net.minecraft.world.Container container, Item item) {
        return findMaterialSlot(container, item, ForgeTableMenu.SLOT_FILL_ORDER);
    }

    /**
     * 找可放入指定物品的材料槽：按 fillOrder 找空槽（「填充顺序即布局」，中心优先），
     * 其次可堆叠的同种物品槽。若找不到返回 -1（材料槽已满）。
     */
    private static int findMaterialSlot(net.minecraft.world.Container container, Item item, int[] fillOrder) {
        // 必须真·优先空槽：composer 按「占用的槽数」计零件，数量无关。
        // 堆到同一槽的话，AI 承诺的 [铁锭,铁锭,煤] 实际只算 2 个零件，
        // 与蓝图路径（逐槽铺开）结果不一致。
        Integer stackable = null;
        for (int slot : fillOrder) {
            ItemStack s = container.getItem(slot);
            if (s.isEmpty()) {
                return slot;
            }
            if (stackable == null && s.is(item) && s.getCount() < s.getMaxStackSize()
                    && ItemStack.isSameItemSameComponents(s, new ItemStack(item))) {
                stackable = slot;
            }
        }
        return stackable == null ? -1 : stackable;
    }
}
