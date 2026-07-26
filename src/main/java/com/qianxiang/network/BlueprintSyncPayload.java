package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.blueprint.BlueprintData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：同步玩家的蓝图列表。
 * <p>
 * 在打开 UI、保存蓝图或使用蓝图后发送，确保客户端列表最新。
 */
public record BlueprintSyncPayload(List<BlueprintData> blueprints) implements CustomPacketPayload {

    public static final Type<BlueprintSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "blueprint_sync"));

    public static final StreamCodec<FriendlyByteBuf, BlueprintSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, BlueprintData.STREAM_CODEC), BlueprintSyncPayload::blueprints,
            BlueprintSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
