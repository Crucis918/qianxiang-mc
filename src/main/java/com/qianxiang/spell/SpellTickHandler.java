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
@EventBusSubscriber(modid = com.qianxiang.Qianxiang.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
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

        if (next != data) {
            player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, next);
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
