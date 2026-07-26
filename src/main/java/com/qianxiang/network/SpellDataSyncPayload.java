package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：同步玩家 mana 数据（用于 HUD 显示）。
 * <p>
 * 同步当前/最大 mana 与「最长剩余冷却 tick」（HUD 冷却条用）。
 * 已学法术列表仍留在服务端。
 */
public record SpellDataSyncPayload(int currentMana, int maxMana, int cooldownTicks)
        implements CustomPacketPayload {

    public static final Type<SpellDataSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "spell_data_sync"));

    public static final StreamCodec<FriendlyByteBuf, SpellDataSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SpellDataSyncPayload::currentMana,
                    ByteBufCodecs.VAR_INT, SpellDataSyncPayload::maxMana,
                    ByteBufCodecs.VAR_INT, SpellDataSyncPayload::cooldownTicks,
                    SpellDataSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
