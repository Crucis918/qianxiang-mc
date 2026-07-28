package com.qianxiang.combat;

import com.qianxiang.Qianxiang;
import com.qianxiang.cap.ProficiencyHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 熟练度战斗节点效果挂钩（与 {@link CombatEffectHandler} 的特效结算互补）：
 * <ul>
 *   <li>{@link LivingDamageEvent.Pre}：攻击者是玩家 →
 *       伤害 ×meleeDamageMult（blade1/blade2/pierce）×execute 斩杀 ×combo 连击，并记连击段。</li>
 *   <li>{@link LivingIncomingDamageEvent}：被击者是玩家 → 伤害 ×incomingDamageMult（bulwark）。</li>
 * </ul>
 * 未开启熟练度/未分配节点时全部 ×1 中性（Helper 内部判）。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class ProficiencyCombatHooks {

    private ProficiencyCombatHooks() {}

    @SubscribeEvent
    public static void onLivingDamagePre(LivingDamageEvent.Pre event) {
        try {
            if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
            LivingEntity target = event.getEntity();
            float damage = event.getOriginalDamage();
            double mult = ProficiencyHelper.meleeDamageMult(attacker)
                    * (1.0 + ProficiencyHelper.executeThresholdBonus(attacker, target))
                    * ProficiencyHelper.comboBonus(attacker);
            if (mult != 1.0) {
                event.setNewDamage((float) (damage * mult));
            }
            // 连击段记录必须在 early return 之外——第一段命中也要入窗（noteMeleeHit 内部判节点）。
            ProficiencyHelper.noteMeleeHit(attacker);
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 熟练度伤害加成结算失败（不影响原伤害）：{}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        try {
            if (!(event.getEntity() instanceof Player victim)) return;
            double mult = ProficiencyHelper.incomingDamageMult(victim);
            if (mult == 1.0) return;
            event.getContainer().setNewDamage(
                    (float) (event.getContainer().getOriginalDamage() * mult));
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 熟练度减伤结算失败（不影响原伤害）：{}", t.toString());
        }
    }
}
