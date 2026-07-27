package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：同步玩家法术数据（用于 HUD 与后续施法轮盘）。
 * <p>
 * 同步当前/最大 mana、含增幅器加成的「有效法力上限」（HUD 显示用，
 * 见 {@link com.qianxiang.spell.AmplifierHelper}）、「最长剩余冷却 tick」（HUD 冷却条用）
 * 与已学法术列表（完整 {@link CustomSpell}，客户端轮盘直接可用）。
 */
public record SpellDataSyncPayload(int currentMana, int maxMana, int effectiveMaxMana, int cooldownTicks,
                                   List<CustomSpell> learnedSpells)
        implements CustomPacketPayload {

    /** 已学法术列表上限（正常远小于此，防异常包撑爆内存）。 */
    public static final int MAX_LEARNED_SPELLS = 64;

    public static final Type<SpellDataSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "spell_data_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpellDataSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SpellDataSyncPayload::currentMana,
                    ByteBufCodecs.VAR_INT, SpellDataSyncPayload::maxMana,
                    ByteBufCodecs.VAR_INT, SpellDataSyncPayload::effectiveMaxMana,
                    ByteBufCodecs.VAR_INT, SpellDataSyncPayload::cooldownTicks,
                    ByteBufCodecs.collection(ArrayList::new, CustomSpell.STREAM_CODEC, MAX_LEARNED_SPELLS),
                    SpellDataSyncPayload::learnedSpells,
                    SpellDataSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
