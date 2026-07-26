package com.qianxiang.event;

import com.qianxiang.Qianxiang;
import com.qianxiang.cap.PlayerFactionData;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.cap.SagaData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * 势力/烙印系统事件监听。
 *
 * <p>当前 MVP 只处理：
 * <ul>
 *   <li>玩家击杀敌对生物（{@link Monster}）：增加屠杀计数、降低好感、写入相谱录。</li>
 *   <li>NPC 交互/交易逻辑放在 {@link com.qianxiang.entity.QianxiangNPCBase#mobInteract} 中处理。</li>
 * </ul>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class FactionEventHandler {
    private FactionEventHandler() {}

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getSource() == null || event.getSource().getEntity() == null) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getEntity().level().isClientSide()) {
            return;
        }

        LivingEntity victim = event.getEntity();
        if (!isSlaughterTarget(victim)) {
            return;
        }

        try {
            PlayerFactionData before = player.getData(QianxiangAttachments.FACTION_DATA);
            PlayerFactionData after = before.withSlaughter(1).updateTitle();
            player.setData(QianxiangAttachments.FACTION_DATA, after);

            SagaData saga = player.getData(QianxiangAttachments.SAGA_DATA);
            String victimName = victim.getName().getString();
            player.setData(QianxiangAttachments.SAGA_DATA,
                    saga.withEntry("§c[屠杀] §r击杀 " + victimName + "，当前屠杀烙印 " + after.slaughterCount()));

            // 称号切换时发送提示
            if (!before.title().equals(after.title())) {
                player.sendSystemMessage(Component.translatable("qianxiang.faction.title.changed",
                        Component.translatable(after.title())));
            }
        } catch (Exception e) {
            Qianxiang.LOGGER.error("[Qianxiang] Faction kill handling failed", e);
        }
    }

    /**
     * MVP 阶段「特定生物」简化为所有 {@link Monster} 子类（僵尸、骷髅、苦力怕等敌对生物）。
     * 后续可通过 entity_type tag 扩展。
     */
    private static boolean isSlaughterTarget(LivingEntity entity) {
        return entity instanceof Monster;
    }
}
