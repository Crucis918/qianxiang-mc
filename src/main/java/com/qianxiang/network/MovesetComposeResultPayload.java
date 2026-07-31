package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：「AI 编排」结果（动作集 JSON；空串 = 失败/需求为空）。
 * 编辑器收包后把序列回填进当前编排，玩家可再手动微调后应用。
 */
public record MovesetComposeResultPayload(String movesetJson) implements CustomPacketPayload {

    public static final Type<MovesetComposeResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "moveset_compose_result"));

    public static final StreamCodec<FriendlyByteBuf, MovesetComposeResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(2048), MovesetComposeResultPayload::movesetJson,
                    MovesetComposeResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
