package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：设定/更换主职业内核。
 * <p>
 * 二选一：{@code templateId} 非空 = 一键预设模板（服务端查表，不信客户端三元组）；
 * 否则用 elementA/elementB/form 自定义三元组（服务端照常校验白名单+互异）。
 * 服务端 {@code ProficiencyHandlers.handleSetClassCore} 校验已开启修行、
 * 合法性，首次免费、转职扣 10 绿宝石。
 * </p>
 */
public record SetClassCorePayload(String templateId, String elementA, String elementB, String form)
        implements CustomPacketPayload {

    /** 自定义三元组构造（templateId 空）。 */
    public static SetClassCorePayload custom(String elementA, String elementB, String form) {
        return new SetClassCorePayload("", elementA, elementB, form);
    }

    /** 预设模板构造。 */
    public static SetClassCorePayload template(String templateId) {
        return new SetClassCorePayload(templateId, "", "", "");
    }

    public static final Type<SetClassCorePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "set_class_core"));

    public static final StreamCodec<FriendlyByteBuf, SetClassCorePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(24), SetClassCorePayload::templateId,
                    ByteBufCodecs.stringUtf8(16), SetClassCorePayload::elementA,
                    ByteBufCodecs.stringUtf8(16), SetClassCorePayload::elementB,
                    ByteBufCodecs.stringUtf8(16), SetClassCorePayload::form,
                    SetClassCorePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
