package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：「能做啥」主动建议（材料变化防抖后的本地分析结果）。
 * <p>
 * 纯展示数据，客户端据此渲染一行「可做：X」；itemId 为空串 = 清空提示行
 * （材料清空或组合不出有效产物）。formId 仅锻造台用（武器形态，"" 无），
 * value 为攻击力（锻造）或法术强度（炼金）。
 * </p>
 */
public record TableSuggestionPayload(boolean magic, String itemId, String formId, double value)
        implements CustomPacketPayload {

    public static final Type<TableSuggestionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "table_suggestion"));

    public static final StreamCodec<FriendlyByteBuf, TableSuggestionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, TableSuggestionPayload::magic,
                    ByteBufCodecs.stringUtf8(64), TableSuggestionPayload::itemId,
                    ByteBufCodecs.stringUtf8(32), TableSuggestionPayload::formId,
                    ByteBufCodecs.DOUBLE, TableSuggestionPayload::value,
                    TableSuggestionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
