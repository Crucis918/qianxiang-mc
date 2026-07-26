package com.qianxiang.combat;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.phase.ComposedAttributes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import java.util.Map;

/**
 * 防具效果部：让千相防具在被攻击时反击攻击者。
 * <p>
 * 灵魂兑现点：玩家说「反伤装甲」——AI 拼出带 REFLECT 算子的材料，锻造出的防具在
 * {@link ComposedAttributes#thornsLevel()} 里带了等级，这里负责兑现成可见的反弹。
 * </p>
 *
 * <h3>机制</h3>
 * 监听 {@link LivingDamageEvent.Post}（最终伤害已定）：当被攻击者是玩家时汇总全身装备：
 * <ul>
 *   <li><b>反弹伤害</b>：任一装备槽物品带 {@code composed_attributes} 且
 *       {@code thornsLevel > 0} → 攻击者受 <b>全身 thornsLevel 之和 × 1.5</b>
 *       的魔法伤害（{@code damageSources().magic()}），并撒荆棘粒子。</li>
 *   <li><b>接触反伤</b>（无反转标志的防具）：防具携带的 grantedEffects（含负面效果）
 *       不施加给穿戴者自己，而是被攻击时让攻击者中这些效果（等级=防具效果等级）；
 *       防具的 igniteLevel 转为点燃攻击者。带反转标志的防具不参与本路径——
 *       其效果由 {@link com.qianxiang.handler.ArmorPassiveHandler} 兑现为抗性/免疫。</li>
 * </ul>
 *
 * <h3>防循环</h3>
 * 反弹伤害本身也会触发本事件。若攻击者也是穿反伤甲的玩家，可能互相弹到一方死亡。
 * 因此跳过魔法/荆棘类伤害源（我方反弹用的正是 magic），只响应「真实攻击」。
 *
 * <h3>防御原则</h3>
 * 全程 try-catch 吞异常 + 服务端判 {@code !level.isClientSide()}，绝不让战斗部崩溃拖垮对局。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class ArmorEffectHandler {

    /** 反伤魔法伤害系数：每级 1.5（与武器进攻形态反伤一致）。 */
    private static final float THORNS_DAMAGE_PER_LEVEL = 1.5f;
    /** 接触点燃 tick 基数：每级 80 tick = 4 秒（与武器灼烧一致）。 */
    private static final int CONTACT_IGNITE_TICKS_PER_LEVEL = 80;
    /** 接触效果时长 tick 基数：每级 60 tick = 3 秒。 */
    private static final int CONTACT_TICKS_PER_LEVEL = 60;
    /** 接触效果药水等级封顶：IV 级（amplifier 3）。 */
    private static final int CONTACT_MAX_AMPLIFIER = 3;

    private ArmorEffectHandler() {}

    /** 监听最终伤害结算：被攻击的玩家若穿戴千相反伤防具，反弹攻击者。 */
    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        try {
            // 被攻击者必须是玩家（防具穿戴者），且服务端权威。
            if (!(event.getEntity() instanceof Player victim) || victim.level().isClientSide()) {
                return;
            }

            DamageSource source = event.getSource();
            // 防循环：魔法/荆棘类伤害（含我方反弹）不再二次反弹。
            if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.THORNS)) {
                return;
            }

            // 取攻击者：causing 优先（抛掷/弓箭时 direct 是箭、causing 是射手）。
            LivingEntity attacker = resolveAttacker(source.getDirectEntity(), source.getEntity());
            if (attacker == null || attacker == victim) {
                return;
            }

            // 汇总全身装备槽的反伤等级 + 无反转防具的接触效果。
            ContactPool pool = collectContactPool(victim);
            if (pool.thorns <= 0 && pool.ignite <= 0 && pool.effects.isEmpty()) {
                return;
            }

            // 反弹伤害：全身 thornsLevel 之和 × 1.5 魔法伤害 + 荆棘粒子。
            if (pool.thorns > 0) {
                float magicDamage = pool.thorns * THORNS_DAMAGE_PER_LEVEL;
                attacker.hurt(victim.damageSources().magic(), magicDamage);
                spawnThornsParticles(attacker, pool.thorns);
            }

            // 接触反伤：无反转防具携带的效果转为攻击者中招（等级=防具效果等级）。
            applyContactEffects(attacker, pool);
        } catch (Throwable t) {
            // 战斗部永不崩：任何意外都吞掉记日志，玩家对局不受影响。
            Qianxiang.LOGGER.error("[Qianxiang] ArmorEffectHandler 处理反伤事件时异常", t);
        }
    }

    /** 一次扫描的汇总结果：反伤等级 + 接触点燃等级 + 接触状态效果。 */
    private static final class ContactPool {
        int thorns;
        int ignite;
        final java.util.Map<ResourceLocation, Integer> effects = new java.util.HashMap<>();
    }

    /**
     * 扫描玩家全部装备槽：
     * <ul>
     *   <li>所有带 composed_attributes 的物品贡献 thornsLevel（反弹伤害）。</li>
     *   <li><b>无</b>反转标志的装备额外贡献 igniteLevel（接触点燃）与
     *       grantedEffects（接触效果，同效果相加）——反转件走抗性路径，不参与。</li>
     * </ul>
     */
    private static ContactPool collectContactPool(Player victim) {
        ContactPool pool = new ContactPool();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            try {
                ItemStack stack = victim.getItemBySlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
                if (attr == null) {
                    continue;
                }
                pool.thorns += attr.thornsLevel();
                if (!attr.isReversed()) {
                    pool.ignite += attr.igniteLevel();
                    Map<ResourceLocation, Integer> granted = attr.grantedEffects();
                    if (granted != null) {
                        granted.forEach((id, lv) -> {
                            if (id != null && lv != null && lv > 0) {
                                pool.effects.merge(id, lv, Integer::sum);
                            }
                        });
                    }
                }
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 扫描装备槽接触效果失败 slot={}", slot, t);
            }
        }
        return pool;
    }

    /** 接触反伤兑现：点燃攻击者 + 逐一施加接触状态效果。单个效果失败不影响其余。 */
    private static void applyContactEffects(LivingEntity attacker, ContactPool pool) {
        if (pool.ignite > 0) {
            try {
                attacker.igniteForTicks(pool.ignite * CONTACT_IGNITE_TICKS_PER_LEVEL);
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 接触点燃失败 lv={}", pool.ignite, t);
            }
        }
        for (var entry : pool.effects.entrySet()) {
            int level = entry.getValue() == null ? 0 : entry.getValue();
            if (level <= 0) {
                continue;
            }
            try {
                var holder = BuiltInRegistries.MOB_EFFECT.getHolder(entry.getKey());
                if (holder.isEmpty()) {
                    continue; // 数据包写了不存在的效果 id，跳过不炸
                }
                attacker.addEffect(new MobEffectInstance(
                        holder.get(),
                        level * CONTACT_TICKS_PER_LEVEL,
                        Math.min(level - 1, CONTACT_MAX_AMPLIFIER)));
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 接触效果施加失败 id={} lv={}", entry.getKey(), level, t);
            }
        }
    }

    /** 从 direct/causing 实体里抠出生物攻击者。 */
    private static LivingEntity resolveAttacker(Entity directEntity, Entity causingEntity) {
        if (causingEntity instanceof LivingEntity le) return le;
        if (directEntity instanceof LivingEntity le) return le;
        return null;
    }

    /** 在攻击者位置撒荆棘粒子，让反伤「看得见」。 */
    private static void spawnThornsParticles(LivingEntity attacker, int totalThorns) {
        try {
            if (attacker.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.CRIT,
                        attacker.getX(), attacker.getY() + attacker.getBbHeight() * 0.5, attacker.getZ(),
                        6 + totalThorns * 2, 0.3, 0.4, 0.3, 0.05);
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 发送反伤粒子失败 attacker={}", attacker.getId(), t);
        }
    }
}
