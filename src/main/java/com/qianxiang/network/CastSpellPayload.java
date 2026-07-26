package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：玩家按下法术键，请求施放当前手持法器上的法术。
 * <p>
 * 包体为空：服务端直接读取玩家主手物品的 {@code qianxiang:spell} 组件即可。
 */
public record CastSpellPayload() implements CustomPacketPayload {

    public static final Type<CastSpellPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "cast_spell"));

    public static final StreamCodec<FriendlyByteBuf, CastSpellPayload> STREAM_CODEC =
            StreamCodec.unit(new CastSpellPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
