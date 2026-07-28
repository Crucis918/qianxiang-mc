package com.qianxiang.handler;

import com.qianxiang.Qianxiang;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 首次进世界指引：流浪相师是修行系统的入口，但他低权重自然刷新，
 * 不指一句玩家永远不知道要找他（「没找到职业系统」投诉的根因之一）。
 * <p>
 * 判定：{@code player.getPersistentData()} 的 {@code qianxiang:greeted} 标志——
 * 随玩家 NBT 持久化，每个玩家只发一次，零新 attachment/codec。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class FirstJoinGuideHandler {

    private static final String GREETED_KEY = "qianxiang:greeted";

    private FirstJoinGuideHandler() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.getPersistentData().getBoolean(GREETED_KEY)) return;
        player.getPersistentData().putBoolean(GREETED_KEY, true);
        player.sendSystemMessage(Component.translatable("qianxiang.guide.first_join"));
    }
}
