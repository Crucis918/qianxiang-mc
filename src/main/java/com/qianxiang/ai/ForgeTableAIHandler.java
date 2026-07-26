package com.qianxiang.ai;

import com.qianxiang.block.ForgeTableBlockEntity;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.AiRequestPayload;
import com.qianxiang.network.AiResponsePayload;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端处理锻造台的 AI 请求。
 * <p>
 * AI 调用（Ollama HTTP）可能耗时数秒，<b>绝不能在主线程同步执行</b>——
 * 否则整服冻结 2~7 秒，玩家什么都点不动。
 * 流程：主线程设置 parsing 状态 → 后台线程跑 AI → 主线程回包并恢复状态。
 */
public final class ForgeTableAIHandler {

    /** 单线程后台执行器，AI 请求串行处理，避免并发打到本地 Ollama。 */
    private static final ExecutorService AI_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qianxiang-ai");
        t.setDaemon(true);
        return t;
    });

    private ForgeTableAIHandler() {}

    /** 同一玩家两次 AI 请求的最小间隔（AI 调用昂贵，且队列无界）。 */
    private static final long AI_REQUEST_COOLDOWN_MS = 3_000L;

    public static void handle(AiRequestPayload payload, IPayloadContext context) {
        // 主线程：校验 + 限流 + 标记 parsing（粒子特效）。
        // 校验必须在提交后台任务之前——此前无条件 submit，改造过的客户端不必打开
        // 锻造台就能循环发包，每包一次真实 LLM HTTP + 一次全物品注册表扫描，
        // 而执行器队列是无界的。
        context.enqueueWork(() -> {
            var player = context.player();
            if (!(player.containerMenu instanceof com.qianxiang.menu.ForgeTableMenu)) {
                return;
            }
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "ai_request", AI_REQUEST_COOLDOWN_MS)) {
                return;
            }
            setBlockEntityState(player, ForgeTableBlockEntity.STATE_PARSING);
            submitAiTask(payload, context);
        });
    }

    /** 真正把 AI 任务丢进后台线程（已通过菜单校验与限流）。 */
    private static void submitAiTask(AiRequestPayload payload, IPayloadContext context) {
        AI_EXECUTOR.submit(() -> {
            PhaseAIRecipeService.RecipeResult result;
            try {
                result = PhaseAIRecipeService.ask(payload);
            } catch (Throwable t) {
                // ask 自身已兜底，理论不会到这里；防御性给空结果
                result = new PhaseAIRecipeService.RecipeResult(java.util.List.of(), "", true);
            }
            final var finalResult = result;
            context.enqueueWork(() -> {
                context.reply(new AiResponsePayload(
                        finalResult.proposals(),
                        finalResult.confirmMessage() == null ? "" : finalResult.confirmMessage(),
                        finalResult.suggestQuestions() == null ? java.util.List.of() : finalResult.suggestQuestions()
                ));
                setBlockEntityState(context.player(), -1); // 按容器当前内容自动刷新状态
            });
        });
    }

    private static void setBlockEntityState(Player player, int state) {
        if (player == null) return;
        if (!(player.containerMenu instanceof ForgeTableMenu menu)) return;
        if (!(menu.getContainer() instanceof ForgeTableBlockEntity be)) return;

        if (state < 0) {
            be.updateCraftingState();
        } else {
            be.setCraftingState(state);
        }
    }
}
