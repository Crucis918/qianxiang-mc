package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.ai.PhaseAIRecipeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：AI 对玩家自然语言需求的推荐结果。
 * <p>
 * 共享契约：返回 {@code List<RecipeProposal> proposals} 与 {@code String confirmMessage}。
 * 另附 {@code List<String> suggestQuestions}（可空）：需求模糊时 AI 给玩家的反问选项词，
 * 客户端渲染成按钮，点选后追加到输入框重新问 AI。
 * 客户端 {@link com.qianxiang.client.ClientForgeTableAI} 接收后
 * 更新 {@link com.qianxiang.client.ForgeTableScreen} 的推荐显示区。
 * {@code seq} 原样带回请求的序号（WQ-76）：客户端据此丢弃落后响应。
 * {@code reqId} 本次请求的飞轮 id（WQ-71）：服务端在 AI 线程生成、随响应下发，
 * 客户端暂存并随 {@code AiPlaceMaterialsPayload} 回传——「建议 vs 采纳」日志
 * 靠它串起来，不走 ThreadLocal（主线程读到的是假 id）。
 */
public record AiResponsePayload(int seq,
                                String reqId,
                                List<PhaseAIRecipeService.RecipeProposal> proposals,
                                String confirmMessage,
                                List<String> suggestQuestions,
                                String aiStatus) implements CustomPacketPayload {

    /** 兼容旧五参构造（无状态）：aiStatus = ""（在线）。 */
    public AiResponsePayload(int seq, String reqId,
                             List<PhaseAIRecipeService.RecipeProposal> proposals,
                             String confirmMessage,
                             List<String> suggestQuestions) {
        this(seq, reqId, proposals, confirmMessage, suggestQuestions, "");
    }

    /** 兼容旧四参构造（无 reqId）：reqId = ""。 */
    public AiResponsePayload(int seq,
                             List<PhaseAIRecipeService.RecipeProposal> proposals,
                             String confirmMessage,
                             List<String> suggestQuestions) {
        this(seq, "", proposals, confirmMessage, suggestQuestions, "");
    }

    /** 兼容旧三参构造（无序号/reqId）：seq = 0，reqId = ""。 */
    public AiResponsePayload(List<PhaseAIRecipeService.RecipeProposal> proposals,
                             String confirmMessage,
                             List<String> suggestQuestions) {
        this(0, "", proposals, confirmMessage, suggestQuestions);
    }

    /** 兼容旧两参构造：suggestQuestions 空 = 无反问。 */
    public AiResponsePayload(List<PhaseAIRecipeService.RecipeProposal> proposals, String confirmMessage) {
        this(0, "", proposals, confirmMessage, List.of());
    }

    public static final Type<AiResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "ai_response"));

    public static final StreamCodec<FriendlyByteBuf, AiResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, AiResponsePayload::seq,
                    ByteBufCodecs.stringUtf8(64), AiResponsePayload::reqId,
                    ByteBufCodecs.collection(ArrayList::new, PhaseAIRecipeService.RecipeProposal.STREAM_CODEC),
                    AiResponsePayload::proposals,
                    ByteBufCodecs.STRING_UTF8, AiResponsePayload::confirmMessage,
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), AiResponsePayload::suggestQuestions,
                    ByteBufCodecs.stringUtf8(16), AiResponsePayload::aiStatus,
                    AiResponsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
