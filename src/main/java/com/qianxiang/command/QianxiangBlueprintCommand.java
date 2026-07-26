package com.qianxiang.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.qianxiang.Qianxiang;
import com.qianxiang.blueprint.BlueprintData;
import com.qianxiang.blueprint.BlueprintLibrary;
import com.qianxiang.blueprint.BlueprintShareCodes;
import com.qianxiang.cap.QianxiangAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * /qianxiang blueprint 命令 —— 蓝图的 UGC 分享入口。
 * <ul>
 *   <li>{@code list} —— 列出自己的蓝图（带序号）。</li>
 *   <li>{@code export <序号>} —— 把蓝图编成分享码，聊天点击即复制。</li>
 *   <li>{@code import <码>} —— 粘贴他人分享码，蓝图入库（带消毒 + 材料存在性提示）。</li>
 * </ul>
 * 自注册 GAME 总线，只读/追加玩家自己的 {@code BLUEPRINT_LIBRARY} attachment，永不抛。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class QianxiangBlueprintCommand {

    private QianxiangBlueprintCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("qianxiang")
                        .then(Commands.literal("blueprint")
                                .then(Commands.literal("list")
                                        .executes(QianxiangBlueprintCommand::handleList))
                                .then(Commands.literal("export")
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .executes(ctx -> handleExport(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "index")))))
                                .then(Commands.literal("import")
                                        .then(Commands.argument("code", StringArgumentType.greedyString())
                                                .executes(ctx -> handleImport(ctx,
                                                        StringArgumentType.getString(ctx, "code"))))))
                        .then(Commands.literal("workshop")
                                .then(Commands.literal("list")
                                        .executes(ctx -> handleWorkshopList(ctx, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> handleWorkshopList(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "page")))))
                                .then(Commands.literal("publish")
                                        .then(Commands.argument("blueprintIndex", IntegerArgumentType.integer(1))
                                                .executes(ctx -> handleWorkshopPublish(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "blueprintIndex")))))
                                .then(Commands.literal("take")
                                        .then(Commands.argument("workshopIndex", IntegerArgumentType.integer(1))
                                                .executes(ctx -> handleWorkshopTake(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "workshopIndex")))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("workshopIndex", IntegerArgumentType.integer(1))
                                                .executes(ctx -> handleWorkshopRemove(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "workshopIndex")))))));
        Qianxiang.LOGGER.info(
                "[Qianxiang] 蓝图命令已注册：/qianxiang blueprint list|export|import；/qianxiang workshop list|publish|take|remove");
    }

    // ============================ 工坊（服务器共享库） ============================

    private static final int WORKSHOP_PAGE_SIZE = 8;

    /** /qianxiang workshop list [页码] —— 浏览全服共享蓝图，带可点击的 [取用]。 */
    private static int handleWorkshopList(CommandContext<CommandSourceStack> ctx, int page)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var workshop = com.qianxiang.blueprint.WorkshopSavedData.get(player.server);
        var entries = workshop.entries();
        if (entries.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7工坊还空着。用 /qianxiang workshop publish <蓝图序号> 发布第一份吧。§r"), false);
            return 0;
        }
        int pages = (entries.size() + WORKSHOP_PAGE_SIZE - 1) / WORKSHOP_PAGE_SIZE;
        int current = Math.min(page, pages);
        int from = (current - 1) * WORKSHOP_PAGE_SIZE;
        int to = Math.min(from + WORKSHOP_PAGE_SIZE, entries.size());

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§6【千相工坊】§r共 " + entries.size() + " 份 §7(第 " + current + "/" + pages + " 页)§r"), false);
        for (int i = from; i < to; i++) {
            var e = entries.get(i);
            int index = i + 1;
            Component takeBtn = Component.literal("[取用]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/qianxiang workshop take " + index))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("复制到我的蓝图库"))));
            ctx.getSource().sendSuccess(() -> Component.literal(
                            String.format("§e#%d§r %s §7by %s (强度 %.1f，被取用 %d 次)§r ",
                                    index, e.data().name(), e.author(), e.data().power(), e.takes()))
                    .append(takeBtn), false);
        }
        if (current < pages) {
            int next = current + 1;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§8输入 /qianxiang workshop list " + next + " 看下一页。§r"), false);
        }
        return entries.size();
    }

    /** /qianxiang workshop publish <蓝图序号> —— 把自己的蓝图发布到全服共享库。 */
    private static int handleWorkshopPublish(CommandContext<CommandSourceStack> ctx, int blueprintIndex)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<BlueprintData> blueprints = library(player).blueprints();
        if (blueprintIndex > blueprints.size()) {
            ctx.getSource().sendFailure(Component.literal(
                    "没有 #" + blueprintIndex + " 号蓝图（共 " + blueprints.size() + " 份）"));
            return 0;
        }
        BlueprintData data = blueprints.get(blueprintIndex - 1);
        var workshop = com.qianxiang.blueprint.WorkshopSavedData.get(player.server);
        String reject = workshop.publish(data,
                player.getGameProfile().getName(), player.getUUID().toString());
        if (reject != null) {
            ctx.getSource().sendFailure(Component.literal("发布失败：" + reject));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a【工坊】§r「" + data.name() + "」已发布到全服共享库！"), true);
        com.qianxiang.QianxiangAdvancements.grant(player, com.qianxiang.QianxiangAdvancements.BLUEPRINT_SHARE);
        return 1;
    }

    /** /qianxiang workshop take <工坊序号> —— 把共享蓝图复制到自己的库。 */
    private static int handleWorkshopTake(CommandContext<CommandSourceStack> ctx, int workshopIndex)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var workshop = com.qianxiang.blueprint.WorkshopSavedData.get(player.server);
        var entry = workshop.take(workshopIndex - 1);
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal("没有 #" + workshopIndex + " 号共享蓝图"));
            return 0;
        }
        BlueprintLibrary lib = library(player);
        player.setData(QianxiangAttachments.BLUEPRINT_LIBRARY, lib.withAdded(entry.data()));
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a【工坊】§r已取用「" + entry.data().name() + "」§7(by " + entry.author() + ")§r，入库为 #"
                        + (lib.blueprints().size() + 1)), false);
        com.qianxiang.QianxiangAdvancements.grant(player, com.qianxiang.QianxiangAdvancements.BLUEPRINT_SHARE);
        return 1;
    }

    /** /qianxiang workshop remove <工坊序号> —— 下架（发布者本人或 OP）。 */
    private static int handleWorkshopRemove(CommandContext<CommandSourceStack> ctx, int workshopIndex)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var workshop = com.qianxiang.blueprint.WorkshopSavedData.get(player.server);
        boolean canModerate = ctx.getSource().hasPermission(2);
        var removed = workshop.remove(workshopIndex - 1, player.getUUID().toString(), canModerate);
        if (removed == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "下架失败：序号不存在，或这份蓝图不是你发布的（OP 可下架任何蓝图）"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a【工坊】§r已下架「" + removed.data().name() + "」"), true);
        return 1;
    }

    private static int handleList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<BlueprintData> blueprints = library(player).blueprints();
        if (blueprints.isEmpty()) {
            ctx.getSource().sendSuccess(() ->
                    Component.literal("§7还没有蓝图。在锻造台锻出满意的产物后保存蓝图，或 import 他人的分享码。§r"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() ->
                Component.literal("§6【千相蓝图】§r共 " + blueprints.size() + " 份："), false);
        for (int i = 0; i < blueprints.size(); i++) {
            BlueprintData d = blueprints.get(i);
            int index = i + 1;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    String.format("§e#%d§r %s §7(强度 %.1f，材料 %d 种)§r",
                            index, d.name(), d.power(), d.materials().size())), false);
        }
        return blueprints.size();
    }

    private static int handleExport(CommandContext<CommandSourceStack> ctx, int index)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<BlueprintData> blueprints = library(player).blueprints();
        if (index > blueprints.size()) {
            ctx.getSource().sendFailure(Component.literal(
                    "没有 #" + index + " 号蓝图（共 " + blueprints.size() + " 份，用 /qianxiang blueprint list 查看）"));
            return 0;
        }
        BlueprintData data = blueprints.get(index - 1);
        String code;
        try {
            code = BlueprintShareCodes.encode(data);
        } catch (Exception e) {
            Qianxiang.LOGGER.error("[Qianxiang] 蓝图导出失败", e);
            ctx.getSource().sendFailure(Component.literal("蓝图编码失败，见服务端日志"));
            return 0;
        }
        Component clickable = Component.literal("[点击复制分享码]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("复制后发给朋友，对方用 /qianxiang blueprint import 粘贴导入"))));
        ctx.getSource().sendSuccess(() -> Component.literal("§6【蓝图分享】§r「" + data.name() + "」 ")
                .append(clickable), false);
        com.qianxiang.QianxiangAdvancements.grant(player, com.qianxiang.QianxiangAdvancements.BLUEPRINT_SHARE);
        return 1;
    }

    private static int handleImport(CommandContext<CommandSourceStack> ctx, String code)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlueprintData data;
        try {
            data = BlueprintShareCodes.decode(code);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("导入失败：" + e.getMessage()));
            return 0;
        }
        if (data.materials().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("导入失败：蓝图不含任何材料"));
            return 0;
        }

        // 材料存在性提示（其它 mod 的材料在本环境可能缺失；仍允许导入，使用时会提示缺料）
        int unknown = 0;
        for (String m : data.materials()) {
            ResourceLocation id = ResourceLocation.tryParse(m);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) unknown++;
        }

        BlueprintLibrary lib = library(player);
        player.setData(QianxiangAttachments.BLUEPRINT_LIBRARY, lib.withAdded(data));

        int total = lib.blueprints().size() + 1;
        final int unknownCount = unknown;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a【蓝图导入】§r「" + data.name() + "」已入库（#" + total + "）"
                        + (unknownCount > 0 ? " §e⚠ 含 " + unknownCount + " 个本环境不存在的材料§r" : "")), false);
        com.qianxiang.QianxiangAdvancements.grant(player, com.qianxiang.QianxiangAdvancements.BLUEPRINT_SHARE);
        return 1;
    }

    private static BlueprintLibrary library(ServerPlayer player) {
        try {
            return player.getData(QianxiangAttachments.BLUEPRINT_LIBRARY);
        } catch (Throwable t) {
            return BlueprintLibrary.empty();
        }
    }
}
