package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.phase.PhaseMaterialRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * 服务端 → 客户端：数据驱动相材料整表同步。
 * <p>
 * 时机：玩家登录 / 服务端 {@code /reload}（{@code OnDatapackSyncEvent}）。
 * 客户端收到后整表替换 {@link PhaseMaterialRegistry}，保证锻造台客户端预览、
 * tooltip 与服务端产物一致。编码走 {@code ByteBufCodecs.fromCodec}（NBT 透传）。
 */
public record PhaseMaterialSyncPayload(Map<ResourceLocation, PhaseMaterialRegistry.Entry> entries)
        implements CustomPacketPayload {

    public static final Type<PhaseMaterialSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "phase_material_sync"));

    public static final StreamCodec<io.netty.buffer.ByteBuf, PhaseMaterialSyncPayload> STREAM_CODEC =
            ByteBufCodecs.fromCodec(PhaseMaterialRegistry.MAP_CODEC)
                    .map(PhaseMaterialSyncPayload::new, PhaseMaterialSyncPayload::entries);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
