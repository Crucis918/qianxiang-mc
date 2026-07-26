package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * AI 服务配置同步包，双向使用：
 * <ul>
 *   <li>客户端→服务端：玩家在「AI 设置」界面点保存，把新配置发给服务端，
 *       服务端写入 {@code config/qianxiang-ai.json} 并立即生效（单机即本地生效）。</li>
 *   <li>服务端→客户端：玩家进服时推送当前配置 / 保存后回执确认，
 *       客户端缓存起来供「AI 设置」界面回填。</li>
 * </ul>
 * 字段契约：
 * <ul>
 *   <li>{@code provider} —— "ollama" 或 "openai"</li>
 *   <li>{@code baseUrl} —— 服务地址</li>
 *   <li>{@code apiKey} —— OpenAI 兼容服务 Key，可为空串</li>
 *   <li>{@code model} —— 模型名</li>
 *   <li>{@code timeoutSeconds} —— 单次请求超时秒数</li>
 * </ul>
 */
public record AiConfigSyncPayload(String provider, String baseUrl, String apiKey,
                                  String model, int timeoutSeconds) implements CustomPacketPayload {

    public static final Type<AiConfigSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "ai_config_sync"));

    public static final StreamCodec<FriendlyByteBuf, AiConfigSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, AiConfigSyncPayload::provider,
                    ByteBufCodecs.STRING_UTF8, AiConfigSyncPayload::baseUrl,
                    ByteBufCodecs.STRING_UTF8, AiConfigSyncPayload::apiKey,
                    ByteBufCodecs.STRING_UTF8, AiConfigSyncPayload::model,
                    ByteBufCodecs.INT, AiConfigSyncPayload::timeoutSeconds,
                    AiConfigSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
