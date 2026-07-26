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
 * 客户端接收 mana 同步包，写入本地玩家 Attachment 供 HUD 读取。
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

    /** 在客户端主线程应用服务端下发的 mana + 冷却数据。 */
    public static void receive(SpellDataSyncPayload payload, Player player) {
        if (player == null) return;
        PlayerSpellData current = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        PlayerSpellData next = current
                .withMaxMana(payload.maxMana())
                .withMana(payload.currentMana());
        if (next != current) {
            player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, next);
        }
        syncedCooldownTicks = Math.max(0, payload.cooldownTicks());
        syncedAtClientTick = player.level().getGameTime();
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
    }
}
