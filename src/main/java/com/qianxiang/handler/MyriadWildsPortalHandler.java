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
     * 在候选点铺一块黑曜石当基座。
     * <p>WQ-69 修复的三类塌陷：
     * <ol>
     *   <li><b>全空气柱（虚空上方）</b>：heightmap 贴底时从世界最低层起扫必然落空，
     *       旧实现随后把基座铺在世界最底层「活埋」玩家。现在先按原锚点扫一遍，
     *       确认是空柱后改以参考高度（玩家在源维度的高度）为锚重扫并铺基座。</li>
     *   <li><b>树冠上方悬空黑曜石</b>：{@code isSolidRender} 对树叶为 false，
     *       旧实现不认树冠、一路扫到树顶再往上铺基座。现在树叶算可站立地面
     *       （与原版「能站树叶上」一致）。</li>
     *   <li><b>heightmap 查询前未判区块加载</b>：未加载区块给的是未初始化的空高度图
     *       （全列返回最低高度），会把实心地面误判成虚空柱。查询前先
     *       {@code hasChunk} 检查、未加载则主动加载（玩家触发的传送，目标区块本就要加载）。</li>
     * </ol>
     */
    private static BlockPos findSafePos(ServerLevel level, BlockPos reference, boolean mayBuildPlatform) {
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;

        // ③ heightmap 查询前判区块加载
        if (!level.hasChunk(reference.getX() >> 4, reference.getZ() >> 4)) {
            level.getChunk(reference);
        }
        int surfaceY = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, reference).getY();

        int startY = Math.clamp(surfaceY, minY, maxY);
        BlockPos found = scanUp(level, reference.getX(), reference.getZ(),
                startY, Math.min(startY + 24, maxY));

        // ① 全空气柱：原锚点（世界最底层）扫不到任何东西时，改以参考高度为锚重扫，
        //    基座也锚定在那里，而不是把玩家埋到 y=min+2
        if (found == null && surfaceY <= minY) {
            int anchorY = Math.clamp(reference.getY(), minY, maxY);
            found = scanUp(level, reference.getX(), reference.getZ(),
                    anchorY, Math.min(anchorY + 24, maxY));
            if (found == null) {
                startY = anchorY; // 平台锚定在参考高度
            }
        }
        if (found != null) {
            return found;
        }

        // 没有天然落点（全空气柱/树冠/雪层）。
        // 回程去主世界时**绝不**改地形——在别人的树顶或海面上凭空生成一块裂隙岩
        // 是实打实的地形污染，旧实现从不这么做。回退到世界出生点是有意设计：
        // 它是主世界里少数「约定可站立」的锚点；但出生点本身也可能在水面/树冠上，
        // 所以回退点同样要过 isStandable（在出生点附近环形搜索最近的可站立陆地）。
        if (!mayBuildPlatform) {
            return findStandableNearSpawn(level, minY, maxY);
        }

        // 在目标维度内铺一小块黑曜石基座（比裂隙岩更不易被误挖，也不冒充回程锚点）
        BlockPos platform = new BlockPos(reference.getX(),
                Math.clamp(startY, minY, maxY - 2), reference.getZ());
        level.setBlockAndUpdate(platform,
                net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
        return platform.above();
    }

    /** 在 [fromY, toY] 内自下而上找第一个可站立位置；找不到返回 null。 */
    private static BlockPos scanUp(ServerLevel level, int x, int z, int fromY, int toY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = fromY; y <= toY; y++) {
            cursor.set(x, y, z);
            if (isStandable(level, cursor)) {
                return cursor.immutable();
            }
        }
        return null;
    }

    /**
     * 回程（不得改地形）的最终回退：以世界出生点为圆心做半径 4 的环形搜索，
     * 找最近的可站立位置——出生点在水面/树冠上时不再把玩家直接扔下水。
     * 全部落空（理论上几乎不可能）才退回旧的「出生点高度图上方一格」。
     */
    private static BlockPos findStandableNearSpawn(ServerLevel level, int minY, int maxY) {
        BlockPos spawn = level.getSharedSpawnPos();
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // 只走圈边
                    BlockPos col = spawn.offset(dx, 0, dz);
                    if (!level.hasChunk(col.getX() >> 4, col.getZ() >> 4)) {
                        level.getChunk(col);
                    }
                    int top = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, col).getY();
                    int fromY = Math.clamp(top, minY, maxY);
                    BlockPos found = scanUp(level, col.getX(), col.getZ(), fromY, Math.min(fromY + 8, maxY));
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, spawn).above();
    }

    /** 脚下实心非流体（树叶算可站立）、身体两格可通过。 */
    private static boolean isStandable(ServerLevel level, BlockPos feet) {
        BlockPos ground = feet.below();
        var groundState = level.getBlockState(ground);
        // ② 树叶可站立：isSolidRender 对树叶为 false，不认会把玩家推到树冠上方再铺悬空基座
        boolean solidGround = groundState.isSolidRender(level, ground)
                || groundState.is(net.minecraft.tags.BlockTags.LEAVES);
        if (!solidGround || !groundState.getFluidState().isEmpty()) {
            return false;
        }
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet).getFluidState().isEmpty()
                && level.getBlockState(feet.above()).getFluidState().isEmpty();
    }

    /** 测试入口：去程落点解析（与真实传送同一条 findSafePos 路径，允许铺基座）。 */
    public static BlockPos findArrivalPosForTest(ServerLevel level, BlockPos reference) {
        return findSafePos(level, reference, true);
    }

    /** 测试入口：回程落点解析（与真实传送同一条 findSafePos 路径，绝不改地形）。 */
    public static BlockPos findReturnPosForTest(ServerLevel level, BlockPos reference) {
        return findSafePos(level, reference, false);
    }
}
