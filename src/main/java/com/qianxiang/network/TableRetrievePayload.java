package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：从功能台（锻造台/炼金台）材料列表点选某槽，取回该槽材料。
 * <p>
 * 两台共用一包：handler 按玩家当前 openMenu 类型分派到
 * {@code ForgeTableMenu}/{@code AlchemyTableMenu}（与 AI 放料同一分派范式）。
 * </p>
 */
public record TableRetrievePayload(int slotIndex) implements CustomPacketPayload {

    public static final Type<TableRetrievePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "table_retrieve"));

    public static final StreamCodec<FriendlyByteBuf, TableRetrievePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, TableRetrievePayload::slotIndex,
                    TableRetrievePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
