package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：动作编辑器的「AI 编排」请求（自然语言 → 动作集 JSON）。
 * 服务端 {@code MovesetComposeHandler} 走 {@code MovesetComposer}
 * （AI 在线编排 / 关键词兜底拼装），结果经 {@code MovesetComposeResultPayload} 回推。
 */
public record MovesetComposePayload(String request) implements CustomPacketPayload {

    public static final Type<MovesetComposePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "moveset_compose"));

    public static final StreamCodec<FriendlyByteBuf, MovesetComposePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(120), MovesetComposePayload::request,
                    MovesetComposePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
