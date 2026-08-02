package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import com.qianxiang.ai.PhaseAIRecipeService;
import com.qianxiang.network.AiResponsePayload;
import com.qianxiang.network.SpellJsonReportPayload;
import com.qianxiang.spell.CustomSpell;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Consumer;

/**
 * 客户端接收 AI 推荐结果。
 * <p>
 * 收到 {@link AiResponsePayload} 后，通过回调把结果交给当前打开的
 * {@link ForgeTableScreen} 刷新推荐显示区。
 * <p>
 * 由于网络包在独立线程处理，这里只是把数据缓存；渲染线程的 screen 每帧读取。
 * <p>
 * 同时承担「AI 输出回传」：把响应附带的 spellJson + 自定义名经
 * {@link SpellJsonReportPayload} 同步给服务端锻造台（产物生成侧消费）。
 */
public final class ClientForgeTableAI {

    private static volatile AiResult lastResult = null;
    private static Consumer<AiResult> onResult = null;
    /** 最近一次响应的飞轮 reqId（WQ-71）：放料时随 {@code AiPlaceMaterialsPayload} 回传。 */
    private static volatile String lastReqId = "";
    /** 最近一次响应的 AI 状态（"" 在线 / offline / auth=密钥无效 / endpoint）。 */
    public static volatile String aiStatus = "";

    /** 客户端自增请求序号（仅客户端主线程读写）：服务端原样带回，用于丢弃落后响应（WQ-76）。 */
    private static int nextSeq = 0;
    /** 已接受响应的最大序号：序号比它小的响应一律丢弃（如被限流空包插队的真实响应）。 */
    private static int lastAcceptedSeq = 0;
    /**
     * 玩家显式点选过的方案索引（{@link SpellJsonReportPayload#NONE} = 无有效选择）。
     * 粘性：新响应到达<b>不</b>重置它——服务端保留的是该选择解析出的法术/名称，
     * 与台上材料（同一张卡放入的）保持一致；若每次响应都改报 0，
     * 产物法术会变成新列表第 0 条而材料还是旧卡的（WQ-76）。
     */
    private static int selectedIndex = SpellJsonReportPayload.NONE;

    private ClientForgeTableAI() {}

    /** 发新 AI 请求前取一个自增序号（随 {@link com.qianxiang.network.AiRequestPayload} 上行）。 */
    public static int nextRequestSeq() {
        return ++nextSeq;
    }

    /** 当前是否有玩家显式点选的有效选择（界面高亮/调试可见性用）。 */
    public static int selectedIndex() {
        return selectedIndex;
    }

    /** 由 {@link com.qianxiang.network.QianxiangPayloads} 的网络 handler 调用。 */
    public static void receive(AiResponsePayload payload) {
        // 落后响应丢弃：限流空包会插队先到，若随后再放行序号更小的真实响应，
        // 旧结果会盖掉新状态（WQ-76）。
        if (payload.seq() < lastAcceptedSeq) {
            return;
        }
        lastAcceptedSeq = payload.seq();
        lastReqId = payload.reqId() == null ? "" : payload.reqId();
        aiStatus = payload.aiStatus() == null ? "" : payload.aiStatus();
        boolean fallback = !payload.proposals().isEmpty()
                && payload.proposals().stream().allMatch(PhaseAIRecipeService.RecipeProposal::isFallback);
        lastResult = new AiResult(payload.proposals(), payload.confirmMessage(), fallback,
                payload.suggestQuestions() == null ? List.of() : payload.suggestQuestions());
        if (onResult != null) {
            onResult.accept(lastResult);
        }
        // 只在无有效选择时才报 0：玩家已点过卡片的话，服务端保留的就是那张卡的
        // 法术/名称，与台上材料一致，不能被新响应改报成新列表第 0 条（WQ-76）。
        // 自动报 0 不算「用户选择」，不写入 selectedIndex。
        if (selectedIndex == SpellJsonReportPayload.NONE) {
            sendIndex(payload.proposals().isEmpty()
                    ? SpellJsonReportPayload.NONE : 0);
        }
    }

    /** 当前打开的锻造台 UI 注册一个回调，收到结果时刷新。 */
    public static void setListener(Consumer<AiResult> listener) {
        onResult = listener;
        if (lastResult != null && listener != null) {
            listener.accept(lastResult);
        }
    }

    /** 关闭 UI 时清掉回调，避免内存泄漏。 */
    public static void clearListener() {
        onResult = null;
    }

    /**
     * 切换世界/断线时的彻底重置：连同缓存的 AI 结果一并丢弃。
     * <p>只清 listener 不够——{@code setListener} 会立刻回放 {@code lastResult}，
     * 于是新世界的锻造台会显示上一个世界的推荐，点「应用」还会把它提交给新服务端。
     */
    public static void resetForWorldChange() {
        onResult = null;
        lastResult = null;
        lastReqId = "";
        aiStatus = "";
        selectedIndex = SpellJsonReportPayload.NONE;
        nextSeq = 0;
        lastAcceptedSeq = 0;
    }

    /** 最近一次响应的 reqId（放料回传用；无则 ""）。 */
    public static String lastReqId() {
        return lastReqId;
    }

    /** 读取最近一次结果（screen 每帧用）。 */
    public static AiResult getLastResult() {
        return lastResult;
    }

    /**
     * 告诉服务端「我选了第几条 AI 方案」。
     * <p>
     * 只发索引不发内容：法术/动作的真身由服务端在算出提案时自己留底，
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
            Qianxiang.LOGGER.warn("[Qianxiang] 回传 AI spellJson 失败（不影响推荐显示）", t);
        }
    }

    public record AiResult(List<PhaseAIRecipeService.RecipeProposal> proposals,
                           String confirmMessage, boolean fallback,
                           List<String> suggestQuestions) {
        /** 兼容旧三参构造：suggestQuestions 空 = 无反问。 */
        public AiResult(List<PhaseAIRecipeService.RecipeProposal> proposals,
                        String confirmMessage, boolean fallback) {
            this(proposals, confirmMessage, fallback, List.of());
        }
    }
}
