package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：玩家点击「开始创作」按钮，请求启动合成仪式。
 * <p>
 * 包体为空：产物由服务端 compose 校验（不信任客户端），
 * handler 按玩家当前 openMenu 类型分派到两台 BE（与 AI 放料同一分派范式）。
 * </p>
 */
public record RitualStartPayload() implements CustomPacketPayload {

    public static final Type<RitualStartPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "ritual_start"));

    public static final StreamCodec<FriendlyByteBuf, RitualStartPayload> STREAM_CODEC =
            StreamCodec.unit(new RitualStartPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
