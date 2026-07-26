package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：连击编辑器（{@code MovesetEditorScreen}）编排好的动作序列，
 * 以 movesetJson 契约字符串（{@code {"category","combos","collider"}}）上送。
 * <p>
 * 服务端 {@link MovesetApplyHandler} 用 {@code WeaponMoveset.fromJson} 解析、
 * 按 {@code AnimationLibrary} 白名单过滤后，直接构造 {@code WeaponMoveset}
 * 写入目标武器（锻造台结果槽优先，其次主手）的 CUSTOM_MOVESET 组件。
 * </p>
 */
public record MovesetApplyPayload(String movesetJson) implements CustomPacketPayload {

    public static final Type<MovesetApplyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "moveset_apply"));

    public static final StreamCodec<FriendlyByteBuf, MovesetApplyPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MovesetApplyPayload::movesetJson,
            MovesetApplyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
