package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：请求同步当前玩家的蓝图列表。
 * <p>
 * 服务端回以 {@link BlueprintSyncPayload}。
 */
public record BlueprintListRequestPayload() implements CustomPacketPayload {

    public static final Type<BlueprintListRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "blueprint_list_request"));

    public static final StreamCodec<FriendlyByteBuf, BlueprintListRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new BlueprintListRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
