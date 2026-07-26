package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import com.qianxiang.network.CastSpellPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端按键监听：当玩家按下法术键时，发包通知服务端施法。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class ClientSpellInput {

    private ClientSpellInput() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (SpellKeybinds.CAST_SPELL.consumeClick()) {
            PacketDistributor.sendToServer(new CastSpellPayload());
        }
    }
}
