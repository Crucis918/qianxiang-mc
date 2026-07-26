package com.qianxiang.handler;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangBlocks;
import com.qianxiang.QianxiangDimensions;
import com.qianxiang.QianxiangMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Set;

/**
 * 万象森罗简易传送门：右键裂隙岩触发传送。
 * <p>
 * MVP 阶段不强制检测完整门框。规则：
 * <ul>
 *   <li>主世界 → 万象森罗：手持裂隙精髓右键裂隙岩，消耗 1 个（创造模式除外）。</li>
 *   <li>万象森罗 → 主世界：空手或手持裂隙精髓右键裂隙岩即可，<b>不消耗</b>——
 *       避免"进得去回不来"的死局。</li>
 *   <li>抵达万象森罗时，若落点附近没有裂隙岩，自动放置一块作为回程锚点。</li>
 *   <li><b>位格门控</b>：去程要求相谱位格 ≥ {@value WILDS_POSITION_THRESHOLD}
 *       （每次锻造 +1，讨伐守望者 +5）；创造模式与回程不受限。</li>
 * </ul>
 * 事件在服务端处理，并取消默认交互。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class MyriadWildsPortalHandler {

    /** 进入万象森罗所需的最低位格——逼玩家先在锻造台上「立身」，也是位格系统的第一个真实门槛。 */
    public static final int WILDS_POSITION_THRESHOLD = 3;

    private MyriadWildsPortalHandler() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!event.getLevel().getBlockState(event.getPos()).is(QianxiangBlocks.RIFT_STONE.get())) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        boolean leavingWilds = player.serverLevel().dimension() == QianxiangDimensions.MYRIAD_WILDS;
        boolean holdingEssence = event.getItemStack().is(QianxiangMaterials.RIFT_ESSENCE.get());
        // 去程必须持精髓；回程空手或持精髓皆可（不消耗）
        if (!leavingWilds && !holdingEssence) return;
        if (leavingWilds && !holdingEssence && !event.getItemStack().isEmpty()) return;

        event.setCanceled(true);

        // 位格门控（律二兑现）：裂隙只对有足够「位格」的相师回应。
        // 回程永不门控——绝不制造"进得去回不来"的死局。
        if (!leavingWilds && !player.isCreative()) {
            int position = player.getData(com.qianxiang.cap.QianxiangAttachments.SAGA_DATA).position();
            if (position < WILDS_POSITION_THRESHOLD) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "qianxiang.portal.position_gate", position, WILDS_POSITION_THRESHOLD));
                return;
            }
        }

        ServerLevel target = player.server.getLevel(
                leavingWilds ? ServerLevel.OVERWORLD : QianxiangDimensions.MYRIAD_WILDS);
        if (target == null) return;

        player.serverLevel().playSound(null, player.blockPosition(),
                net.minecraft.sounds.SoundEvents.PORTAL_TRAVEL,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.3f, 1.4f);
        BlockPos pos = findSafePos(target, player.blockPosition(), !leavingWilds);
        player.teleportTo(target, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot());
        target.playSound(null, pos, net.minecraft.sounds.SoundEvents.PORTAL_TRAVEL,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.3f, 1.4f);

        if (!leavingWilds) {
            if (!player.isCreative()) {
                event.getItemStack().shrink(1);
            }
            ensureReturnAnchor(target, pos, player);
        }
    }

    /**
     * 抵达万象森罗后，若落点周围没有裂隙岩，就近放置一块，保证回程可用。
     * <p>此前只试 2 个候选点，都不可替换就静默 return——玩家挖掉去程裂隙岩后
     * 就永久困在维度里。现在扫 3×3×3 候选，仍失败则强制在脚边放置，
     * 真的一个位置都放不下时给出聊天警示（不再静默）。
     */
    private static void ensureReturnAnchor(ServerLevel level, BlockPos arrival, ServerPlayer player) {
        for (BlockPos p : BlockPos.betweenClosed(arrival.offset(-6, -3, -6), arrival.offset(6, 3, 6))) {
            if (level.getBlockState(p).is(QianxiangBlocks.RIFT_STONE.get())) return;
        }
        var anchorState = QianxiangBlocks.RIFT_STONE.get().defaultBlockState();
        // 候选：脚边一圈 → 头顶两格 → 脚下（最后手段，玩家会站上去）
        for (BlockPos candidate : BlockPos.betweenClosed(
                arrival.offset(-1, 0, -1), arrival.offset(1, 2, 1))) {
            // 不占玩家自己的两格身位——放进头部格会直接把玩家闷死
            if (candidate.equals(arrival) || candidate.equals(arrival.above())) continue;
            if (level.getBlockState(candidate).canBeReplaced()) {
                level.setBlockAndUpdate(candidate.immutable(), anchorState);
                return;
            }
        }
        // 强制兜底只覆盖「可替换方块」（空气/草/水）——原条件把非完整碰撞箱也算进去，
        // 会连箱子/台阶/楼梯一起覆盖掉，等于破坏玩家的容器。
        BlockPos forced = arrival.above(3);
        if (level.getBlockState(forced).canBeReplaced()) {
            level.setBlockAndUpdate(forced, anchorState);
            return;
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "qianxiang.portal.anchor_failed"));
    }

    /**
     * 找一个可站立的落点。
     * <p>原实现用 {@code MOTION_BLOCKING_NO_LEAVES}（<b>流体计入高度图</b>），
     * 在 amplified 地形上会把玩家直接放到海面或岩浆湖表面；y 兜底为 100 时
     * 也不检查那里是不是实心岩层。现在改用 {@code WORLD_SURFACE} 取高后
     * 向上找「脚下是实心、身体两格是空、脚下不是流体」的位置，找不到就
     * 在候选点铺一块裂隙岩当基座。
     */
    private static BlockPos findSafePos(ServerLevel level, BlockPos reference, boolean mayBuildPlatform) {
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, reference);
        int startY = Math.clamp(surface.getY(), minY, maxY);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = startY; y <= Math.min(startY + 24, maxY); y++) {
            cursor.set(reference.getX(), y, reference.getZ());
            if (isStandable(level, cursor)) {
                return cursor.immutable();
            }
        }

        // 没有天然落点（全空气柱/树冠/雪层）。
        // 回程去主世界时**绝不**改地形——在别人的树顶或海面上凭空生成一块裂隙岩
        // 是实打实的地形污染，旧实现从不这么做。
        if (!mayBuildPlatform) {
            BlockPos spawn = level.getSharedSpawnPos();
            BlockPos spawnSurface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, spawn);
            return spawnSurface.above();
        }

        // 在目标维度内铺一小块黑曜石基座（比裂隙岩更不易被误挖，也不冒充回程锚点）
        BlockPos platform = new BlockPos(reference.getX(),
                Math.clamp(startY, minY, maxY - 2), reference.getZ());
        level.setBlockAndUpdate(platform,
                net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
        return platform.above();
    }

    /** 脚下实心非流体、身体两格可通过。 */
    private static boolean isStandable(ServerLevel level, BlockPos feet) {
        BlockPos ground = feet.below();
        var groundState = level.getBlockState(ground);
        if (!groundState.isSolidRender(level, ground) || !groundState.getFluidState().isEmpty()) {
            return false;
        }
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet).getFluidState().isEmpty()
                && level.getBlockState(feet.above()).getFluidState().isEmpty();
    }
}
