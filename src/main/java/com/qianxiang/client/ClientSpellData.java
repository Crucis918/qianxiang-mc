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

    /** 在客户端主线程应用服务端下发的 mana 数据。 */
    public static void receive(SpellDataSyncPayload payload, Player player) {
        if (player == null) return;
        PlayerSpellData current = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        PlayerSpellData next = current
                .withMaxMana(payload.maxMana())
                .withMana(payload.currentMana());
        if (next != current) {
            player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, next);
        }
    }
}
