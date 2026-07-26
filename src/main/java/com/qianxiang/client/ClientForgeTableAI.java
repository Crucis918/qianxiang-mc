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

    private ClientForgeTableAI() {}

    /** 由 {@link com.qianxiang.network.QianxiangPayloads} 的网络 handler 调用。 */
    public static void receive(AiResponsePayload payload) {
        boolean fallback = !payload.proposals().isEmpty()
                && payload.proposals().stream().allMatch(PhaseAIRecipeService.RecipeProposal::isFallback);
        lastResult = new AiResult(payload.proposals(), payload.confirmMessage(), fallback,
                payload.suggestQuestions() == null ? List.of() : payload.suggestQuestions());
        if (onResult != null) {
            onResult.accept(lastResult);
        }
        // AI 响应到达即把「选中第 0 条」同步给服务端锻造台；
        // 玩家改选其他方案时 applyProposal 会再报一次索引（后者覆盖前者）。
        reportProposalIndex(payload.proposals().isEmpty()
                ? SpellJsonReportPayload.NONE : 0);
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
