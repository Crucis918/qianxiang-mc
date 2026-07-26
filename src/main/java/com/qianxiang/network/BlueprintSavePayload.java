package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端→服务端：保存当前材料组合为蓝图。
 * <p>
 * 仅发送材料 registry name 列表；服务端会重新调用 {@link com.qianxiang.phase.ForgeComposer#compose}
 * 校验有效性，再写入玩家蓝图库。
 */
public record BlueprintSavePayload(List<String> materials) implements CustomPacketPayload {

    public static final Type<BlueprintSavePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "blueprint_save"));

    public static final StreamCodec<FriendlyByteBuf, BlueprintSavePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), BlueprintSavePayload::materials,
            BlueprintSavePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
