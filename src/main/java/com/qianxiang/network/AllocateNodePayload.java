package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：请求分配一个熟练度节点。
 * 校验全部在服务端（unlocked/有点/前置/未点过，见 {@code ProficiencyHelper#allocate}）。
 */
public record AllocateNodePayload(String nodeId) implements CustomPacketPayload {

    /** 节点 id 长度上限。 */
    public static final int MAX_NODE_ID_CHARS = 64;

    public static final Type<AllocateNodePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "allocate_node"));

    public static final StreamCodec<FriendlyByteBuf, AllocateNodePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MAX_NODE_ID_CHARS), AllocateNodePayload::nodeId,
                    AllocateNodePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
