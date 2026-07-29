package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：同步当前法术连击数（荣耀连招系统）。
 * <p>
 * 每次成功施法由 {@link com.qianxiang.spell.SpellCastHandler} 下发
 * （连击每次施法必然变化：递增、同 id 重置为 1 或超时重计）。
 * 客户端 {@link com.qianxiang.client.ComboHudRenderer} 收到后刷新
 * 「N 连击！」显示；超窗断连由客户端按最后同步时间本地淡出兜底，
 * 不需要服务端为超时专门发包。
 * </p>
 */
public record ComboSyncPayload(int combo) implements CustomPacketPayload {

    public static final Type<ComboSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "combo_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ComboSyncPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, ComboSyncPayload::combo, ComboSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
