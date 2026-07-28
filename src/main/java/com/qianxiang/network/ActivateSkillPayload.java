package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：释放主动技能（warcry 战吼 / surge 法力涌动）。
 * 校验（已解锁 + 冷却）在服务端，见 {@code ProficiencyHelper#activateWarCry/#activateSurge}。
 */
public record ActivateSkillPayload(String skillId) implements CustomPacketPayload {

    /** 技能 id 长度上限。 */
    public static final int MAX_SKILL_ID_CHARS = 32;

    public static final Type<ActivateSkillPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "activate_skill"));

    public static final StreamCodec<FriendlyByteBuf, ActivateSkillPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MAX_SKILL_ID_CHARS), ActivateSkillPayload::skillId,
                    ActivateSkillPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
