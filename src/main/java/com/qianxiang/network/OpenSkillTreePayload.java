package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：打开技能树界面（下一步客户端接 GUI；本步先注册）。
 */
public record OpenSkillTreePayload() implements CustomPacketPayload {

    public static final Type<OpenSkillTreePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "open_skill_tree"));

    public static final StreamCodec<FriendlyByteBuf, OpenSkillTreePayload> STREAM_CODEC =
            StreamCodec.unit(new OpenSkillTreePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
