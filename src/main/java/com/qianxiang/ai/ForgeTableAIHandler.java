package com.qianxiang.ai;

import com.qianxiang.Qianxiang;
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
@net.neoforged.fml.common.EventBusSubscriber(modid = Qianxiang.MOD_ID)
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
                    player, "ai_request", (long) (AI_REQUEST_COOLDOWN_MS
                            * com.qianxiang.cap.ProficiencyHelper.aiCooldownMult(player)))) {
                // WQ-74：限流命中必须回包+提示——此前静默 return，客户端状态条永卡
                // PARSING 只能关界面重开。空响应让客户端退出 PARSING（监听映射空提案→IDLE）。
                replyRateLimited(player, context, payload.seq());
                return;
            }
            setBlockEntityState(player, ForgeTableBlockEntity.STATE_PARSING);

            // 记下方块坐标与维度：回包时按坐标定位方块复位，而不是靠
            // 「玩家此刻还开着同一个菜单」——玩家中途关 GUI、下线、或去开了
            // 另一台锻造台，原来的写法都会把复位打空或打到错的方块上。
            var menu = (com.qianxiang.menu.ForgeTableMenu) player.containerMenu;
            net.minecraft.core.BlockPos tablePos = null;
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim = null;
            if (menu.getContainer() instanceof ForgeTableBlockEntity be) {
                tablePos = be.getBlockPos();
                dim = player.level().dimension();
            }
            submitAiTask(payload, context, tablePos, dim);
        });
    }

    /** 限流命中时的回包：空提案响应（客户端退出 PARSING）+ actionbar 提示「请稍候」。
     *  seq 原样带回请求序号——客户端只认最新序号的响应（WQ-76），不带 seq 会被当成落后响应丢弃。 */
    static void replyRateLimited(net.minecraft.world.entity.player.Player player,
                                 IPayloadContext context, int seq) {
        try {
            context.reply(new AiResponsePayload(seq, java.util.List.of(), "", java.util.List.of()));
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 限流回包失败（玩家已断线？）：{}", t.toString());
        }
        if (player != null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "qianxiang.ai.rate_limited"), true);
        }
    }

    /** 真正把 AI 任务丢进后台线程（已通过菜单校验与限流）。 */
    private static void submitAiTask(AiRequestPayload payload, IPayloadContext context,
                                     net.minecraft.core.BlockPos tablePos,
                                     net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        AI_EXECUTOR.submit(() -> {
            PhaseAIRecipeService.RecipeResult result;
            try {
                result = PhaseAIRecipeService.ask(payload,
                        context.player() instanceof net.minecraft.server.level.ServerPlayer sp ? sp : null);
            } catch (Throwable t) {
                // ask 自身已兜底，理论不会到这里；防御性给空结果
                result = new PhaseAIRecipeService.RecipeResult(java.util.List.of(), "", true);
            }
            final var finalResult = result;
            // reqId 在 AI 线程上才有效（WQ-71 病根：主线程读 ThreadLocal 拿到 null 现造假 id）——
            // 这里读出真 id，随响应下发给客户端暂存，放料回传时闭环「建议 vs 采纳」。
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
     * 把本次 AI 算出的提案登记到锻造台（按发起玩家的 UUID）。
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
            if (!(level.getBlockEntity(pos) instanceof ForgeTableBlockEntity be)) return;

            var proposals = new java.util.ArrayList<ForgeTableBlockEntity.AiProposal>();
            for (var rp : result.proposals()) {
                String spellJson = rp.hasSpell() ? rp.spellJson() : "";
                String name = spellJson.isEmpty()
                        ? "" : com.qianxiang.spell.CustomSpell.sanitizeCustomName(
                                com.qianxiang.spell.CustomSpell.extractName(spellJson));
                String movesetJson = rp.hasMoveset() ? rp.movesetJson() : "";
                proposals.add(new ForgeTableBlockEntity.AiProposal(spellJson, name, movesetJson));
            }
            be.setProposals(sp.getUUID(), proposals);
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 登记 AI 提案失败（无害）：{}", t.toString());
        }
    }

    /** 按坐标复位锻造台状态（不依赖玩家当前打开的菜单）。 */
    private static void resetTableByPos(IPayloadContext context,
                                        net.minecraft.core.BlockPos pos,
                                        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        if (pos == null || dim == null) return;
        try {
            var player = context.player();
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            var level = sp.server.getLevel(dim);
            if (level == null || !level.isLoaded(pos)) return;   // 区块已卸载：下次加载时状态本就归一
            if (level.getBlockEntity(pos) instanceof ForgeTableBlockEntity be) {
                be.updateCraftingState();
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 锻造台状态复位失败（无害）：{}", t.toString());
        }
    }

    /** 关服时停掉 AI 线程池并丢弃排队任务，避免持有旧世界对象图。 */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        AI_EXECUTOR.shutdownNow();
        AIGateway.shutdownLogExecutor(); // WQ-71：jsonl 落盘线程一并停
        com.qianxiang.util.PlayerRateLimiter.clearAll();
        Qianxiang.LOGGER.info("[Qianxiang] AI 后台线程池已停止。");
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
