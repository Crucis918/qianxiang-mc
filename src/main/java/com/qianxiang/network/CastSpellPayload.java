package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：请求施放一个<b>已学</b>法术。
 * <p>
 * 轮盘施法改造后，V 键不再读手上物品：客户端在松开轮盘/点按时把具体
 * spellId 发给服务端，服务端只认明确 id（在 {@code PlayerSpellData.learnedSpells}
 * 里查），空串/非法 id/未学法术一律拒绝（WARN，不给客户端反馈）。
 * </p>
 */
public record CastSpellPayload(String spellId) implements CustomPacketPayload {

    /** spellId 长度上限（namespace:path 远小于此）。 */
    public static final int MAX_SPELL_ID_CHARS = 64;

    public static final Type<CastSpellPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "cast_spell"));

    public static final StreamCodec<FriendlyByteBuf, CastSpellPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MAX_SPELL_ID_CHARS), CastSpellPayload::spellId,
                    CastSpellPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
