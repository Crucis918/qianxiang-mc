package com.qianxiang.combat;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.item.QianxiangWeaponItem;
import com.qianxiang.network.DamageNumberPayload;
import com.qianxiang.phase.ComposedAttributes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;

/**
 * 战斗效果部：让材料的五种特殊效果在攻击时真正触发，并把伤害数字发给客户端。
 * <p>
 * 灵魂兑现点：玩家说「喷火吸血的刀」——AI 拼出带 {@code igniteLevel}/{@code lifestealLevel}
 * 的材料，锻造出的器在 {@link ComposedAttributes} 里就带了等级，这里负责兑现成可见效果。
 * <b>强度靠材料</b>：effect level 直接来自材料档位，等级越高效果越猛；
 * 强力产物的反噬代价由 {@link DrawbackHandler} 按 {@code DrawbackLevels} 兑现，与本类互不干扰。
 * </p>
 *
 * <h3>事件选择（已 javap 查证 neoforge-21.1.219）</h3>
 * <ul>
 *   <li>旧 {@code LivingHurtEvent} 在 1.21 已拆分，现叫：</li>
 *   <li>{@link LivingIncomingDamageEvent}（伤害进来、可取消/改量）——不用。</li>
 *   <li>{@link LivingDamageEvent.Pre}（盔甲/附魔减免前，可改 newDamage）——不用。</li>
 *   <li>{@link LivingDamageEvent.Post}（最终伤害已定，{@code getNewDamage()} 即真实掉血）
 *       ——<b>用这个</b>：此时才知道到底打了多少，吸血/浮字才准确。</li>
 * </ul>
 * Post 事件在「最终伤害结算后、实体可能已死」时触发，副作用（点火/减速）此时施加最稳妥。
 *
 * <h3>防御原则</h3>
 * 全程 try-catch 包裹 + 服务端判 {@code !level.isClientSide()}。任何异常都吞掉并记日志，
 * 绝不让战斗部崩溃拖垮玩家对局。
 *
 * <h3>Epic Fight 兼容性（TODO，需统筹实测）</h3>
 * Epic Fight 会接管部分近战伤害管线。EF 21.15.x 通常仍走 vanilla 的
 * {@code actuallyHurt}→{@code reallyHurt}，{@link LivingDamageEvent.Post} 应仍触发；
 * 但 EF 的技能伤害若走自定义 DamageSource 路径，{@code getEntity()} 可能不是玩家主手持有者。
 * 统筹者实测：装备千相武器用 EF 技能打怪时，看特效是否触发；若不触发，需额外挂 EF 的
 * {@code ProjectileHitEvent} 或 {@code AttackPhase} 钩子补一层。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class CombatEffectHandler {

    /** 吸血系数：每级回血 = 造成伤害 × level × 0.2。 */
    private static final float LIFESTEAL_PER_LEVEL_FACTOR = 0.2f;
    /** 着火 tick 基数：每级 80 tick = 4 秒。 */
    private static final int IGNITE_TICKS_PER_LEVEL = 80;
    /** 减速时长 tick 基数：每级 60 tick = 3 秒。 */
    private static final int SLOW_TICKS_PER_LEVEL = 60;
    /** 疗伤时长 tick 基数：每级 60 tick = 3 秒。 */
    private static final int HEAL_TICKS_PER_LEVEL = 60;
    /** 反伤魔法伤害系数：每级 1.5。 */
    private static final float THORNS_DAMAGE_PER_LEVEL = 1.5f;
    /** 中毒时长 tick 基数：每级 60 tick = 3 秒。 */
    private static final int POISON_TICKS_PER_LEVEL = 60;
    /** 霜冻时长 tick 基数：每级 150 tick。vanilla 细雪冻伤阈值是 140，
     * 1 级即超过——保证放 1 个寒霜材料就能让敌人冻伤，不必堆多级。 */
    private static final int FROST_TICKS_PER_LEVEL = 150;
    /** 漂浮时长 tick 基数：每级 20 tick = 1 秒。 */
    private static final int LEVITATION_TICKS_PER_LEVEL = 20;
    /** 通用自由效果时长 tick 基数：每级 60 tick = 3 秒。 */
    private static final int GRANTED_TICKS_PER_LEVEL = 60;
    /** 通用自由效果药水等级封顶：IV 级（amplifier 3）。 */
    private static final int GRANTED_MAX_AMPLIFIER = 3;

    private CombatEffectHandler() {}

    /**
     * 监听最终伤害结算：施加五种特效 + 发伤害浮字包。
     * <p>Post 事件不可取消，只读 {@code getNewDamage()} 即真实掉血。</p>
     */
    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        try {
            LivingEntity target = event.getEntity();
            // 服务端权威：客户端模拟的事件直接跳过，避免重复施效。
            if (target == null || target.level().isClientSide()) {
                return;
            }

            DamageSource source = event.getSource();
            Entity directEntity = source.getDirectEntity();
            Entity causingEntity = source.getEntity();
            // 取「真正挥刀者」：causingEntity 优先（抛掷/弓箭时 direct 是箭、causing 是玩家）。
            Player attacker = resolveAttacker(directEntity, causingEntity);
            if (attacker == null) {
                return;
            }

            // 发伤害浮字包：让所有看见这次伤害的玩家收到（追踪目标 + 目标自己若是玩家）。
            float finalDamage = event.getNewDamage();
            if (finalDamage > 0) {
                sendDamageNumber(target, finalDamage);
            }

            // 必须手持千相武器——这才符合「这把器真的喷火吸血」。
            ItemStack weapon = attacker.getMainHandItem();
            if (!(weapon.getItem() instanceof QianxiangWeaponItem)) {
                return;
            }
            ComposedAttributes attr = weapon.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
            if (attr == null) {
                return;
            }
            applySpecialEffects(target, attacker, attr, finalDamage);
        } catch (Throwable t) {
            // 战斗部永不崩：任何意外都吞掉记日志，玩家对局不受影响。
            Qianxiang.LOGGER.error("[Qianxiang] CombatEffectHandler 处理伤害事件时异常", t);
        }
    }

    /** 从 direct/causing 实体里抠出玩家攻击者。非玩家攻击（怪物互殴）返回 null。 */
    private static Player resolveAttacker(Entity directEntity, Entity causingEntity) {
        if (causingEntity instanceof Player p) return p;
        if (directEntity instanceof Player p) return p;
        return null;
    }

    /**
     * 按 {@link ComposedAttributes} 的等级施加五种特效。
     * <p>等级 &gt; 0 才生效（见 ComposedAttributes 注释）。各项互不依赖，可同时叠加。</p>
     * <ul>
     *   <li><b>igniteLevel</b>：{@code target.igniteForTicks(level × 80)}（每级 4 秒可见火焰）。</li>
     *   <li><b>lifestealLevel</b>：{@code attacker.heal(finalDamage × level × 0.2)}。</li>
     *   <li><b>slowLevel</b>：给目标 {@link MobEffects#MOVEMENT_SLOWDOWN}（时长 level×60，药水等级 level-1）。</li>
     *   <li><b>thornsLevel</b>：额外魔法伤害 {@code level × 1.5}（反伤的进攻形态，直接打目标）。</li>
     *   <li><b>healLevel</b>：给攻击者 {@link MobEffects#REGENERATION}（时长 level×60，药水等级 level-1）。</li>
     * </ul>
     * <p>另读取 {@link ComposedAttributes.EffectLevels} 子记录兑现扩展效果：</p>
     * <ul>
     *   <li><b>poison</b>：目标 {@link MobEffects#POISON}（时长 level×60）+ 孢子粒子
     *       （不用 HAPPY_VILLAGER，避免与骨粉催熟的绿星粒子混淆）。</li>
     *   <li><b>frost</b>：目标 {@link MobEffects#MOVEMENT_SLOWDOWN} + {@code setTicksFrozen}
     *       （时长 level×40）+ 雪花粒子。</li>
     *   <li><b>levitation</b>：目标 {@link MobEffects#LEVITATION}（时长 level×20）+ 末地烛粒子。</li>
     *   <li><b>strength</b>：已由 attackDamage 体现，此处不处理。</li>
     * </ul>
     * <p><b>反转（{@link ComposedAttributes#isReversed()}，材料含逆相之核）</b>：
     * 伤害型效果极性倒转——</p>
     * <ul>
     *   <li>点燃 → 冰冻（减速 + 冻结值 + 雪花粒子，按 ignite 等级）。</li>
     *   <li>吸血 → 反吸血：攻击者自伤本该吸到的血量（魔法伤害）。</li>
     *   <li>中毒 → 再生（施于攻击者，时长/等级同中毒）。</li>
     *   <li>漂浮 → 加重迟缓（缓慢药水等级 = level，比常规高一档）。</li>
     * </ul>
     */
    private static void applySpecialEffects(LivingEntity target, Player attacker,
                                            ComposedAttributes attr, float finalDamage) {
        boolean rev = attr.isReversed();

        // —— 灼烧：着火；反转 → 冰冻 ——
        if (attr.igniteLevel() > 0) {
            try {
                if (rev) {
                    int ticks = attr.igniteLevel() * FROST_TICKS_PER_LEVEL;
                    target.addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN, ticks, attr.igniteLevel() - 1));
                    target.setTicksFrozen(target.getTicksFrozen() + ticks);
                    spawnParticles(target, ParticleTypes.SNOWFLAKE, 8 + attr.igniteLevel() * 2);
                } else {
                    // javap LivingEntity 确认：方法名 igniteForTicks(int)，单位 tick。
                    target.igniteForTicks(attr.igniteLevel() * IGNITE_TICKS_PER_LEVEL);
                }
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 施加灼烧失败 lv={}", attr.igniteLevel(), t);
            }
        }

        // —— 吸血：攻击者回血；反转 → 反吸血（攻击者自伤同等血量） ——
        if (attr.lifestealLevel() > 0) {
            try {
                float heal = finalDamage * attr.lifestealLevel() * LIFESTEAL_PER_LEVEL_FACTOR;
                if (heal > 0) {
                    if (rev) {
                        attacker.hurt(attacker.damageSources().magic(), heal);
                    } else {
                        attacker.heal(heal);
                    }
                }
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 施加吸血失败 lv={}", attr.lifestealLevel(), t);
            }
        }

        // —— 迟缓：目标减速 ——
        if (attr.slowLevel() > 0) {
            try {
                target.addEffect(new MobEffectInstance(
                        MobEffects.MOVEMENT_SLOWDOWN,
                        attr.slowLevel() * SLOW_TICKS_PER_LEVEL,
                        attr.slowLevel() - 1)); // 药水等级 0-based：1级→放大器0
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 施加迟缓失败 lv={}", attr.slowLevel(), t);
            }
        }

        // —— 反伤（进攻形态）：额外魔法伤害直接打目标 ——
        if (attr.thornsLevel() > 0) {
            try {
                float magicDmg = attr.thornsLevel() * THORNS_DAMAGE_PER_LEVEL;
                target.hurt(attacker.damageSources().magic(), magicDmg);
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 施加反伤失败 lv={}", attr.thornsLevel(), t);
            }
        }

        // —— 疗伤：攻击者获再生 ——
        if (attr.healLevel() > 0) {
            try {
                attacker.addEffect(new MobEffectInstance(
                        MobEffects.REGENERATION,
                        attr.healLevel() * HEAL_TICKS_PER_LEVEL,
                        attr.healLevel() - 1));
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 施加疗伤失败 lv={}", attr.healLevel(), t);
            }
        }

        // ==================== 扩展效果（EffectLevels 子记录） ====================
        // strength 已由 attackDamage 体现，无需在此处处理。
        ComposedAttributes.EffectLevels effects = attr.effects();
        if (effects != null) {

        // —— 中毒：目标中毒（每级 60 tick），孢子粒子；反转 → 再生施于攻击者 ——
        // 注意：不要用 HAPPY_VILLAGER（绿星）——那是骨粉催熟的标志粒子，玩家会误以为「骨粉效果」。
        // 中毒本身的绿色泡泡由 POISON 状态效果自动环绕目标，这里用 MYCELIUM 孢子做爆发点缀。
        if (effects.poison() > 0) {
            try {
                if (rev) {
                    attacker.addEffect(new MobEffectInstance(
                            MobEffects.REGENERATION,
                            effects.poison() * POISON_TICKS_PER_LEVEL,
                            effects.poison() - 1));
                    spawnParticles(attacker, ParticleTypes.MYCELIUM, 6 + effects.poison() * 2);
                } else {
                    target.addEffect(new MobEffectInstance(
                            MobEffects.POISON,
                            effects.poison() * POISON_TICKS_PER_LEVEL,
                            effects.poison() - 1)); // 药水等级 0-based：1级→放大器0
                    spawnParticles(target, ParticleTypes.MYCELIUM, 6 + effects.poison() * 2);
                }
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 施加中毒失败 lv={}", effects.poison(), t);
            }
        }

        // —— 霜冻：目标减速 + 冻结（每级 40 tick），雪花粒子 ——
        if (effects.frost() > 0) {
            try {
                int ticks = effects.frost() * FROST_TICKS_PER_LEVEL;
                target.addEffect(new MobEffectInstance(
                        MobEffects.MOVEMENT_SLOWDOWN,
                        ticks,
                        effects.frost() - 1));
                // setTicksFrozen 需达到阈值(140)才真正冻伤，按等级直接抬升冻结值。
                target.setTicksFrozen(target.getTicksFrozen() + ticks);
                spawnParticles(target, ParticleTypes.SNOWFLAKE, 8 + effects.frost() * 2);
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 施加霜冻失败 lv={}", effects.frost(), t);
            }
        }

        // —— 漂浮：目标漂浮（每级 20 tick），末地烛粒子；反转 → 加重迟缓 ——
        if (effects.levitation() > 0) {
            try {
                if (rev) {
                    // 加重迟缓：时长用 SLOW 基数，药水等级 = level（比常规 level-1 高一档）
                    target.addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN,
                            effects.levitation() * SLOW_TICKS_PER_LEVEL,
                            Math.min(effects.levitation(), GRANTED_MAX_AMPLIFIER)));
                    spawnParticles(target, ParticleTypes.END_ROD, 6 + effects.levitation() * 2);
                } else {
                    target.addEffect(new MobEffectInstance(
                            MobEffects.LEVITATION,
                            effects.levitation() * LEVITATION_TICKS_PER_LEVEL,
                            effects.levitation() - 1));
                    spawnParticles(target, ParticleTypes.END_ROD, 6 + effects.levitation() * 2);
                }
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 施加漂浮失败 lv={}", effects.levitation(), t);
            }
        }
        }

        // ==================== 通用自由效果（grantedEffects） ====================
        // 由 qianxiang:materials/effect/* tag 材料贡献：攻击时对目标施加任意状态效果。
        // 时长 = 等级 × 60 tick，药水等级 = 等级 - 1（封顶 IV 级）。与固定算子并存。
        applyGrantedEffects(target, attr.grantedEffects());
    }

    /**
     * 遍历武器 grantedEffects（MobEffect registry id → 等级），对目标逐一施加。
     * 未注册的效果 id 安全跳过；单个效果失败不影响其余。
     */
    private static void applyGrantedEffects(LivingEntity target,
                                            Map<ResourceLocation, Integer> granted) {
        if (granted == null || granted.isEmpty()) {
            return;
        }
        for (var entry : granted.entrySet()) {
            int level = entry.getValue() == null ? 0 : entry.getValue();
            if (level <= 0) {
                continue;
            }
            try {
                var holder = BuiltInRegistries.MOB_EFFECT.getHolder(entry.getKey());
                if (holder.isEmpty()) {
                    continue; // 数据包写了不存在的效果 id，跳过不炸
                }
                target.addEffect(new MobEffectInstance(
                        holder.get(),
                        level * GRANTED_TICKS_PER_LEVEL,
                        Math.min(level - 1, GRANTED_MAX_AMPLIFIER)));
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 施加自由效果失败 id={} lv={}", entry.getKey(), level, t);
            }
        }
    }

    /** 在实体腰身位置撒一把粒子（仅服务端 ServerLevel 有效）。 */
    private static void spawnParticles(LivingEntity entity, SimpleParticleType type, int count) {
        try {
            if (entity.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(type,
                        entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(),
                        count, 0.3, 0.4, 0.3, 0.02);
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 发送效果粒子失败 target={}", entity.getId(), t);
        }
    }

    /**
     * 发伤害浮字包给「追踪该目标的所有玩家 + 目标自己」。
     * <p>API 查证（javap PacketDistributor）：
     * {@code PacketDistributor.sendToPlayersTrackingEntityAndSelf(Entity, payload)}。
     * 这正是「看见目标被砍的人」集合——旁观者收到浮字、目标玩家（若是玩家）也收到。</p>
     */
    private static void sendDamageNumber(LivingEntity target, float amount) {
        try {
            if (!(target.level() instanceof ServerLevel)) {
                return;
            }
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                    target, new DamageNumberPayload(target.getId(), amount));
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 发送伤害浮字包失败 target={}", target.getId(), t);
        }
    }
}
