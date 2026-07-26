package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：把最近一次 AI 响应附带的输出（spellJson + 自定义名 + movesetJson）同步给服务端锻造台。
 * <p>
 * 传递链路：服务端 AI 响应（{@link AiResponsePayload}，内含 RecipeProposal.spellJson/movesetJson）→
 * 客户端缓存 → 本包回传服务端 → 暂存于 {@code ForgeTableBlockEntity} →
 * {@code ForgeTableMenu.slotsChanged} 组合产物时消费（见 ForgeComposer#applyAiSpell / #applyAiMoveset）。
 * <p>
 * 字段都可为空串：空串 = 清除暂存（AI 响应不带对应 JSON / 玩家选了无定制方案）。
 */
public record SpellJsonReportPayload(String spellJson, String customName, String movesetJson) implements CustomPacketPayload {

    /** 兼容旧两参构造：movesetJson 默认空串（=无动作定制）。 */
    public SpellJsonReportPayload(String spellJson, String customName) {
        this(spellJson, customName, "");
    }

    public static final Type<SpellJsonReportPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "spell_json_report"));

    public static final StreamCodec<FriendlyByteBuf, SpellJsonReportPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, SpellJsonReportPayload::spellJson,
                    ByteBufCodecs.STRING_UTF8, SpellJsonReportPayload::customName,
                    ByteBufCodecs.STRING_UTF8, SpellJsonReportPayload::movesetJson,
                    SpellJsonReportPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
