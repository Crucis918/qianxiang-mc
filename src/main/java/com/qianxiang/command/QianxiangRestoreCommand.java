package com.qianxiang.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.qianxiang.Qianxiang;
import com.qianxiang.cap.ProficiencyHelper;
import com.qianxiang.cap.ProficiencyTrack;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.spell.CustomSpell;
import com.qianxiang.spell.SpellCastHandler;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 恢复/管理命令（OP 2 级）：数据被误清后的手工补救通道。
 * <p>
 * 背景：游玩检测装置（PlaytestHandler）的 S 场景复位曾在用户真实存档里清掉
 * 玩家自学的法术与熟练度。装置已改用「游玩检测」专用存档，本命令是兜底恢复手段：
 * <ul>
 *   <li>{@code /qianxiang learn <spellId>}：学一个预置法术（{@link CustomSpell#byId}
 *       注册表；材料/AI 生成法术不入表，学不了——它们的数据在卷轴组件里）；
 *       {@code /qianxiang learn all} 学全部预置。幂等，已学过不重复。</li>
 *   <li>{@code /qianxiang prof addxp <track> <amount>}：给指定轨（combat/arcane/craft）
 *       加熟练度经验；未开启修行时先自动开启（被清的存档连 unlocked 也没了）。</li>
 * </ul>
 * 自注册：靠 {@link EventBusSubscriber} 挂 GAME 总线，与 kit/dim/shot 同体系。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class QianxiangRestoreCommand {

    private QianxiangRestoreCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("qianxiang")
                .then(Commands.literal("learn")
                        // 作弊性管理命令：直接改写玩家法术/熟练度数据，多人服必须限 OP
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("spellId", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("all");
                                    for (ResourceLocation id : CustomSpell.registry().keySet()) {
                                        builder.suggest(id.toString());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(QianxiangRestoreCommand::handleLearn)))
                .then(Commands.literal("prof")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("addxp")
                                .then(Commands.argument("track", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (ProficiencyTrack track : ProficiencyTrack.values()) {
                                                builder.suggest(track.id());
                                            }
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("amount",
                                                        IntegerArgumentType.integer(1))
                                                .executes(QianxiangRestoreCommand::handleAddXp))))));
        Qianxiang.LOGGER.info("[Qianxiang] 恢复命令已注册：/qianxiang learn, /qianxiang prof addxp");
    }

    // ============================ /qianxiang learn ============================

    private static int handleLearn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ResourceLocation arg = ResourceLocationArgument.getId(ctx, "spellId");
        // "all" 能被 ResourceLocation 解析（minecraft:all），按字面特判
        boolean all = "minecraft".equals(arg.getNamespace()) && "all".equals(arg.getPath());
        int learned = learnPreset(player, all ? "all" : arg.toString());
        if (learned <= 0) {
            src.sendFailure(Component.translatable("qianxiang.command.learn.unknown", arg.toString()));
            return 0;
        }
        if (all) {
            int count = learned;
            src.sendSuccess(() -> Component.translatable("qianxiang.command.learn.ok_all", count), false);
        } else {
            src.sendSuccess(() -> Component.translatable("qianxiang.command.learn.ok", arg.toString()), false);
        }
        return learned;
    }

    /**
     * learn 命令核心（GameTest 可直接断言）：学预置法术。
     * <p>
     * {@code idOrAll} 为 {@code "all"} 时学全部预置（跳过已学的，返回新学数量）；
     * 否则按预置 id 学单个（已学过幂等返回 1）。
     * </p>
     *
     * @return 新学数量（单个路径恒 1）；0 = 未知/非法 id，已学列表不变
     */
    public static int learnPreset(ServerPlayer player, String idOrAll) {
        var data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        if ("all".equals(idOrAll)) {
            int learned = 0;
            for (CustomSpell spell : CustomSpell.registry().values()) {
                if (!data.hasLearned(spell.id())) {
                    data = data.learn(spell);
                    learned++;
                }
            }
            player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, data);
            SpellCastHandler.sync(player);
            return learned;
        }
        ResourceLocation id = ResourceLocation.tryParse(idOrAll == null ? "" : idOrAll);
        CustomSpell spell = id == null ? null : CustomSpell.byId(id);
        if (spell == null) {
            return 0; // 非法 id：拒绝，已学列表不动
        }
        if (!data.hasLearned(spell.id())) {
            player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, data.learn(spell));
        }
        SpellCastHandler.sync(player);
        return 1;
    }

    // ============================ /qianxiang prof addxp ============================

    private static int handleAddXp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        String rawTrack = StringArgumentType.getString(ctx, "track");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");

        ProficiencyTrack track = null;
        for (ProficiencyTrack t : ProficiencyTrack.values()) {
            if (t.id().equalsIgnoreCase(rawTrack)) {
                track = t;
                break;
            }
        }
        if (track == null) {
            src.sendFailure(Component.translatable("qianxiang.command.prof.addxp.bad_track", rawTrack));
            return 0;
        }
        // 恢复场景：被清的存档连 unlocked 也没了，不先开启则 addXp 静默不攒
        if (!player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA).unlocked()) {
            ProficiencyHelper.unlock(player);
        }
        ProficiencyHelper.addXp(player, track, amount);
        var after = player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        ProficiencyTrack finalTrack = track;
        src.sendSuccess(() -> Component.translatable("qianxiang.command.prof.addxp.ok",
                finalTrack.id(), amount, after.levelOf(finalTrack), after.xpOf(finalTrack)), false);
        return amount;
    }
}
