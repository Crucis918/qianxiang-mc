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
 * {@code ForgeTableMenu.slotsChanged} 组合产物时消费（动作定制见 ForgeComposer#applyAiMoveset；
 * spellJson 的法术铭刻已迁往炼金台，锻造台不再消费，链路保留待炼金台复用）。
 * <p>
 * <b>只携带索引，不携带内容</b>：法术/动作的真身由服务端在算出提案时留底
 * （见 {@code ForgeTableBlockEntity.setProposals}），客户端只能说「我选第几条」。
 * 此前回传的是 spellJson 全文，服务端无从区分「选了方案二」与「自己编了一段
 * 合法 JSON」——法术内容的决定权实际在客户端手里。
 * <p>
 * {@link #NONE}（-1）或越界索引 = 不使用 AI 结果（清除该玩家的选择）。
 */
public record SpellJsonReportPayload(int proposalIndex) implements CustomPacketPayload {

    /** 「不使用 AI 结果」的索引值。 */
    public static final int NONE = -1;

    public static final Type<SpellJsonReportPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "spell_json_report"));

    public static final StreamCodec<FriendlyByteBuf, SpellJsonReportPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SpellJsonReportPayload::proposalIndex,
                    SpellJsonReportPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
