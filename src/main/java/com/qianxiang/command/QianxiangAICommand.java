package com.qianxiang.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.qianxiang.Qianxiang;
import com.qianxiang.ai.MaterialLibrary;
import com.qianxiang.ai.PhaseAIRecipeService;
import com.qianxiang.ai.PhaseAIRecipeService.RecipeProposal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /qianxiang ask &lt;自然语言需求&gt; 命令。
 * <p>
 * 自注册：靠 {@link EventBusSubscriber} 挂 GAME 总线，
 * <b>不修改任何现有文件</b>（包括 {@link Qianxiang} 主类）。
 * <p>
 * 执行时调 {@link PhaseAIRecipeService#ask}，把材料清单（registry 名 + 中文名）+
 * 估强度 powerScore + 一句话说明反馈给玩家。所有异常都被 PhaseAIRecipeService 吞掉，
 * 命令绝不会因 Ollama 缺失而崩——最多走关键词基础配方。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class QianxiangAICommand {

    private QianxiangAICommand() {}

    /** AI 命令共用的后台单线程执行器：HTTP 调用不落在服务器主线程上。 */
    private static final java.util.concurrent.ExecutorService EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qianxiang-ai-command");
                t.setDaemon(true);
                return t;
            });

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("qianxiang")
                        .then(Commands.literal("ask")
                                .then(Commands.argument("want", StringArgumentType.greedyString())
                                        .executes(QianxiangAICommand::handleAsk)))
                        .then(Commands.literal("ai")
                                .then(Commands.literal("status")
                                        .executes(QianxiangAICommand::handleStatus))
                                .then(Commands.literal("clearcache")
                                        .executes(QianxiangAICommand::handleClearCache))));
        Qianxiang.LOGGER.info("[Qianxiang] AI 命令已注册：/qianxiang ask <想要什么>；/qianxiang ai status|clearcache");
    }

    /** /qianxiang ai status —— AI 网关状态（provider/模型/计数，不含 apiKey）。 */
    private static int handleStatus(CommandContext<CommandSourceStack> ctx) {
        for (String line : com.qianxiang.ai.AIGateway.statusSummary().split("\\R")) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7" + line + "§r"), false);
        }
        return 1;
    }

    /** /qianxiang ai clearcache —— 清空 AI 响应缓存（换模型/调 prompt 后用）。 */
    private static int handleClearCache(CommandContext<CommandSourceStack> ctx) {
        int n = com.qianxiang.ai.AIGateway.clearCache();
        ctx.getSource().sendSuccess(() -> Component.literal("§a已清空 AI 响应缓存（" + n + " 条）。§r"), false);
        return n;
    }

    /**
     * /qianxiang ask 的执行体。HTTP 调用移到后台线程（避免卡服务器主线程），
     * 回包经主线程发送。命令立即返回 1（已受理）。
     */
    private static int handleAsk(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        String want = StringArgumentType.getString(ctx, "want");
        var server = src.getServer();

        EXECUTOR.submit(() -> {
            RecipeProposal proposal;
            try {
                proposal = PhaseAIRecipeService.ask(want); // 永不抛；再包一层防御
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] /qianxiang ask 意外异常", t);
                server.execute(() -> src.sendFailure(Component.literal("AI 配方大脑出错，请稍后再试。")));
                return;
            }
            server.execute(() -> reply(src, want, proposal));
        });
        return 1;
    }

    /** 在服务器主线程把结果打给玩家。 */
    private static void reply(CommandSourceStack src, String want, RecipeProposal proposal) {
        if (proposal == null || proposal.materialNames().isEmpty()) {
            src.sendFailure(Component.literal("未能生成材料清单（材料库为空或全部无效）。"));
            return;
        }

        // —— 反馈：逐行把材料清单打给玩家 ——
        src.sendSuccess(() -> Component.literal("§6【千相·AI 配方】§r 针对：§e" + want + "§r"), false);

        for (String name : proposal.materialNames()) {
            // 顺手把 registry name 翻成可翻译 Component（中文名+档位+相），发到客户端本地化渲染。
            // 服务端不 .getString() 取死串，让中文玩家看 zh_cn.json 的「烬铁 (稀有{火})」。
            var entryOpt = MaterialLibrary.find(name);
            if (entryOpt.isPresent()) {
                var entry = entryOpt.get();
                src.sendSuccess(() -> Component.literal("§a- §r").append(entry.displayNameComponent()), false);
            } else {
                // 理论不会走到（find 已校验），防御：原样打 registry 名
                src.sendSuccess(() -> Component.literal("§a- §r§7" + name + "§r"), false);
            }
        }

        double power = proposal.estimatedPower();
        String summary = proposal.summary() == null ? "" : proposal.summary();
        String aiTag = proposal.isFallback() ? "§7基础配方（Ollama 未连接）§r" : "§bAI 推荐§r";

        src.sendSuccess(() -> Component.literal(
                aiTag + " §7|§r 估强度 §e" + String.format("%.2f", power) + "§r"), false);
        src.sendSuccess(() -> {
            Component summaryComp = summary.startsWith("qianxiang.")
                    ? Component.translatable(summary)
                    : Component.literal(summary);
            return Component.literal("§7").append(summaryComp).append("§r");
        }, false);
    }
}
