package com.qianxiang.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.qianxiang.Qianxiang;
import com.qianxiang.network.ScreenshotRequestPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * /qianxiang shot —— 开发调试「游戏内截图眼」。
 * <p>
 * 服务端命令向发起者发 {@link ScreenshotRequestPayload}，客户端抓整窗存
 * {@code run/screenshots/qx_<时间戳>.png}。用于冒烟时拍背包图标/手持 3D/
 * 台子虚影/合成仪式等外观表现。限 OP（开发工具）。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class QianxiangShotCommand {

    private QianxiangShotCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("qianxiang")
                        .then(Commands.literal("shot")
                                // 开发调试工具，限 OP（与 kit/dim 同口径）
                                .requires(src -> src.hasPermission(2))
                                .executes(QianxiangShotCommand::handleShot)));
        Qianxiang.LOGGER.info("[Qianxiang] 截图命令已注册：/qianxiang shot");
    }

    private static int handleShot(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PacketDistributor.sendToPlayer(player, new ScreenshotRequestPayload());
        ctx.getSource().sendSuccess(
                () -> Component.translatable("qianxiang.command.shot.saved"), false);
        return 1;
    }
}
