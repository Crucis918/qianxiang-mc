package com.qianxiang.client;

import com.qianxiang.cap.PlayerSpellData;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.network.SpellDataSyncPayload;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 客户端接收法术数据同步包，写入本地玩家 Attachment 供 HUD/轮盘读取。
 */
@EventBusSubscriber(modid = com.qianxiang.Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class ClientSpellData {

    private ClientSpellData() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // 注册由 QianxiangPayloads 统一处理，这里不需要额外操作。
        // 本类只提供同步包到达后的应用方法。
    }

    // ---- 冷却（HUD 用）----
    // 服务端只在「玩家可见状态真的变了」时发包（见 SpellTickHandler），
    // 不再每 tick 刷同步包。因此客户端拿到的是一个快照，需要自己按本地 tick 倒数，
    // 否则冷却条会卡住不动直到下一个包到达。
    private static int syncedCooldownTicks;
    private static long syncedAtClientTick;
    /** 服务端下发的有效法力上限（含增幅器加成）；0 = 尚未同步，HUD 回退 attachment 的 maxMana。 */
    private static int syncedEffectiveMaxMana;

    /** 在客户端主线程应用服务端下发的 mana + 冷却 + 已学法术数据。 */
    public static void receive(SpellDataSyncPayload payload, Player player) {
        if (player == null) return;
        PlayerSpellData current = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        PlayerSpellData next = current
                .withMaxMana(payload.maxMana())
                .withMana(payload.currentMana())
                // 已学法术列表同步进本地 Attachment，后续 HUD/施法轮盘直接读
                // player.getData(PLAYER_SPELL_DATA).learnedSpells() 即可。
                .withLearnedSpells(payload.learnedSpells());
        if (next != current) {
            player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, next);
        }
        syncedCooldownTicks = Math.max(0, payload.cooldownTicks());
        syncedEffectiveMaxMana = Math.max(0, payload.effectiveMaxMana());
        syncedAtClientTick = player.level().getGameTime();
    }

    /** 有效法力上限（含增幅器加成）；未同步过回退 attachment 的基础 maxMana。 */
    public static int effectiveMaxMana(Player player) {
        if (syncedEffectiveMaxMana > 0) return syncedEffectiveMaxMana;
        if (player == null) return PlayerSpellData.DEFAULT_MAX_MANA;
        return player.getData(QianxiangAttachments.PLAYER_SPELL_DATA).maxMana();
    }

    /** 当前剩余冷却 tick（本地倒数，0 = 无冷却）。 */
    public static int remainingCooldownTicks(Player player) {
        if (player == null || syncedCooldownTicks <= 0) return 0;
        long elapsed = player.level().getGameTime() - syncedAtClientTick;
        if (elapsed < 0) return syncedCooldownTicks;   // 跨维度导致 gameTime 跳变，保守显示
        return (int) Math.max(0, syncedCooldownTicks - elapsed);
    }

    /** 冷却条满格基准（用同步瞬间的值做分母，条才会从满到空平滑收缩）。 */
    public static int cooldownBaselineTicks() {
        return syncedCooldownTicks;
    }

    /** 切换世界/断线时清空，避免旧值泄漏到新世界的 HUD。 */
    public static void clear() {
        syncedCooldownTicks = 0;
        syncedAtClientTick = 0L;
        syncedEffectiveMaxMana = 0;
        lastCastSpellId = null;
        castAtBySpell.clear();
    }

    // ---- 上次施放 + 客户端冷却估算（轮盘置灰/HUD「上次施放」行用）----
    // 服务端冷却表不下发逐法术明细，客户端按「自己发出施法包的时刻 + 法术自带
    // cooldownTicks」做本地估算——仅用于显示（置灰/上次施放行），施放与否服务端权威。

    private static String lastCastSpellId;
    private static final java.util.Map<String, Long> castAtBySpell = new java.util.HashMap<>();

    /** 客户端发出施法包时记录（不管服务端是否接受，估算偏差只影响显示）。 */
    public static void recordCast(com.qianxiang.spell.CustomSpell spell, Player player) {
        if (spell == null) return;
        lastCastSpellId = spell.id().toString();
        if (player != null) {
            castAtBySpell.put(lastCastSpellId, player.level().getGameTime());
        }
    }

    /** 上次施放的法术 id（字符串形；无记录为 null）。 */
    public static String lastCastSpellId() {
        return lastCastSpellId;
    }

    /** 该法术客户端估算的剩余冷却 tick（无记录/已结束为 0；仅显示用，服务端权威）。 */
    public static int estimatedCooldownRemaining(Player player, com.qianxiang.spell.CustomSpell spell) {
        if (player == null || spell == null) return 0;
        Long at = castAtBySpell.get(spell.id().toString());
        if (at == null) return 0;
        long elapsed = player.level().getGameTime() - at;
        if (elapsed < 0) return spell.cooldownTicks();   // 跨维度导致 gameTime 跳变，保守显示
        return (int) Math.max(0, spell.cooldownTicks() - elapsed);
    }
}
