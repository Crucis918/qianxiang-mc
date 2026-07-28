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
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
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

        // 熟练度 XP：主手持有物品击杀（受害者非玩家）即记，未开启不攒（Helper 内部判）。
        // 守望者三轨各 +100；其余按目标最大生命 ×0.5（至少 1）。
        try {
            if (!(victim instanceof net.minecraft.world.entity.player.Player)) {
                if (victim instanceof com.qianxiang.entity.QianxiangMyriadWarden) {
                    com.qianxiang.cap.ProficiencyHelper.addXp(
                            player, com.qianxiang.cap.ProficiencyTrack.COMBAT,
                            com.qianxiang.cap.ProficiencyHelper.WARDEN_XP_EACH_TRACK);
                    com.qianxiang.cap.ProficiencyHelper.addXp(
                            player, com.qianxiang.cap.ProficiencyTrack.ARCANE,
                            com.qianxiang.cap.ProficiencyHelper.WARDEN_XP_EACH_TRACK);
                    com.qianxiang.cap.ProficiencyHelper.addXp(
                            player, com.qianxiang.cap.ProficiencyTrack.CRAFT,
                            com.qianxiang.cap.ProficiencyHelper.WARDEN_XP_EACH_TRACK);
                } else {
                    com.qianxiang.cap.ProficiencyHelper.addXp(
                            player, com.qianxiang.cap.ProficiencyTrack.COMBAT,
                            Math.max(1, (int) (victim.getMaxHealth()
                                    * com.qianxiang.cap.ProficiencyHelper.KILL_XP_PER_MAX_HEALTH)));
                }
                // harvest 节点：击杀回血 2
                int heal = com.qianxiang.cap.ProficiencyHelper.killHeal(player);
                if (heal > 0 && player.isAlive()) {
                    player.heal(heal);
                }
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 熟练度击杀 XP 记账失败（不影响烙印）：{}", t.toString());
        }

        if (!isSlaughterTarget(victim)) {
            return;
        }

        try {
            PlayerFactionData before = player.getData(QianxiangAttachments.FACTION_DATA);
            PlayerFactionData after = before.withSlaughter(1).updateTitle();
            player.setData(QianxiangAttachments.FACTION_DATA, after);

            SagaData saga = player.getData(QianxiangAttachments.SAGA_DATA);
            String victimName = victim.getName().getString();
            saga = saga.withEntry("§c[屠杀] §r击杀 " + victimName + "，当前屠杀烙印 " + after.slaughterCount());
            if (victim instanceof com.qianxiang.entity.QianxiangMyriadWarden) {
                // 讨伐维度 Boss 是位格的大跃升（锻造每次 +1，讨伐一次 +5）
                saga = saga.withEntry("§5[讨伐] §r讨灭森罗守望者，位格大幅提升").withBumpedPosition(5);
                player.sendSystemMessage(Component.translatable("qianxiang.saga.position_up",
                        saga.position()));
            }
            player.setData(QianxiangAttachments.SAGA_DATA, saga);

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
