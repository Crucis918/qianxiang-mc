package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.cap.PlayerProficiencyData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端→客户端：全量同步玩家熟练度（三轨 xp/等级/技能点 + 已分配节点 + 开启标记）。
 * <p>字段较多超 {@code StreamCodec.composite} 上限，用手写 codec
 * （与 {@code SpellDataSyncPayload} 的 learnedSpells 同范式，allocated 限长 64）。</p>
 */
public record ProficiencySyncPayload(
        boolean unlocked,
        int combatXp, int arcaneXp, int craftXp,
        int combatLevel, int arcaneLevel, int craftLevel,
        int combatPoints, int arcanePoints, int craftPoints,
        Set<String> allocated,
        int surgeCooldownMs, int warcryCooldownMs, int warcryActiveMs,
        String classElementA, String classElementB, String classForm, String classTemplateId
) implements CustomPacketPayload {

    /** 已分配节点列表上限（27 节点，64 防异常包撑爆）。 */
    public static final int MAX_ALLOCATED = 64;

    public static ProficiencySyncPayload of(PlayerProficiencyData d) {
        return new ProficiencySyncPayload(d.unlocked(),
                d.combatXp(), d.arcaneXp(), d.craftXp(),
                d.combatLevel(), d.arcaneLevel(), d.craftLevel(),
                d.combatPoints(), d.arcanePoints(), d.craftPoints(),
                d.allocated(), 0, 0, 0,
                d.classCore().elementA(), d.classCore().elementB(), d.classCore().form(), d.classTemplateId());
    }

    /** 带主动技能冷却状态的完整同步（ProficiencyHelper.sync 用）。 */
    public static ProficiencySyncPayload of(net.minecraft.server.level.ServerPlayer player) {
        PlayerProficiencyData d = player.getData(
                com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        return new ProficiencySyncPayload(d.unlocked(),
                d.combatXp(), d.arcaneXp(), d.craftXp(),
                d.combatLevel(), d.arcaneLevel(), d.craftLevel(),
                d.combatPoints(), d.arcanePoints(), d.craftPoints(),
                d.allocated(),
                (int) com.qianxiang.cap.ProficiencyHelper.surgeCooldownRemainingMs(player),
                (int) com.qianxiang.cap.ProficiencyHelper.warcryCooldownRemainingMs(player),
                (int) com.qianxiang.cap.ProficiencyHelper.warcryActiveRemainingMs(player),
                d.classCore().elementA(), d.classCore().elementB(), d.classCore().form(), d.classTemplateId());
    }

    public static final Type<ProficiencySyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "proficiency_sync"));

    public static final StreamCodec<FriendlyByteBuf, ProficiencySyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ProficiencySyncPayload decode(FriendlyByteBuf buf) {
                    boolean unlocked = ByteBufCodecs.BOOL.decode(buf);
                    int combatXp = ByteBufCodecs.VAR_INT.decode(buf);
                    int arcaneXp = ByteBufCodecs.VAR_INT.decode(buf);
                    int craftXp = ByteBufCodecs.VAR_INT.decode(buf);
                    int combatLevel = ByteBufCodecs.VAR_INT.decode(buf);
                    int arcaneLevel = ByteBufCodecs.VAR_INT.decode(buf);
                    int craftLevel = ByteBufCodecs.VAR_INT.decode(buf);
                    int combatPoints = ByteBufCodecs.VAR_INT.decode(buf);
                    int arcanePoints = ByteBufCodecs.VAR_INT.decode(buf);
                    int craftPoints = ByteBufCodecs.VAR_INT.decode(buf);
                    List<String> allocated = ByteBufCodecs.collection(ArrayList::new,
                            ByteBufCodecs.stringUtf8(64), MAX_ALLOCATED).decode(buf);
                    int surgeCd = ByteBufCodecs.VAR_INT.decode(buf);
                    int warcryCd = ByteBufCodecs.VAR_INT.decode(buf);
                    int warcryActive = ByteBufCodecs.VAR_INT.decode(buf);
                    String classElementA = ByteBufCodecs.stringUtf8(16).decode(buf);
                    String classElementB = ByteBufCodecs.stringUtf8(16).decode(buf);
                    String classForm = ByteBufCodecs.stringUtf8(16).decode(buf);
                    String classTemplateId = ByteBufCodecs.stringUtf8(24).decode(buf);
                    return new ProficiencySyncPayload(unlocked, combatXp, arcaneXp, craftXp,
                            combatLevel, arcaneLevel, craftLevel,
                            combatPoints, arcanePoints, craftPoints,
                            new LinkedHashSet<>(allocated), surgeCd, warcryCd, warcryActive,
                            classElementA, classElementB, classForm, classTemplateId);
                }

                @Override
                public void encode(FriendlyByteBuf buf, ProficiencySyncPayload value) {
                    ByteBufCodecs.BOOL.encode(buf, value.unlocked());
                    ByteBufCodecs.VAR_INT.encode(buf, value.combatXp());
                    ByteBufCodecs.VAR_INT.encode(buf, value.arcaneXp());
                    ByteBufCodecs.VAR_INT.encode(buf, value.craftXp());
                    ByteBufCodecs.VAR_INT.encode(buf, value.combatLevel());
                    ByteBufCodecs.VAR_INT.encode(buf, value.arcaneLevel());
                    ByteBufCodecs.VAR_INT.encode(buf, value.craftLevel());
                    ByteBufCodecs.VAR_INT.encode(buf, value.combatPoints());
                    ByteBufCodecs.VAR_INT.encode(buf, value.arcanePoints());
                    ByteBufCodecs.VAR_INT.encode(buf, value.craftPoints());
                    ByteBufCodecs.collection(ArrayList::new,
                            ByteBufCodecs.stringUtf8(64), MAX_ALLOCATED)
                            .encode(buf, new ArrayList<>(value.allocated()));
                    ByteBufCodecs.VAR_INT.encode(buf, value.surgeCooldownMs());
                    ByteBufCodecs.VAR_INT.encode(buf, value.warcryCooldownMs());
                    ByteBufCodecs.VAR_INT.encode(buf, value.warcryActiveMs());
                    ByteBufCodecs.stringUtf8(16).encode(buf, value.classElementA());
                    ByteBufCodecs.stringUtf8(16).encode(buf, value.classElementB());
                    ByteBufCodecs.stringUtf8(16).encode(buf, value.classForm());
                    ByteBufCodecs.stringUtf8(24).encode(buf, value.classTemplateId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
