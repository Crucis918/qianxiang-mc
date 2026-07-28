package com.qianxiang.ai;

import com.qianxiang.Qianxiang;
import com.qianxiang.block.AlchemyTableBlockEntity;
import com.qianxiang.menu.AlchemyTableMenu;
import com.qianxiang.network.AiRequestPayload;
import com.qianxiang.network.AiResponsePayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端处理炼金台的 AI 请求（type=magic，产出带 spellJson 的卷轴方案）。
 * <p>
 * 与 {@link ForgeTableAIHandler} 同一骨架：主线程校验 + 限流 + 标记 PARSING →
 * 后台线程跑 AI（绝不在主线程同步 HTTP）→ 主线程回包、登记提案、按坐标复位。
 * 入口由 {@code QianxiangPayloads} 按「玩家当前打开的菜单类型」分派进来。
 * </p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class AlchemyTableAIHandler {

    /** 单线程后台执行器，AI 请求串行处理，避免并发打到本地 Ollama。 */
    private static final ExecutorService AI_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qianxiang-alchemy-ai");
        t.setDaemon(true);
        return t;
    });

    private AlchemyTableAIHandler() {}

    /** 同一玩家两次 AI 请求的最小间隔（与锻造台同一把限流锁）。 */
    private static final long AI_REQUEST_COOLDOWN_MS = 3_000L;

    /**
     * 主线程入口（调用方已确认玩家正打开 {@link AlchemyTableMenu}，且已在主线程）。
     * 校验 + 限流 + 标记 PARSING，然后把 AI 任务丢进后台线程。
     */
    public static void handleOnMainThread(AiRequestPayload payload, IPayloadContext context) {
        var player = context.player();
        if (!(player.containerMenu instanceof AlchemyTableMenu menu)) {
            return;
        }
        if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                player, "ai_request", (long) (AI_REQUEST_COOLDOWN_MS
                        * com.qianxiang.cap.ProficiencyHelper.aiCooldownMult(player)))) {
            // WQ-74：限流命中必须回包+提示（同锻造台）——否则客户端状态条永卡 PARSING。
            // seq 原样带回（WQ-76）：不带 seq 会被客户端当成落后响应丢弃。
            try {
                context.reply(new AiResponsePayload(payload.seq(), java.util.List.of(), "", java.util.List.of()));
            } catch (Throwable t) {
                Qianxiang.LOGGER.debug("[Qianxiang] 限流回包失败（玩家已断线？）：{}", t.toString());
            }
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "qianxiang.ai.rate_limited"), true);
            return;
        }
        if (menu.getContainer() instanceof AlchemyTableBlockEntity be) {
            be.setCraftingState(AlchemyTableBlockEntity.STATE_PARSING);
        }

        // 记下方块坐标与维度：回包时按坐标定位方块复位，而不是靠
        // 「玩家此刻还开着同一个菜单」（中途关 GUI/下线/换台子都会打空或打错）。
        net.minecraft.core.BlockPos tablePos = null;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim = null;
        if (menu.getContainer() instanceof AlchemyTableBlockEntity be) {
            tablePos = be.getBlockPos();
            dim = player.level().dimension();
        }
        submitAiTask(payload, context, tablePos, dim);
    }

    /** 真正把 AI 任务丢进后台线程（已通过菜单校验与限流）。 */
    private static void submitAiTask(AiRequestPayload payload, IPayloadContext context,
                                     net.minecraft.core.BlockPos tablePos,
                                     net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        AI_EXECUTOR.submit(() -> {
            PhaseAIRecipeService.RecipeResult result;
            try {
                result = PhaseAIRecipeService.ask(payload);
            } catch (Throwable t) {
                // ask 自身已兜底，理论不会到这里；防御性给空结果
                result = new PhaseAIRecipeService.RecipeResult(java.util.List.of(), "", true);
            }
            final var finalResult = result;
            // reqId 在 AI 线程上才有效（WQ-71，与锻造台同构）：读出真 id 随响应下发。
            final String reqId = AIGateway.currentRequestId();
            try {
                AIGateway.logRequest(reqId, payload.request(),
                        context.player() == null ? "" : context.player().getUUID().toString(),
                        finalResult.proposals().size(),
                        PhaseAIRecipeService.lastDroppedMaterials(),
                        finalResult.fallback() ? PhaseAIRecipeService.lastFallbackReason() : "");
            } catch (Throwable t) {
                Qianxiang.LOGGER.debug("[Qianxiang] 飞轮请求日志失败（无害）：{}", t.toString());
            }
            try {
                context.enqueueWork(() -> {
                    try {
                        context.reply(new AiResponsePayload(
                                payload.seq(),
                                reqId,
                                finalResult.proposals(),
                                finalResult.confirmMessage() == null ? "" : finalResult.confirmMessage(),
                                finalResult.suggestQuestions() == null
                                        ? java.util.List.of() : finalResult.suggestQuestions()
                        ));
                    } catch (Throwable t) {
                        // 玩家可能已断线：回包失败不该影响方块复位
                        Qianxiang.LOGGER.debug("[Qianxiang] AI 回包失败（玩家已断线？）：{}", t.toString());
                    }
                    // 登记服务端权威提案副本：客户端随后只能回传「选第几条」。
                    registerProposals(context, tablePos, dim, finalResult);
                    resetTableByPos(context, tablePos, dim);
                });
            } catch (Throwable t) {
                // 服务端可能已停止：任务被拒是正常的
                Qianxiang.LOGGER.debug("[Qianxiang] AI 回调入队失败（服务端已停止？）：{}", t.toString());
            }
        });
    }

    /**
     * 把本次 AI 算出的提案登记到炼金台（按发起玩家的 UUID）。
     * <p>这是信任边界的服务端一侧：内容留在服务端，客户端只说「我选第几条」。
     */
    private static void registerProposals(IPayloadContext context,
                                          net.minecraft.core.BlockPos pos,
                                          net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim,
                                          PhaseAIRecipeService.RecipeResult result) {
        if (pos == null || dim == null || result == null) return;
        try {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            var level = sp.server.getLevel(dim);
            if (level == null || !level.isLoaded(pos)) return;
            if (!(level.getBlockEntity(pos) instanceof AlchemyTableBlockEntity be)) return;

            var proposals = new java.util.ArrayList<AlchemyTableBlockEntity.AiProposal>();
            for (var rp : result.proposals()) {
                String spellJson = rp.hasSpell() ? rp.spellJson() : "";
                String name = spellJson.isEmpty()
                        ? "" : com.qianxiang.spell.CustomSpell.sanitizeCustomName(
                                com.qianxiang.spell.CustomSpell.extractName(spellJson));
                proposals.add(new AlchemyTableBlockEntity.AiProposal(spellJson, name));
            }
            be.setProposals(sp.getUUID(), proposals);
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 登记炼金 AI 提案失败（无害）：{}", t.toString());
        }
    }

    /** 按坐标复位炼金台状态（不依赖玩家当前打开的菜单）。 */
    private static void resetTableByPos(IPayloadContext context,
                                        net.minecraft.core.BlockPos pos,
                                        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        if (pos == null || dim == null) return;
        try {
            var player = context.player();
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            var level = sp.server.getLevel(dim);
            if (level == null || !level.isLoaded(pos)) return;   // 区块已卸载：下次加载时状态本就归一
            if (level.getBlockEntity(pos) instanceof AlchemyTableBlockEntity be) {
                be.updateCraftingState();
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 炼金台状态复位失败（无害）：{}", t.toString());
        }
    }

    /** 关服时停掉 AI 线程池并丢弃排队任务，避免持有旧世界对象图。 */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        AI_EXECUTOR.shutdownNow();
        Qianxiang.LOGGER.info("[Qianxiang] 炼金台 AI 后台线程池已停止。");
    }
}
