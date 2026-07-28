package com.qianxiang.compat;

import com.qianxiang.phase.MaterialSources;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;

/**
 * Refined Storage 2 网络取料源——软依赖隔离类。
 * <p>
 * 全模组对 {@code com.refinedmods.refinedstorage.*} 的引用只存在于本类，
 * 调用点一律先 {@code ModList.isLoaded("refinedstorage")} 再碰本类（EF 同款隔离模式）。
 * RS2 无 IItemHandler，走官方稳定 API：附近网络方块的 BE 实现
 * {@link NetworkNodeContainer} → {@code getNode().getNetwork()} →
 * {@code network.getComponent(StorageNetworkComponent.class)}（RootStorage 语义）取料。
 * </p>
 */
public final class RefinedStorageCompat {

    private RefinedStorageCompat() {}

    /** 台子 r=4 内第一个可用 RS2 网络的存储作为取料源；找不到返回空。 */
    public static Optional<MaterialSources.Source> sourceOf(Player player, Level level, BlockPos tablePos) {
        int r = MaterialSources.NEARBY_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(
                tablePos.offset(-r, -r, -r), tablePos.offset(r, r, r))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof NetworkNodeContainer container)) continue;
            Network network = container.getNode().getNetwork();
            if (network == null) continue;
            StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
            if (storage == null) continue;
            return MaterialSources.wrap(networkSource(storage, player));
        }
        return Optional.empty();
    }

    /** {@link StorageNetworkComponent}（RootStorage）→ Source 适配，严格物品守恒。 */
    private static MaterialSources.Source networkSource(StorageNetworkComponent storage, Player player) {
        return new MaterialSources.Source() {
            @Override
            public int count(Item item) {
                long stored = storage.get(keyOf(item));
                return (int) Math.min(Integer.MAX_VALUE, stored);
            }

            @Override
            public ItemStack extract(Item item, int count) {
                long got = storage.extract(keyOf(item), count, Action.EXECUTE, new PlayerActor(player));
                if (got <= 0) {
                    return ItemStack.EMPTY;
                }
                // 抽多少造多少（RS 返回数量语义，栈由我们按物品重建——组件级匹配见类文档遗留）
                return new ItemStack(item, (int) got);
            }
        };
    }

    private static ItemResource keyOf(Item item) {
        return ItemResource.ofItemStack(new ItemStack(item));
    }
}
