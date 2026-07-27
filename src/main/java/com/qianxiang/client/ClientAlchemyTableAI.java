package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import com.qianxiang.network.AiResponsePayload;
import com.qianxiang.network.SpellJsonReportPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端接收炼金台 AI 推荐结果（{@link ClientForgeTableAI} 的炼金台平行副本）。
 * <p>
 * 收到 {@link AiResponsePayload} 后缓存并通过回调刷新当前打开的
 * {@link AlchemyTableScreen}；同时把「选中第 0 条」经 {@link SpellJsonReportPayload}
 * 回传服务端炼金台（同一「只回传索引」信任边界）。
 * </p>
 */
public final class ClientAlchemyTableAI {

    private static volatile ClientForgeTableAI.AiResult lastResult = null;
    private static java.util.function.Consumer<ClientForgeTableAI.AiResult> onResult = null;

    private ClientAlchemyTableAI() {}

    /** 由 {@link com.qianxiang.network.QianxiangPayloads} 的网络 handler 按当前界面分派调用。 */
    public static void receive(AiResponsePayload payload) {
        boolean fallback = !payload.proposals().isEmpty()
                && payload.proposals().stream().allMatch(
                        com.qianxiang.ai.PhaseAIRecipeService.RecipeProposal::isFallback);
        lastResult = new ClientForgeTableAI.AiResult(payload.proposals(), payload.confirmMessage(),
                fallback,
                payload.suggestQuestions() == null ? java.util.List.of() : payload.suggestQuestions());
        if (onResult != null) {
            onResult.accept(lastResult);
        }
        // AI 响应到达即把「选中第 0 条」同步给服务端炼金台；
        // 玩家改选其他方案时 screen 会再报一次索引（后者覆盖前者）。
        reportProposalIndex(payload.proposals().isEmpty()
                ? SpellJsonReportPayload.NONE : 0);
    }

    /** 当前打开的炼金台 UI 注册一个回调，收到结果时刷新。 */
    public static void setListener(java.util.function.Consumer<ClientForgeTableAI.AiResult> listener) {
        onResult = listener;
        if (lastResult != null && listener != null) {
            listener.accept(lastResult);
        }
    }

    /** 关闭 UI 时清掉回调，避免内存泄漏。 */
    public static void clearListener() {
        onResult = null;
    }

    /** 切换世界/断线时的彻底重置：连同缓存的 AI 结果一并丢弃（同 ClientForgeTableAI）。 */
    public static void resetForWorldChange() {
        onResult = null;
        lastResult = null;
    }

    /** 读取最近一次结果（screen 每帧用）。 */
    public static ClientForgeTableAI.AiResult getLastResult() {
        return lastResult;
    }

    /**
     * 告诉服务端「我选了第几条 AI 方案」。
     * <p>
     * 只发索引不发内容：法术的真身由服务端在算出提案时自己留底，
     * 客户端无从伪造。{@link SpellJsonReportPayload#NONE} = 不使用 AI 结果。
     * 任何异常吞掉——回传失败不该影响客户端 UI。
     */
    public static void reportProposalIndex(int index) {
        try {
            PacketDistributor.sendToServer(new SpellJsonReportPayload(index));
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 回传炼金 AI 提案索引失败（不影响推荐显示）", t);
        }
    }
}
