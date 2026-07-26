package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：使用指定索引的蓝图。
 * <p>
 * 服务端收到后尝试把蓝图材料从玩家背包放入锻造台材料槽，
 * 成功或失败都会给玩家提示并同步最新蓝图列表。
 */
public record BlueprintUsePayload(int index) implements CustomPacketPayload {

    public static final Type<BlueprintUsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "blueprint_use"));

    public static final StreamCodec<FriendlyByteBuf, BlueprintUsePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BlueprintUsePayload::index,
            BlueprintUsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
