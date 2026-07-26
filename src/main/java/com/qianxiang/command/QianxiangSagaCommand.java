package com.qianxiang.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.qianxiang.Qianxiang;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.cap.SagaData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /qianxiang saga 命令——查看玩家自己的相谱（不可逆传记）+ 位格。
 * <p>
 * 自注册：靠 {@link EventBusSubscriber} 挂 GAME 总线，
 * <b>不修改任何现有文件</b>（包括 {@link Qianxiang} 主类）。
 * <p>
 * 相谱数据来自 NeoForge Attachment {@link QianxiangAttachments#SAGA_DATA}（{@link SagaData}），
 * 由能力部维护。本命令只读、永不抛：getData 任何异常都降级为"相谱空白"提示。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class QianxiangSagaCommand {

    /** 位格上限（MVP 雏形），与 SagaData 契约一致。 */
    private static final int POSITION_CAP = 100;

    /** 每页条目数。 */
    private static final int PAGE_SIZE = 10;

    private QianxiangSagaCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("qianxiang")
                        .then(Commands.literal("saga")
                                .executes(ctx -> handleSaga(ctx, 1))
                                .then(Commands.argument("page",
                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .executes(ctx -> handleSaga(ctx,
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .getInteger(ctx, "page"))))));
        Qianxiang.LOGGER.info("[Qianxiang] 相谱命令已注册：/qianxiang saga [页码]");
    }

    /** /qianxiang saga [page] 的执行体。最新条目在前，每页 {@value PAGE_SIZE} 条。返回位格值作为命令结果。 */
    private static int handleSaga(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        net.minecraft.world.entity.player.Player player = src.getPlayerOrException();

        // 读相谱 Attachment；任何异常都降级为"空白"，绝不崩
        SagaData saga;
        try {
            saga = player.getData(QianxiangAttachments.SAGA_DATA.get());
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] /qianxiang saga 读 SagaData 失败", t);
            src.sendSuccess(() -> Component.literal("§6【相谱】§r 位格 §e0/" + POSITION_CAP + "§r"), false);
            src.sendSuccess(() -> Component.literal("§7相谱空白，去锻造你的第一件器吧。§r"), false);
            return 0;
        }

        int position = saga == null ? 0 : Math.max(0, Math.min(POSITION_CAP, saga.position()));
        java.util.List<String> entries = saga == null ? java.util.List.of() : saga.entries();

        // —— 位格条 ——
        src.sendSuccess(() -> Component.literal(
                "§6【相谱】§r 位格 §e" + position + "/" + POSITION_CAP + "§r"), false);

        // —— 相谱正文（最新在前，分页）——
        if (entries == null || entries.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7相谱空白，去锻造你的第一件器吧。§r"), false);
            return position;
        }

        int total = entries.size();
        int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.min(page, pages);
        int from = (current - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, total);

        src.sendSuccess(() -> Component.literal(
                "§7—— 第 §e" + current + "§7/§e" + pages + "§7 页（共 " + total + " 条明细）——§r"), false);
        // entries 按时间正序存储，展示时最新在前
        for (int i = 0; i < to - from; i++) {
            String entry = entries.get(total - 1 - from - i);
            String line = entry == null ? "" : entry;
            src.sendSuccess(() -> Component.literal("§7- " + line + "§r"), false);
        }
        if (saga.forgotten() > 0) {
            int faded = saga.forgotten();
            src.sendSuccess(() -> Component.literal(
                    "§8……更早的 " + faded + " 条铭刻已随岁月淡忘（总铭刻 " + saga.totalInscribed() + " 笔）。§r"), false);
        }
        if (current < pages) {
            int nextPage = current + 1;
            src.sendSuccess(() -> Component.literal(
                    "§8输入 /qianxiang saga " + nextPage + " 查看更早的记录。§r"), false);
        }

        return position;
    }
}
