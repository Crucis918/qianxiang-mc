package com.qianxiang.phase;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工作台自动取料的存储来源抽象（AI 放料用）。
 * <p>
 * 对（玩家，台子位置，世界）建<b>有序</b>来源列表，按序翻：
 * 玩家背包 → 旅行背包（TB，软依赖）→ 台子 r=4 内容器（NeoForge 标准
 * item handler，天然覆盖 SSN Exchange 代理块/箱子）→ RS2 网络（软依赖）。
 * 装哪个认哪个，不装不崩（TB/RS2 的类型引用全部隔离在 compat/ 两个类里，
 * 本类只在 {@code ModList.isLoaded} 守卫内触碰，与 EF 同一隔离模式）。
 * </p>
 * <p>
 * 手动路径（GUI shift-click / 手持投入 / 扔料吸收）不走这里——语义不变，仍只认玩家背包。
 * </p>
 */
public final class MaterialSources {

    private MaterialSources() {}

    /** 附近容器/RS2 网络的扫描半径（Chebyshev 距离）。 */
    public static final int NEARBY_RADIUS = 4;

    /** 一个取料来源：count/extract 两操作，严格物品守恒（抽多少放多少）。 */
    public interface Source {
        /** 该来源里某物品的当前数量（0 = 没有）。 */
        int count(Item item);

        /**
         * 抽取至多 {@code count} 个 item。
         *
         * @return 实际抽到的栈（count 可能小于请求）；没抽到返回 {@link ItemStack#EMPTY}
         */
        ItemStack extract(Item item, int count);
    }

    /** 有序来源列表：玩家背包 → TB → 附近容器（逐个）→ RS2。 */
    public static List<Source> of(Player player, Level level, BlockPos tablePos) {
        List<Source> sources = new ArrayList<>();
        sources.add(playerInventory(player));
        // TB/RS2 软依赖：方法引用只在 isLoaded 内求值，未装时 compat 类的方法体
        // 不会执行到第三方类型（EF 同款类加载隔离）。
        if (net.neoforged.fml.ModList.get().isLoaded("travelersbackpack")) {
            com.qianxiang.compat.TravelersBackpackCompat.sourceOf(player).ifPresent(sources::add);
        }
        sources.addAll(nearbyContainers(level, tablePos));
        if (net.neoforged.fml.ModList.get().isLoaded("refinedstorage")) {
            com.qianxiang.compat.RefinedStorageCompat.sourceOf(player, level, tablePos)
                    .ifPresent(sources::add);
        }
        return sources;
    }

    // ============================ 玩家背包 ============================

    /** 玩家主背包 36 格（不动盔甲/副手——与 AiPlaceMaterialsHandler 原有口径一致）。 */
    private static Source playerInventory(Player player) {
        return new Source() {
            @Override
            public int count(Item item) {
                int total = 0;
                for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (!s.isEmpty() && s.is(item)) total += s.getCount();
                }
                return total;
            }

            @Override
            public ItemStack extract(Item item, int count) {
                for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (!s.isEmpty() && s.is(item)) {
                        return s.split(count);
                    }
                }
                return ItemStack.EMPTY;
            }
        };
    }

    // ============================ 附近容器（r=4，SSN/箱子通用） ============================

    /** 扫描台子周围 r=4 内所有暴露 {@code Capabilities.ItemHandler.BLOCK} 的方块（排除台子自身）。 */
    public static List<Source> nearbyContainers(Level level, BlockPos tablePos) {
        List<Source> sources = new ArrayList<>();
        int r = NEARBY_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(
                tablePos.offset(-r, -r, -r), tablePos.offset(r, r, r))) {
            if (pos.equals(tablePos)) continue;  // 台子自身不算来源
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler != null) {
                sources.add(handlerSource(handler));
            }
        }
        return sources;
    }

    /** {@link IItemHandler} → Source 适配（TB 的 backpack storage 与附近容器共用）。 */
    public static Source handlerSource(IItemHandler handler) {
        return new Source() {
            @Override
            public int count(Item item) {
                int total = 0;
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack s = handler.getStackInSlot(i);
                    if (!s.isEmpty() && s.is(item)) total += s.getCount();
                }
                return total;
            }

            @Override
            public ItemStack extract(Item item, int count) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack s = handler.getStackInSlot(i);
                    if (!s.isEmpty() && s.is(item)) {
                        // simulate 先行，保证「抽多少放多少」的守恒口径
                        ItemStack sim = handler.extractItem(i, count, true);
                        if (sim.isEmpty()) continue;
                        return handler.extractItem(i, sim.getCount(), false);
                    }
                }
                return ItemStack.EMPTY;
            }
        };
    }

    /** 空来源列表（测试/兜底用）。 */
    public static List<Source> none() {
        return List.of();
    }

    /** 便捷：只包玩家背包的来源列表（手动路径口径，供测试对照）。 */
    public static List<Source> playerOnly(Player player) {
        return List.of(playerInventory(player));
    }

    /** Optional 助手：非 null 才包装（compat 类用）。 */
    public static Optional<Source> wrap(Source source) {
        return Optional.of(source);
    }
}
