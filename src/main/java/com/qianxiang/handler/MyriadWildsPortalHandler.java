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
 * 万象森罗简易传送门：用裂隙精髓右键裂隙岩触发传送。
 * <p>
 * MVP 阶段不强制检测完整门框，只需右键裂隙岩即可在主世界与万象森罗之间往返。
 * 事件在服务端处理，消耗一个裂隙精髓（创造模式除外），并取消默认交互。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class MyriadWildsPortalHandler {

    private MyriadWildsPortalHandler() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!event.getItemStack().is(QianxiangMaterials.RIFT_ESSENCE.get())) return;
        if (!event.getLevel().getBlockState(event.getPos()).is(QianxiangBlocks.RIFT_STONE.get())) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        event.setCanceled(true);

        ServerLevel target = player.server.getLevel(
                player.serverLevel().dimension() == QianxiangDimensions.MYRIAD_WILDS
                        ? ServerLevel.OVERWORLD
                        : QianxiangDimensions.MYRIAD_WILDS);
        if (target == null) return;

        BlockPos pos = findSafePos(target, player.blockPosition());
        player.teleportTo(target, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot());

        if (!player.isCreative()) {
            event.getItemStack().shrink(1);
        }
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
