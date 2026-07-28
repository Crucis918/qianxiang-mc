package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：GUI 里左键点击背包/快捷栏槽位，向功能台（锻造台/炼金台）材料区投入。
 * <p>
 * 两台共用一包：handler 按玩家当前 openMenu 类型分派到
 * {@code ForgeTableMenu}/{@code AlchemyTableMenu}（与取回同一分派范式）。
 * slotIndex 是 menu.slots 下标（背包区 = RESULT_SLOT+1 起 36 格），服务端校验区间，
 * 不信任客户端坐标。左键=投入 1 个，Shift+左键=投入整组（wholeStack）。
 * </p>
 */
public record TableInsertPayload(int slotIndex, boolean wholeStack) implements CustomPacketPayload {

    public static final Type<TableInsertPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "table_insert"));

    public static final StreamCodec<FriendlyByteBuf, TableInsertPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, TableInsertPayload::slotIndex,
                    ByteBufCodecs.BOOL, TableInsertPayload::wholeStack,
                    TableInsertPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
