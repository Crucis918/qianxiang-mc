package com.qianxiang.combat;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.item.QianxiangArmorItem;
import com.qianxiang.item.QianxiangToolItem;
import com.qianxiang.item.QianxiangWeaponItem;
import com.qianxiang.phase.ComposedAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 代价兑现部：让 {@link ComposedAttributes.DrawbackLevels} 的五种代价真正生效——
 * 「强力必有代价」的另一半（生成见 {@link com.qianxiang.phase.DrawbackRules}）。
 *
 * <h3>兑现方式</h3>
 * <ul>
 *   <li><b>heavy 沉重</b>：持有（主手）或穿戴时续杯式 {@link MobEffects#MOVEMENT_SLOWDOWN}
 *       弱效（药水等级封顶 I 级），每级加深一档。</li>
 *   <li><b>draining 耗力</b>：攻击命中时给攻击者 {@link MobEffects#HUNGER} + 少量饥饿消耗。</li>
 *   <li><b>unstable 不稳</b>：攻击命中时 5%×等级 概率反噬自己 1 心（2 点魔法伤害）。</li>
 *   <li><b>cursed 诅咒</b>：持有/穿戴时随机获得短暂负面（虚弱/缓慢），每秒约 2%×等级 概率。</li>
 *   <li><b>frail 易碎</b>：耐久损耗处额外扣等级点（近似消耗加倍）——
 *       由 {@link QianxiangWeaponItem#hurtEnemy} 与 {@link QianxiangToolItem} 的
 *       {@code useOn} 调 {@link #applyFrailExtraDamage} 兑现。</li>
 * </ul>
 *
 * <h3>防御原则</h3>
 * 与 {@link CombatEffectHandler} 一致：全程 try-catch 吞异常 + 仅服务端执行。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class DrawbackHandler {

    /** 刷新间隔：每秒重算一次持有/穿戴的代价等级。 */
    private static final int REFRESH_INTERVAL_TICKS = 20;
    /** 续杯时长：11 秒（脱掉/收起后最多 11 秒自然消退）。 */
    private static final int EFFECT_DURATION_TICKS = 220;
    /** heavy 药水等级封顶：II 级（amplifier 1）——保持「弱效」定位。 */
    private static final int HEAVY_MAX_AMPLIFIER = 1;
    /** cursed 每秒触发概率基数：每级 2%。 */
    private static final float CURSED_CHANCE_PER_LEVEL_PER_SECOND = 0.02f;
    /** cursed 负面时长：5 秒。 */
    private static final int CURSED_DURATION_TICKS = 100;
    /** unstable 反噬概率基数：每级 5%。 */
    private static final float UNSTABLE_CHANCE_PER_LEVEL = 0.05f;
    /** unstable 反噬伤害：1 心。 */
    private static final float UNSTABLE_BACKLASH_DAMAGE = 2.0f;
    /** draining 饥饿效果时长基数：每级 100 tick = 5 秒。 */
    private static final int DRAINING_HUNGER_TICKS_PER_LEVEL = 100;
    /** draining 每次攻击的额外饥饿消耗基数：每级 0.4（约半格鸡腿/十几次攻击）。 */
    private static final float DRAINING_EXHAUSTION_PER_LEVEL = 0.4f;

    private DrawbackHandler() {}

    // ============================ 持有/穿戴代价（heavy / cursed） ============================

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        try {
            Player player = event.getEntity();
            Level level = player.level();
            if (level.isClientSide()) {
                return;
            }
            if (level.getGameTime() % REFRESH_INTERVAL_TICKS != 0) {
                return;
            }

            // 汇总主手（武器/工具）+ 四件护甲的代价等级
            int heavy = 0;
            int cursed = 0;
            ComposedAttributes.DrawbackLevels held = drawbacksOf(player.getMainHandItem());
            if (held != null) {
                heavy += held.heavy();
                cursed += held.cursed();
            }
            for (EquipmentSlot slot : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ComposedAttributes.DrawbackLevels d = drawbacksOf(player.getItemBySlot(slot));
                if (d != null) {
                    heavy += d.heavy();
                    cursed += d.cursed();
                }
            }

            // —— 沉重：续杯式缓慢（弱效，封顶 II 级）——
            if (heavy > 0) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.MOVEMENT_SLOWDOWN,
                        EFFECT_DURATION_TICKS,
                        Math.min(heavy - 1, HEAVY_MAX_AMPLIFIER),
                        true, false, true));
            }

            // —— 诅咒：小概率随机短暂负面（虚弱/缓慢）——
            if (cursed > 0
                    && player.getRandom().nextFloat() < CURSED_CHANCE_PER_LEVEL_PER_SECOND * cursed) {
                var effect = player.getRandom().nextBoolean()
                        ? MobEffects.WEAKNESS : MobEffects.MOVEMENT_SLOWDOWN;
                player.addEffect(new MobEffectInstance(
                        effect, CURSED_DURATION_TICKS, Math.min(cursed - 1, 1)));
            }
        } catch (Throwable t) {
            try {
                Qianxiang.LOGGER.warn("[Qianxiang] 持有/穿戴代价处理异常（已吞）: {}", t.toString());
            } catch (Throwable ignored) {
                // 日志也不允许再抛
            }
        }
    }

    // ============================ 攻击代价（draining / unstable） ============================

    /**
     * 监听最终伤害结算：手持带代价的千相武器命中时，给攻击者自己兑现 draining/unstable。
     * 与 {@link CombatEffectHandler#onLivingDamagePost} 同一事件、互不干扰。
     */
    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        try {
            LivingEntity target = event.getEntity();
            if (target == null || target.level().isClientSide()) {
                return;
            }
            Entity causing = event.getSource().getEntity();
            Entity direct = event.getSource().getDirectEntity();
            Player attacker = causing instanceof Player p ? p
                    : (direct instanceof Player p ? p : null);
            if (attacker == null) {
                return;
            }
            ItemStack weapon = attacker.getMainHandItem();
            if (!(weapon.getItem() instanceof QianxiangWeaponItem)) {
                return;
            }
            ComposedAttributes.DrawbackLevels d = drawbacksOf(weapon);
            if (d == null || (!d.anyPositive())) {
                return;
            }

            // —— 耗力：攻击令自己饥饿 ——
            if (d.draining() > 0) {
                try {
                    attacker.addEffect(new MobEffectInstance(
                            MobEffects.HUNGER,
                            d.draining() * DRAINING_HUNGER_TICKS_PER_LEVEL,
                            Math.min(d.draining() - 1, 2)));
                    attacker.causeFoodExhaustion(DRAINING_EXHAUSTION_PER_LEVEL * d.draining());
                } catch (Throwable t) {
                    Qianxiang.LOGGER.error("[Qianxiang] 兑现耗力代价失败 lv={}", d.draining(), t);
                }
            }

            // —— 不稳：5%×等级 概率反噬自己 1 心 ——
            if (d.unstable() > 0
                    && attacker.getRandom().nextFloat() < UNSTABLE_CHANCE_PER_LEVEL * d.unstable()) {
                try {
                    attacker.hurt(attacker.damageSources().magic(), UNSTABLE_BACKLASH_DAMAGE);
                } catch (Throwable t) {
                    Qianxiang.LOGGER.error("[Qianxiang] 兑现不稳代价失败 lv={}", d.unstable(), t);
                }
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] DrawbackHandler 处理伤害事件时异常", t);
        }
    }

    // ============================ 耐久代价（frail） ============================

    /**
     * 易碎兑现：在武器 hurtEnemy / 工具 useOn 的正常耐久损耗之后调用，
     * 额外扣除 frail 等级点耐久（正常 1 点 + 额外 frail 点 ≈ 消耗加倍）。
     * 仅服务端生效；任何异常吞掉，绝不影响正常损耗流程。
     */
    public static void applyFrailExtraDamage(ItemStack stack, LivingEntity entity, EquipmentSlot slot) {
        try {
            if (stack == null || stack.isEmpty() || entity == null) {
                return;
            }
            if (entity.level().isClientSide()) {
                return;
            }
            ComposedAttributes.DrawbackLevels d = drawbacksOf(stack);
            if (d == null || d.frail() <= 0) {
                return;
            }
            stack.hurtAndBreak(d.frail(), entity, slot);
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 易碎代价额外损耗异常（已吞）: {}", t.toString());
        }
    }

    /** 读栈上的代价等级；非千相产物/无组件/全零都返回 null。 */
    private static ComposedAttributes.DrawbackLevels drawbacksOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (!(stack.getItem() instanceof QianxiangWeaponItem)
                && !(stack.getItem() instanceof QianxiangArmorItem)
                && !(stack.getItem() instanceof QianxiangToolItem)) {
            return null;
        }
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null) {
            return null;
        }
        ComposedAttributes.DrawbackLevels d = attr.drawbacks();
        return d != null && d.anyPositive() ? d : null;
    }
}
