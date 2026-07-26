package com.qianxiang.spell;

import com.qianxiang.cap.PlayerSpellData;
import com.qianxiang.cap.QianxiangAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 法术系统服务端 tick：
 * <ul>
 *   <li>每 tick 减少法术冷却。</li>
 *   <li>每 10 tick 恢复 1 点 mana（clamp 到上限）。</li>
 *   <li>玩家登录/重生时把 mana 同步给客户端。</li>
 * </ul>
 */
@EventBusSubscriber(modid = com.qianxiang.Qianxiang.MOD_ID)
public final class SpellTickHandler {

    private SpellTickHandler() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        PlayerSpellData data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        PlayerSpellData next = data.tickCooldowns();

        if (player.level().getGameTime() % 10 == 0) {
            next = next.withMana(next.currentMana() + 1);
        }

        if (next == data) {
            return;
        }
        player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, next);

        // 发包条件不能用 next != data：tickCooldowns() 在冷却非空时每 tick 都返回新实例，
        // 那样冷却期间会 20 包/秒/玩家地刷同步包，而包里只有 mana 两个 int（纯噪音）。
        // 只在「客户端真正显示的值」发生变化时才发。
        if (syncedStateChanged(data, next)) {
            SpellCastHandler.sync(player);
        }
    }

    /** 客户端 HUD 关心的字段（法力 + 最长剩余冷却）是否变化。 */
    private static boolean syncedStateChanged(PlayerSpellData before, PlayerSpellData after) {
        return before.currentMana() != after.currentMana()
                || before.maxMana() != after.maxMana()
                || SpellCastHandler.longestCooldown(before) != SpellCastHandler.longestCooldown(after);
    }

    /**
     * 跨维度后重新同步：换维度会重建客户端玩家实体，attachment 回落到默认值
     * （100/100），HUD 会闪一下虚高的法力再被下一个包纠正。
     */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SpellCastHandler.sync(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SpellCastHandler.sync(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SpellCastHandler.sync(player);
        }
    }
}
