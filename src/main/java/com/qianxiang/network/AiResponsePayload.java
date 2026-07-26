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
 */
public record AiResponsePayload(List<PhaseAIRecipeService.RecipeProposal> proposals,
                                String confirmMessage,
                                List<String> suggestQuestions) implements CustomPacketPayload {

    /** 兼容旧两参构造：suggestQuestions 空 = 无反问。 */
    public AiResponsePayload(List<PhaseAIRecipeService.RecipeProposal> proposals, String confirmMessage) {
        this(proposals, confirmMessage, List.of());
    }

    public static final Type<AiResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "ai_response"));

    public static final StreamCodec<FriendlyByteBuf, AiResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.collection(ArrayList::new, PhaseAIRecipeService.RecipeProposal.STREAM_CODEC),
                    AiResponsePayload::proposals,
                    ByteBufCodecs.STRING_UTF8, AiResponsePayload::confirmMessage,
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), AiResponsePayload::suggestQuestions,
                    AiResponsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
