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
        // 主菜单/加载中没有玩家与连接：consumeClick 本身安全，但发包不安全。
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            SpellKeybinds.CAST_SPELL.consumeClick();   // 丢弃期间积压的点击，避免进世界就连放
            return;
        }
        // while 而非 if：一 tick 内可能积压多次点击，if 会静默吞掉多余的那些
        while (SpellKeybinds.CAST_SPELL.consumeClick()) {
            PacketDistributor.sendToServer(new CastSpellPayload());
        }
    }
}
