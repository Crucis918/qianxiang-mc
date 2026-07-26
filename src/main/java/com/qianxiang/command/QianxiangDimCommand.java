package com.qianxiang.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDimensions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Set;

/**
 * /qianxiang dim 命令——MVP 第一步维度传送。
 * <p>
 * 在主世界执行会传送到「万象森罗」；在万象森罗执行会返回主世界。
 * 目标位置取当前 x/z 坐标对应的地表高度，找不到地表则回退到 y=100。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class QianxiangDimCommand {

    private QianxiangDimCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("qianxiang")
                        .then(Commands.literal("dim")
                                .executes(QianxiangDimCommand::handleDim)));
        Qianxiang.LOGGER.info("[Qianxiang] 维度命令已注册：/qianxiang dim");
    }

    private static int handleDim(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel current = player.serverLevel();

        boolean enteringMyriad = current.dimension() != QianxiangDimensions.MYRIAD_WILDS;
        ServerLevel target = current.getServer().getLevel(
                enteringMyriad ? QianxiangDimensions.MYRIAD_WILDS : ServerLevel.OVERWORLD);

        if (target == null) {
            src.sendFailure(Component.translatable("qianxiang.command.dim.not_found"));
            return 0;
        }

        BlockPos pos = findSafePos(target, player.blockPosition());
        player.teleportTo(target, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot());

        src.sendSuccess(() -> Component.translatable(
                enteringMyriad ? "qianxiang.command.dim.enter" : "qianxiang.command.dim.return"), false);
        return 1;
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
