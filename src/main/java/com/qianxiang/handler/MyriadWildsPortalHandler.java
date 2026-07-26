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
        BlockPos pos = findSafePos(target, player.blockPosition());
        player.teleportTo(target, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot());
        target.playSound(null, pos, net.minecraft.sounds.SoundEvents.PORTAL_TRAVEL,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.3f, 1.4f);

        if (!leavingWilds) {
            if (!player.isCreative()) {
                event.getItemStack().shrink(1);
            }
            ensureReturnAnchor(target, pos);
        }
    }

    /** 抵达万象森罗后，若落点周围没有裂隙岩，就近放置一块，保证回程可用。 */
    private static void ensureReturnAnchor(ServerLevel level, BlockPos arrival) {
        for (BlockPos p : BlockPos.betweenClosed(arrival.offset(-6, -3, -6), arrival.offset(6, 3, 6))) {
            if (level.getBlockState(p).is(QianxiangBlocks.RIFT_STONE.get())) return;
        }
        BlockPos anchor = arrival.east();
        if (!level.getBlockState(anchor).canBeReplaced()) {
            anchor = arrival.above(2);
            if (!level.getBlockState(anchor).canBeReplaced()) return;
        }
        level.setBlockAndUpdate(anchor, QianxiangBlocks.RIFT_STONE.get().defaultBlockState());
    }

    private static BlockPos findSafePos(ServerLevel level, BlockPos reference) {
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, reference);
        int y = surface.getY();
        if (y <= level.getMinBuildHeight() + 1) {
            y = Math.max(level.getMinBuildHeight() + 1, 100);
        }
        return new BlockPos(reference.getX(), y, reference.getZ());
    }
}
