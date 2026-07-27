package com.qianxiang.spell;

import com.qianxiang.Qianxiang;
import com.qianxiang.cap.PlayerSpellData;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.network.CastSpellPayload;
import com.qianxiang.network.SpellDataSyncPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端处理玩家施法请求。
 * <p>
 * 轮盘施法：客户端发来明确的 spellId（{@link CastSpellPayload}），
 * 服务端在玩家已学列表（{@code PlayerSpellData.learnedSpells}）里查到才施放；
 * 空串/非法 id/未学法术一律 WARN 拒绝，不给客户端反馈。
 * 校验（冷却/法力/增幅器）与结算全部走 {@link #castCustomSpell}。
 * </p>
 */
public final class SpellCastHandler {

    private SpellCastHandler() {}

    /** 施法失败提示的最小间隔：每个被拒上行包回一个下行包，否则是 1:1 的流量放大面。 */
    private static final long FAIL_NOTICE_COOLDOWN_MS = 1_000L;

    /**
     * 节流版 actionbar 提示。冷却/法力不足是玩家按住键就会连续触发的高频路径，
     * 逐包回消息等于让改造过的客户端用最小成本让服务端对它单播海量数据。
     */
    private static void notifyThrottled(ServerPlayer player, String translationKey) {
        if (com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                player, "spell_fail_notice", FAIL_NOTICE_COOLDOWN_MS)) {
            player.displayClientMessage(Component.translatable(translationKey), true);
        }
    }

    public static void handle(CastSpellPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            // 观战者/尸体不得施法：此前只判类型，观战模式按 V 照样能放
            // （ender 元素还会 connection.teleport 把观战者传走），死亡瞬间同理。
            if (!serverPlayer.isAlive() || serverPlayer.isSpectator()) {
                return;
            }
            castLearnedSpell(serverPlayer, payload.spellId());
        });
    }

    /**
     * 按 id 在已学列表查找并施放（轮盘施法服务端入口，GameTest 可直接调用）。
     * <p>
     * 空串/非法 id/未学法术一律 WARN 拒绝并返回 false，不给客户端反馈；
     * 校验（冷却/法力/增幅器）与结算全部走 {@link #castCustomSpell}。
     * </p>
     *
     * @return true = 实际进入施放结算（不代表效果命中）
     */
    public static boolean castLearnedSpell(ServerPlayer serverPlayer, String rawId) {
        ResourceLocation spellId = ResourceLocation.tryParse(rawId == null ? "" : rawId);
        if (spellId == null) {
            Qianxiang.LOGGER.warn("[Qianxiang] 玩家 {} 发送了非法施法 id「{}」，已忽略",
                    serverPlayer.getName().getString(), rawId);
            return false;
        }

        PlayerSpellData data = serverPlayer.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        CustomSpell spell = data.findLearned(spellId).orElse(null);
        if (spell == null) {
            Qianxiang.LOGGER.warn("[Qianxiang] 玩家 {} 请求施放未学法术 {}，已忽略",
                    serverPlayer.getName().getString(), spellId);
            return false;
        }
        return castCustomSpell(spell, serverPlayer);
    }

    /**
     * 施放一个自定义法术（自由法术核心路径）。
     * <p>
     * 先做冷却/法力校验，通过后按增幅器结算伤害倍率、调
     * {@link SpellEffectEngine#cast} 兑现效果，再扣法力、写冷却、同步客户端。
     * 法术数据来自玩家已学列表，预置注册表之外的法术（材料/AI 生成）也能施放。
     * </p>
     *
     * @return true = 实际施放成功
     */
    public static boolean castCustomSpell(CustomSpell spell, ServerPlayer serverPlayer) {
        PlayerSpellData data = serverPlayer.getData(QianxiangAttachments.PLAYER_SPELL_DATA);

        if (data.isOnCooldown(spell.id())) {
            notifyThrottled(serverPlayer, "qianxiang.spell.cooldown");
            return false;
        }
        if (data.currentMana() < spell.manaCost()) {
            notifyThrottled(serverPlayer, "qianxiang.spell.no_mana");
            return false;
        }

        // 增幅器结算：主手+副手法杖/魔法书的法术伤害加成合并为一个倍率传入效果引擎。
        float damageMult = (float) AmplifierHelper.damageMultiplier(serverPlayer);
        try {
            SpellEffectEngine.cast(spell, serverPlayer, damageMult);
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 自定义法术效果执行失败 spell={}", spell.id(), t);
            return false;
        }

        // 重读 attachment 再扣蓝：引擎结算可能已动过法力
        // （blood 血换蓝的 +20、settleCast 退款），用旧快照扣减会把它们整个抹掉。
        PlayerSpellData after = serverPlayer.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        PlayerSpellData next = after
                .withMana(after.currentMana() - spell.manaCost())
                .setCooldown(spell.id(), spell.cooldownTicks());
        if (next != after) {
            serverPlayer.setData(QianxiangAttachments.PLAYER_SPELL_DATA, next);
        }
        sync(serverPlayer);
        com.qianxiang.QianxiangAdvancements.grant(serverPlayer,
                com.qianxiang.QianxiangAdvancements.FIRST_CAST);
        return true;
    }

    /** 把当前 mana、最长剩余冷却、有效法力上限与已学法术列表同步给指定玩家。 */
    public static void sync(ServerPlayer player) {
        try {
            PlayerSpellData data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
            PacketDistributor.sendToPlayer(player, new SpellDataSyncPayload(
                    data.currentMana(), data.maxMana(), AmplifierHelper.effectiveMaxMana(player, data),
                    longestCooldown(data), data.learnedSpells()));
        } catch (Throwable t) {
            // 同步发包失败（断线瞬间、或未协商 payload 通道的连接）不该拖垮施法/退款结算
            Qianxiang.LOGGER.debug("[Qianxiang] 法术数据同步发包失败（不影响结算）：{}", t.toString());
        }
    }

    /**
     * 当前最长的剩余冷却 tick（无冷却返回 0）。
     * <p>HUD 只画一根冷却条，取最长的那个即可表达「还不能连放」。
     */
    public static int longestCooldown(PlayerSpellData data) {
        int max = 0;
        for (int remaining : data.cooldowns().values()) {
            if (remaining > max) max = remaining;
        }
        return max;
    }
}
