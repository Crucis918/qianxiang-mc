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

    /** 客户端自增请求序号（仅客户端主线程读写）：服务端原样带回，用于丢弃落后响应（WQ-76）。 */
    private static int nextSeq = 0;
    /** 已接受响应的最大序号：序号比它小的响应一律丢弃。 */
    private static int lastAcceptedSeq = 0;
    /**
     * 玩家显式点选过的方案索引（{@link SpellJsonReportPayload#NONE} = 无有效选择）。
     * 粘性：新响应到达不重置（服务端保留的是该选择解析出的法术，与台上材料一致，WQ-76）。
     */
    private static int selectedIndex = SpellJsonReportPayload.NONE;

    private ClientAlchemyTableAI() {}

    /** 发新 AI 请求前取一个自增序号（随 {@link com.qianxiang.network.AiRequestPayload} 上行）。 */
    public static int nextRequestSeq() {
        return ++nextSeq;
    }

    /** 由 {@link com.qianxiang.network.QianxiangPayloads} 的网络 handler 按当前界面分派调用。 */
    public static void receive(AiResponsePayload payload) {
        // 落后响应丢弃（WQ-76）：限流空包会插队先到，序号更小的真实响应不得盖回。
        if (payload.seq() < lastAcceptedSeq) {
            return;
        }
        lastAcceptedSeq = payload.seq();
        lastReqId = payload.reqId() == null ? "" : payload.reqId();
        aiStatus = payload.aiStatus() == null ? "" : payload.aiStatus();
        boolean fallback = !payload.proposals().isEmpty()
                && payload.proposals().stream().allMatch(
                        com.qianxiang.ai.PhaseAIRecipeService.RecipeProposal::isFallback);
        lastResult = new ClientForgeTableAI.AiResult(payload.proposals(), payload.confirmMessage(),
                fallback,
                payload.suggestQuestions() == null ? java.util.List.of() : payload.suggestQuestions());
        if (onResult != null) {
            onResult.accept(lastResult);
        }
        // 只在无有效选择时才报 0（WQ-76）；自动报 0 不算用户选择，不写入 selectedIndex。
        if (selectedIndex == SpellJsonReportPayload.NONE) {
            sendIndex(payload.proposals().isEmpty()
                    ? SpellJsonReportPayload.NONE : 0);
        }
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
        lastReqId = "";
        aiStatus = "";
        selectedIndex = SpellJsonReportPayload.NONE;
        nextSeq = 0;
        lastAcceptedSeq = 0;
    }

    /** 最近一次响应的飞轮 reqId（WQ-71：放料回传用；无则 ""）。 */
    private static volatile String lastReqId = "";
    /** 最近一次响应的 AI 状态（"" 在线 / offline / auth=密钥无效 / endpoint）。 */
    public static volatile String aiStatus = "";

    /** 最近一次响应的 reqId（放料回传用；无则 ""）。 */
    public static String lastReqId() {
        return lastReqId;
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
        selectedIndex = index;
        sendIndex(index);
    }

    /** 实际发包（不触碰 {@link #selectedIndex}）：用户选择与「无选择时自动报 0」共用。 */
    private static void sendIndex(int index) {
        try {
            PacketDistributor.sendToServer(new SpellJsonReportPayload(index));
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 回传炼金 AI 提案索引失败（不影响推荐显示）", t);
        }
    }
}
