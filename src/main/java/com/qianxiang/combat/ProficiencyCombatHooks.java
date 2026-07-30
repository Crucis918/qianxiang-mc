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
            // 主职业形态倍率只作用于近战攻击（PLAYER_ATTACK）：AoE/弹体等法术伤害
            // 同样以玩家为伤害源，不拦会把「非内核形态 ×0.6」误乘到法术上（实测 1.15×0.6）。
            double formMult = event.getSource().is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)
                    ? com.qianxiang.cap.ClassCoreHelper.meleeFormMult(attacker, mainhandForm(attacker))
                    : 1.0;
            // 招牌被动（背刺/低血/潜行/对减速·中毒·亡灵等）同样只作用于近战攻击
            double signatureMult = event.getSource().is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)
                    ? com.qianxiang.cap.ClassCoreHelper.meleeSignatureMult(attacker, target)
                    : 1.0;
            double mult = ProficiencyHelper.meleeDamageMult(attacker)
                    * formMult
                    * signatureMult
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

    /** 近战命中附加（流氓上毒 / 柔道家缓慢+补击退；只认 PLAYER_ATTACK）。 */
    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        try {
            if (!event.getSource().is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)) return;
            if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
            com.qianxiang.cap.ClassCoreHelper.onMeleeHitPost(attacker, event.getEntity());
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 近战命中附加失败（不影响原伤害）：{}", t.toString());
        }
    }

    /** 击杀附加（盗贼隐身 / 死灵回血 / 复仇者回血；杀手是玩家即触发，近战法术同享）。 */
    @SubscribeEvent
    public static void onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        try {
            if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
            com.qianxiang.cap.ClassCoreHelper.onKill(attacker);
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 击杀附加失败（不影响原流程）：{}", t.toString());
        }
    }

    /** 主手产物的武器形态（AppearanceData.form；无组件/空串 = 非内核形态 ×0.6 档）。 */
    private static String mainhandForm(ServerPlayer attacker) {
        var stack = attacker.getMainHandItem();
        if (stack.isEmpty()) return "";
        var attr = stack.get(com.qianxiang.QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        return attr == null ? "" : attr.form();
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        try {
            if (!(event.getEntity() instanceof Player victim)) return;
            double mult = ProficiencyHelper.incomingDamageMult(victim)
                    * com.qianxiang.cap.ClassCoreHelper.incomingSignatureMult(victim); // 圣骑士 -10%
            if (mult == 1.0) return;
            event.getContainer().setNewDamage(
                    (float) (event.getContainer().getOriginalDamage() * mult));
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 熟练度减伤结算失败（不影响原伤害）：{}", t.toString());
        }
    }
}
