package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：请求洗点（扣 10 绿宝石，清空已分配，点数按轨等级全额返还）。
 * 500ms 节流在 handler；价格与返还口径见 {@code ProficiencyHelper#respec}。
 */
public record RespecPayload() implements CustomPacketPayload {

    public static final Type<RespecPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "proficiency_respec"));

    public static final StreamCodec<FriendlyByteBuf, RespecPayload> STREAM_CODEC =
            StreamCodec.unit(new RespecPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
