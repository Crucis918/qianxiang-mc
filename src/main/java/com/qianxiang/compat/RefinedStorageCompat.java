package com.qianxiang.compat;

import com.qianxiang.phase.MaterialSources;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
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
 * <p>
 * <b>组件级匹配</b>：千相材料带 phase_data 等组件，存进 RS 网络后是「Item + 组件 patch」
 * 条目，旧实现用 {@code ItemResource.ofItemStack(白板栈)} 裸 key 查询会完全匹配不到。
 * 现改为遍历 {@code getAll()} 逐条目按 {@link ItemMatch#matches} 口径匹配
 * （同 Item；白板查询接受任何同 Item 条目，带组件查询要求组件精确相等），
 * count 累加全部匹配条目，extract 选最佳条目（优先无组件裸条目，其次数量最多者）
 * 用其真实 {@link ItemResource} 抽取并按 {@code toItemStack} 重建栈——组件随料带出，
 * 严格物品守恒。
 * </p>
 * <p>
 * <b>实机验证步骤</b>（dev 环境未装 RS，单测只覆盖纯谓词 {@link ItemMatch}）：
 * 装好 RS2 后进存档，摆 控制器+磁盘驱动器+合成台旁造台 →
 * ① 把合成过的千相材料（带 phase_data）存入磁盘，台子需求清单应能计数到该材料；
 * ② shift 自动取料，材料连组件一起落到台子格（物品 hover 可见相性组件）；
 * ③ 同名白板材料与带组件材料混存时，优先消耗裸条目，数量不足再动带组件条目。
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
                ItemStack query = new ItemStack(item);
                long total = 0;
                for (ResourceAmount ra : storage.getAll()) {
                    if (ra.resource() instanceof ItemResource ir
                            && ItemMatch.matches(query, ir.item(), ir.components())) {
                        total += ra.amount();
                        if (total >= Integer.MAX_VALUE) {
                            return Integer.MAX_VALUE;
                        }
                    }
                }
                return (int) total;
            }

            @Override
            public ItemStack extract(Item item, int count) {
                ItemResource best = bestMatch(storage, new ItemStack(item));
                if (best == null) {
                    return ItemStack.EMPTY;
                }
                long got = storage.extract(best, count, Action.EXECUTE, new PlayerActor(player));
                if (got <= 0) {
                    return ItemStack.EMPTY;
                }
                // 用匹配到的真实条目重建栈：组件随料带出，抽多少给多少
                return best.toItemStack(got);
            }
        };
    }

    /** 匹配条目里选抽取对象：优先无组件裸条目（等同旧行为），同档取数量最多者。 */
    private static ItemResource bestMatch(StorageNetworkComponent storage, ItemStack query) {
        ItemResource best = null;
        long bestAmount = -1;
        for (ResourceAmount ra : storage.getAll()) {
            if (!(ra.resource() instanceof ItemResource ir)
                    || !ItemMatch.matches(query, ir.item(), ir.components())) {
                continue;
            }
            boolean curBlank = ir.components().isEmpty();
            boolean bestBlank = best != null && best.components().isEmpty();
            if (best == null || (curBlank && !bestBlank)
                    || (curBlank == bestBlank && ra.amount() > bestAmount)) {
                best = ir;
                bestAmount = ra.amount();
            }
        }
        return best;
    }

    /**
     * RS 条目与查询栈的匹配谓词——纯函数，<b>不引用任何 RS 类型</b>。
     * 独立成嵌套类而非本类静态方法的原因：调用本类静态方法会触发 {@link RefinedStorageCompat}
     * 链接，进而解析方法签名里的 RS 类型，在未装 RS 的环境（dev/gametest）直接 NoClassDefFoundError；
     * 嵌套类是独立 class 文件，加载它不会链接外层类，单测可安全触碰。
     */
    public static final class ItemMatch {

        private ItemMatch() {}

        /**
         * 匹配口径：同 Item 且网络条目的组件覆盖查询所需——
         * 查询是白板（无组件 patch）时接受任何同 Item 条目；查询带组件时要求精确相等。
         *
         * @param query           查询栈（取料调用方目前总是白板栈，但谓词对带组件查询同样成立）
         * @param entryItem       网络条目的 Item
         * @param entryComponents 网络条目的组件 patch
         */
        public static boolean matches(ItemStack query, Item entryItem, DataComponentPatch entryComponents) {
            if (query.getItem() != entryItem) {
                return false;
            }
            DataComponentPatch need = query.getComponentsPatch();
            return need.isEmpty() || need.equals(entryComponents);
        }
    }
}
