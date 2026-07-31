package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.ai.MovesetComposer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端处理「AI 编排」请求（动作编辑器）。
 * <p>
 * 与锻造台 AI 同一骨架：主线程限流 → 后台单线程跑
 * {@link MovesetComposer#composeMoveset}（AI HTTP 绝不在主线程）→ 回包结果。
 * 兜底路径是纯本地关键词拼装（零 HTTP），AI 掉线也永远有回应。
 * </p>
 */
public final class MovesetComposeHandler {

    private MovesetComposeHandler() {}

    /** 同一玩家两次编排请求的最小间隔。 */
    private static final long COMPOSE_COOLDOWN_MS = 2_000L;

    /** 单线程后台执行器（与 qianxiang-ai 同规，避免并发打到本地 Ollama）。 */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qianxiang-moveset-ai");
        t.setDaemon(true);
        return t;
    });

    public static void handle(MovesetComposePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null || player.level().isClientSide) return;
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "moveset_compose", COMPOSE_COOLDOWN_MS)) {
                return;
            }
            var sp = player instanceof net.minecraft.server.level.ServerPlayer s ? s : null;
            EXECUTOR.submit(() -> {
                String result;
                try {
                    result = MovesetComposer.composeMoveset(payload.request(), sp);
                } catch (Throwable t) {
                    Qianxiang.LOGGER.warn("[Qianxiang] 动作编排失败：{}", t.toString());
                    result = null;
                }
                final String json = result == null ? "" : result;
                try {
                    context.enqueueWork(() -> {
                        try {
                            context.reply(new MovesetComposeResultPayload(json));
                        } catch (Throwable t) {
                            Qianxiang.LOGGER.debug("[Qianxiang] 编排回包失败（玩家已断线？）：{}", t.toString());
                        }
                    });
                } catch (Throwable t) {
                    Qianxiang.LOGGER.debug("[Qianxiang] 编排回调入队失败（服务端已停止？）：{}", t.toString());
                }
            });
        });
    }
}
