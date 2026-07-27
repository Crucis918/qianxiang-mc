package com.qianxiang.spell;

import com.qianxiang.Qianxiang;
import com.qianxiang.entity.SpellProjectileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 自由法术效果引擎：把 {@link CustomSpell}（元素×形式×效果×修饰）结算为游戏内表现。
 *
 * <p>入口：{@link #cast(CustomSpell, ServerPlayer)}。全部服务端结算，
 * 粒子经 {@link ServerLevel#sendParticles} 下发客户端。所有异常吞掉记日志，
 * 不让一次坏法术崩掉服务端 tick。
 *
 * <p>形式：projectile（发射 {@link SpellProjectileEntity}）/ self / aoe / beam / touch。
 * 命中结算统一走 {@link #resolveHit}，投射物与直接形式共用同一套元素×效果逻辑。
 */
public final class SpellEffectEngine {

    private SpellEffectEngine() {}

    /** 施放一枚自由法术。只在服务端执行；任何失败仅记日志。 */
    public static void cast(CustomSpell spell, ServerPlayer player) {
        cast(spell, player, 1.0f);
    }

    /**
     * 施放一枚自由法术，附带增幅器伤害倍率。
     *
     * @param damageMult 增幅器倍率（1 + 主副手 spellPowerPercent/100，
     *                   见 {@link AmplifierHelper#damageMultiplier}）；
     *                   只放大 damage/heal 结算（power×3.0 / power×2.5 那一跳），
     *                   不影响效果时长/半径等其他派生量
     */
    public static void cast(CustomSpell spell, ServerPlayer player, float damageMult) {
        try {
            if (spell == null || player == null || player.level().isClientSide) {
                return;
            }
            String element = norm(spell.element());
            String form = norm(spell.form());
            String effect = norm(spell.effect());
            Set<String> mods = modSet(spell.modifiers());
            float power = spell.power();
            if (mods.contains("amplified")) {
                power *= 1.5f;
            }
            float mult = damageMult <= 0.0f ? 1.0f : damageMult;

            ServerLevel level = player.serverLevel();
            level.playSound(null, player.blockPosition(), castSoundFor(form, element),
                    SoundSource.PLAYERS, castVolumeFor(element), pitchForForm(form));
            resetTally();
            switch (form) {
                case "self" -> castSelf(level, player, element, effect, power, mods, mult);
                case "aoe" -> castAoe(level, player, element, effect, power, mods, mult);
                case "beam" -> castBeam(level, player, element, effect, power, mods, mult);
                case "touch" -> castTouch(level, player, element, effect, power, mods, mult);
                default -> castProjectile(level, player, element, effect, power, mods, mult);
            }
            // projectile 形式的命中发生在若干 tick 之后，本次同步结算里必然是 0/0，
            // settleCast 的「零受益且有无效目标」条件自然不成立，不会误退款。
            settleCast(player, spell);
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 自由法术施放失败 spell={}", spell, t);
        }
    }

    // ---------- 形式实现 ----------

    /** projectile：发射一枚法术弹体，命中逻辑在 {@link SpellProjectileEntity} 中回调 resolveHit。 */
    private static void castProjectile(ServerLevel level, ServerPlayer player, String element,
                                       String effect, float power, Set<String> mods, float damageMult) {
        Vec3 look = player.getLookAngle();
        Vec3 eye = player.getEyePosition(1.0f);
        SpellProjectileEntity bolt = new SpellProjectileEntity(
                com.qianxiang.entity.QianxiangEntities.SPELL_PROJECTILE.get(), level);
        bolt.setOwner(player);
        bolt.setPos(eye.x + look.x * 0.5, eye.y - 0.1, eye.z + look.z * 0.5);
        bolt.configure(element, effect, power, mods, damageMult);
        bolt.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.6F, 0.0F);
        level.addFreshEntity(bolt);
        burst(level, element, eye.x, eye.y, eye.z, 6, 0.15);
    }

    /** self：对自身结算。heal 回血 power×2.5×增幅倍率；buff 按元素给正面状态 220tick；damage 自身周围 3 格小 aoe。 */
    private static void castSelf(ServerLevel level, ServerPlayer player, String element,
                                 String effect, float power, Set<String> mods, float damageMult) {
        switch (effect) {
            case "heal" -> {
                player.heal(power * 2.5f * damageMult);
                burst(level, element, player.getX(), player.getY() + 1.0, player.getZ(), 14, 0.4);
            }
            case "buff" -> {
                int durMul = mods.contains("extended") ? 2 : 1;
                player.addEffect(new MobEffectInstance(buffFor(element), 220 * durMul, amplifierOf(power)));
                burst(level, element, player.getX(), player.getY() + 1.0, player.getZ(), 16, 0.4);
            }
            case "utility" -> resolveUtility(level, player, player.blockPosition(), player, element);
            default -> {
                // damage / debuff：自身周围 3 格范围结算
                List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
                        player.getBoundingBox().inflate(3.0),
                        e -> e.isAlive() && e != player);
                for (LivingEntity target : targets) {
                    resolveHit(level, player, player, target, element, effect, power, mods, damageMult);
                }
                burst(level, element, player.getX(), player.getY() + 0.5, player.getZ(), 24, 2.0);
            }
        }
    }

    /** aoe：以玩家前方 4 格为圆心，(2+power) 半径范围结算，外加环形粒子。 */
    private static void castAoe(ServerLevel level, ServerPlayer player, String element,
                                String effect, float power, Set<String> mods, float damageMult) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (flat.lengthSqr() < 0.0025) {
            flat = new Vec3(0.0, 0.0, 1.0);
        }
        Vec3 center = player.position().add(flat.normalize().scale(4.0));
        double radius = 2.0 + power;
        double y = player.getY();

        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(new Vec3(center.x, y, center.z), radius * 2.0, 4.0, radius * 2.0),
                e -> e.isAlive() && e != player && e.distanceToSqr(center.x, y, center.z) <= radius * radius);
        for (LivingEntity target : targets) {
            resolveHit(level, player, player, target, element, effect, power, mods, damageMult);
        }

        // 环形粒子圈
        ParticleOptions particle = particleFor(level, element);
        int steps = 28;
        for (int i = 0; i < steps; i++) {
            double angle = Math.PI * 2.0 * i / steps;
            double px = center.x + Math.cos(angle) * radius;
            double pz = center.z + Math.sin(angle) * radius;
            level.sendParticles(particle, px, y + 0.2, pz, 1, 0.0, 0.05, 0.0, 0.0);
        }
    }

    /** beam：视线 raycast 20 格，路径粒子 + 命中结算。 */
    private static void castBeam(ServerLevel level, ServerPlayer player, String element,
                                 String effect, float power, Set<String> mods, float damageMult) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        Vec3 maxEnd = eye.add(look.scale(20.0));

        BlockHitResult blockHit = level.clip(new ClipContext(eye, maxEnd,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double reach = blockHit.getType() == HitResult.Type.MISS ? 20.0 : eye.distanceTo(blockHit.getLocation());
        Vec3 reachEnd = eye.add(look.scale(reach));

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(level, player, eye, reachEnd,
                player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0),
                e -> e instanceof LivingEntity && e.isAlive() && e != player);

        Vec3 particleEnd = entityHit != null ? entityHit.getLocation() : reachEnd;
        // 路径粒子
        ParticleOptions particle = particleFor(level, element);
        double len = eye.distanceTo(particleEnd);
        for (double d = 0.6; d < len; d += 0.6) {
            Vec3 p = eye.add(look.scale(d));
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }

        if (entityHit != null && entityHit.getEntity() instanceof LivingEntity target) {
            resolveHit(level, player, player, target, element, effect, power, mods, damageMult);
        }
    }

    /** touch：准星指向 4 格内最近实体结算。 */
    private static void castTouch(ServerLevel level, ServerPlayer player, String element,
                                  String effect, float power, Set<String> mods, float damageMult) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(4.0));

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(level, player, eye, end,
                player.getBoundingBox().expandTowards(look.scale(4.0)).inflate(2.0),
                e -> e instanceof LivingEntity && e.isAlive() && e != player);

        if (entityHit != null && entityHit.getEntity() instanceof LivingEntity target) {
            resolveHit(level, player, player, target, element, effect, power, mods, damageMult);
        } else {
            burst(level, element, end.x, end.y, end.z, 6, 0.2);
        }
    }

    // ---------- 命中结算（投射物与各形式共用） ----------

    /**
     * 对单个目标按 effect + element + power 结算（无增幅倍率的旧入口，等价于 mult=1）。
     *
     * @param direct 伤害的直接来源实体（投射物或玩家自身），可为 null
     */
    public static void resolveHit(ServerLevel level, @Nullable ServerPlayer caster, @Nullable Entity direct,
                                  LivingEntity target, String element, String effect,
                                  float power, Set<String> mods) {
        resolveHit(level, caster, direct, target, element, effect, power, mods, 1.0f);
    }

    /**
     * 对单个目标按 effect + element + power 结算，附带增幅器伤害倍率。
     *
     * @param direct     伤害的直接来源实体（投射物或玩家自身），可为 null
     * @param damageMult 增幅器倍率（见 {@link AmplifierHelper#damageMultiplier}），
     *                   只放大 damage/heal 结算，不影响效果时长等派生量
     */
    public static void resolveHit(ServerLevel level, @Nullable ServerPlayer caster, @Nullable Entity direct,
                                  LivingEntity target, String element, String effect,
                                  float power, Set<String> mods, float damageMult) {
        try {
            int durMul = mods.contains("extended") ? 2 : 1;
            switch (effect) {
                case "heal" -> {
                    if (isAlly(caster, target)) {
                        target.heal(power * 2.5f * damageMult);
                        burstAt(level, element, target, 12);
                        markEffective();
                    } else {
                        markIneffective();
                    }
                }
                case "buff" -> {
                    if (isAlly(caster, target)) {
                        target.addEffect(new MobEffectInstance(buffFor(element), 220 * durMul, amplifierOf(power)));
                        burstAt(level, element, target, 12);
                        markEffective();
                    } else {
                        markIneffective();
                    }
                }
                case "debuff" -> resolveDebuff(level, target, element, power, durMul);
                case "utility" -> resolveUtility(level, caster, target.blockPosition(), target, element);
                default -> resolveDamage(level, caster, direct, target, element, power, mods, durMul, damageMult);
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 法术命中结算失败 element={} effect={}", element, effect, t);
        }
    }

    /**
     * debuff：按元素分派的负面效果。时长统一 {@code (int)(power*40) * durMul}，
     * 等级 {@code amplifierOf(power)}（nature 中毒借此可到 II）。
     */
    private static void resolveDebuff(ServerLevel level, LivingEntity target, String element,
                                      float power, int durMul) {
        int dur = (int) (power * 40) * durMul;
        int amp = amplifierOf(power);
        switch (element) {
            case "fire" -> {
                target.igniteForTicks((int) (power * 20) * durMul);
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, dur, amp));
            }
            case "frost" -> {
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, dur, amp));
                target.setTicksFrozen(target.getTicksFrozen() + (int) (power * 30) * durMul);
            }
            case "lightning" -> {
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, dur, amp));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, dur, amp));
            }
            case "nature" -> {
                target.addEffect(new MobEffectInstance(MobEffects.POISON, dur, amp));
                target.addEffect(new MobEffectInstance(MobEffects.CONFUSION, dur, amp));
            }
            case "shadow" -> {
                target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, dur, amp));
                target.addEffect(new MobEffectInstance(MobEffects.DARKNESS, dur, amp));
            }
            case "holy" -> {
                target.addEffect(new MobEffectInstance(MobEffects.GLOWING, dur, amp));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, dur, amp));
            }
            case "blood" -> target.addEffect(new MobEffectInstance(MobEffects.WITHER, dur, amp));
            case "ender" -> target.addEffect(new MobEffectInstance(MobEffects.LEVITATION, dur, amp));
            default -> { // arcane
                target.addEffect(new MobEffectInstance(MobEffects.CONFUSION, dur, amp));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, dur, amp));
            }
        }
        burstAt(level, element, target, 12);
    }

    /** damage：power×3.0×增幅倍率 魔法伤害（对标武器 EDGE 件 3.0×档倍的标定）+ 元素附加。 */
    private static void resolveDamage(ServerLevel level, @Nullable ServerPlayer caster, @Nullable Entity direct,
                                      LivingEntity target, String element, float power,
                                      Set<String> mods, int durMul, float damageMult) {
        float dmg = power * 3.0f * damageMult;
        if (caster != null) {
            target.hurt(caster.damageSources().indirectMagic(direct != null ? direct : caster, caster), dmg);
        } else {
            target.hurt(level.damageSources().magic(), dmg);
        }
        burstAt(level, element, target, 14);
        level.playSound(null, target.blockPosition(), hitSoundFor(element),
                SoundSource.PLAYERS, hitVolumeFor(element), 1.0f);
        if (!target.isAlive()) {
            return;
        }

        switch (element) {
            case "fire" -> target.igniteForTicks((int) (power * 20) * durMul);
            case "frost" -> target.setTicksFrozen(target.getTicksFrozen() + (int) (power * 30) * durMul);
            case "lightning" -> {
                if (mods.contains("chain")) {
                    chainLightning(level, caster, target, dmg);
                }
            }
            case "shadow" -> {
                int dur = (int) (power * 40) * durMul;
                target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, dur, 0));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, dur, 0));
            }
            case "blood" -> {
                if (caster != null) {
                    caster.heal(dmg * 0.3f);
                }
            }
            case "ender" -> {
                if (caster != null) {
                    caster.connection.teleport(target.getX(), target.getY() + 0.1, target.getZ(),
                            caster.getYRot(), caster.getXRot());
                }
            }
            case "nature" -> {
                int dur = (int) (power * 40) * durMul;
                target.addEffect(new MobEffectInstance(MobEffects.POISON, dur, amplifierOf(power)));
            }
            case "holy" -> {
                // 圣光克亡灵：对亡灵补一跳 50% 额外神圣伤，并小量回复施法者
                if (target.isInvertedHealAndHarm()) {
                    if (caster != null) {
                        target.hurt(caster.damageSources().indirectMagic(direct != null ? direct : caster, caster),
                                dmg * 0.5f);
                    } else {
                        target.hurt(level.damageSources().magic(), dmg * 0.5f);
                    }
                }
                if (caster != null) {
                    caster.heal(1.0f);
                }
            }
            case "arcane" -> {
                // 奥术印记：发光标记 + 短暂虚弱，为后续攻击创造窗口
                int dur = (int) (power * 40) * durMul;
                target.addEffect(new MobEffectInstance(MobEffects.GLOWING, dur, 0));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, dur / 2, 0));
            }
            default -> { }
        }
    }

    /** lightning + chain：向附近最多 2 个敌人跳跃，每次衰减 50%。 */
    private static void chainLightning(ServerLevel level, @Nullable ServerPlayer caster,
                                       LivingEntity from, float baseDmg) {
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class,
                from.getBoundingBox().inflate(6.0),
                e -> e.isAlive() && e != from && e != caster && !isAlly(caster, e));
        nearby.sort(Comparator.comparingDouble(from::distanceToSqr));
        float dmg = baseDmg;
        Vec3 prev = from.getBoundingBox().getCenter();
        int jumps = 0;
        for (LivingEntity next : nearby) {
            if (jumps >= 2) {
                break;
            }
            dmg *= 0.5f;
            if (caster != null) {
                next.hurt(caster.damageSources().indirectMagic(caster, caster), dmg);
            } else {
                next.hurt(level.damageSources().magic(), dmg);
            }
            Vec3 hit = next.getBoundingBox().getCenter();
            beamParticles(level, ParticleTypes.ELECTRIC_SPARK, prev, hit);
            prev = hit;
            jumps++;
        }
    }

    /** utility：按元素 —— holy=群疗友方，nature=骨粉效果，ender=传送施法者到目标点，
     *  blood=血换蓝，fire/frost/lightning/shadow/arcane=施法者自身状态，其余仅粒子。 */
    private static void resolveUtility(ServerLevel level, @Nullable ServerPlayer caster, BlockPos pos,
                                       @Nullable Entity focus, String element) {
        switch (element) {
            case "holy" -> {
                Vec3 center = focus != null ? focus.position() : Vec3.atCenterOf(pos);
                if (caster != null) {
                    caster.heal(4.0f);
                    for (LivingEntity ally : level.getEntitiesOfClass(LivingEntity.class,
                            caster.getBoundingBox().inflate(5.0), e -> e.isAlive() && isAlly(caster, e))) {
                        ally.heal(4.0f);
                    }
                }
                burst(level, element, center.x, center.y + 0.5, center.z, 20, 0.5);
            }
            case "nature" -> {
                BoneMealItem.applyBonemeal(new ItemStack(Items.BONE_MEAL), level, pos, caster);
                level.levelEvent(2005, pos, 0); // 骨粉粒子
                level.levelEvent(2005, pos.above(), 0);
            }
            case "ender" -> {
                if (caster != null) {
                    caster.connection.teleport(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5,
                            caster.getYRot(), caster.getXRot());
                    burst(level, element, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 20, 0.3);
                }
            }
            case "blood" -> resolveBloodMana(level, caster);
            case "fire" -> {
                if (caster != null) {
                    caster.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1200, 0));
                    burstAt(level, element, caster, 16);
                }
            }
            case "frost" -> {
                if (caster != null) {
                    caster.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 1200, 0));
                    burstAt(level, element, caster, 16);
                }
            }
            case "lightning" -> {
                if (caster != null) {
                    caster.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1200, 0));
                    burstAt(level, element, caster, 16);
                }
            }
            case "shadow" -> {
                if (caster != null) {
                    caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 600, 0));
                    burstAt(level, element, caster, 16);
                }
            }
            default -> { // arcane
                if (caster != null) {
                    caster.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1200, 0));
                    burstAt(level, element, caster, 16);
                }
            }
        }
    }

    /**
     * blood utility：血换蓝——当前生命 > 4 时自伤 4 点换 20 法力
     * （操作 PLAYER_SPELL_DATA attachment，withMana 钳到 maxMana，与 settleCast 同款写法）；
     * 生命 ≤ 4 不发动，actionbar 提示。
     */
    private static void resolveBloodMana(ServerLevel level, @Nullable ServerPlayer caster) {
        if (caster == null) return;
        if (caster.getHealth() <= 4.0f) {
            caster.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "qianxiang.spell.blood_mana.low_hp"), true);
            return;
        }
        caster.hurt(caster.damageSources().magic(), 4.0f);
        var data = caster.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA);
        int restored = Math.min(data.maxMana(), data.currentMana() + 20);
        if (restored != data.currentMana()) {
            caster.setData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA,
                    data.withMana(restored));
        }
        SpellCastHandler.sync(caster);
        burstAt(level, "blood", caster, 16);
    }

    // ---------- 工具 ----------

    // ===== 「本次施法是否有任何目标真正受益」的 per-cast 记账 =====
    // 退款必须按「一次施法」结算，不能按「每个命中目标」：resolveHit 会被 AoE 与
    // 链式循环逐目标调用，挂在那里等于一发 AoE 命中 5 只怪就退 5 次；
    // 若命中集合里还混着友方，治疗照常生效却仍退蓝——补偿直接变成奖励。
    // 这两条加起来比原来的「静默扣蓝」更糟，所以记账只在这里累计，
    // 真正的退款在 cast() 收尾时一次性结算。

    private static final ThreadLocal<int[]> CAST_TALLY = ThreadLocal.withInitial(() -> new int[2]);
    private static final int IDX_EFFECTIVE = 0;
    private static final int IDX_INEFFECTIVE = 1;

    /** 本次施法有一个目标真正吃到了效果。 */
    private static void markEffective() {
        CAST_TALLY.get()[IDX_EFFECTIVE]++;
    }

    /** 本次施法有一个目标因「非友方」而什么都没发生。 */
    private static void markIneffective() {
        CAST_TALLY.get()[IDX_INEFFECTIVE]++;
    }

    // ===== 测试入口：让 GameTest 能直接验证记账判据，不必构造真实网络连接 =====

    /** 在干净记账上跑一段标记操作，返回 [受益数, 无效数]。 */
    public static int[] tallySnapshotForTest(Runnable marking) {
        resetTally();
        marking.run();
        int[] t = CAST_TALLY.get();
        int[] snapshot = {t[IDX_EFFECTIVE], t[IDX_INEFFECTIVE]};
        resetTally();
        return snapshot;
    }

    public static void markEffectiveForTest() {
        markEffective();
    }

    public static void markIneffectiveForTest() {
        markIneffective();
    }

    /** 退款判据：零受益且至少有一个无效目标（与目标数无关，故最多退一次）。 */
    public static boolean shouldRefundForTest(int[] tally) {
        return tally[IDX_EFFECTIVE] == 0 && tally[IDX_INEFFECTIVE] > 0;
    }

    private static void resetTally() {
        int[] t = CAST_TALLY.get();
        t[IDX_EFFECTIVE] = 0;
        t[IDX_INEFFECTIVE] = 0;
    }

    /**
     * 一次施法收尾：只有「零个目标受益且至少有一个目标被判无效」时才退款，
     * 且<b>退实际 manaCost</b>（此前写死 10+5*1=15，cost 小于它的法术每次净赚）。
     */
    private static void settleCast(@Nullable ServerPlayer caster, CustomSpell spell) {
        int[] t = CAST_TALLY.get();
        boolean nothingWorked = t[IDX_EFFECTIVE] == 0 && t[IDX_INEFFECTIVE] > 0;
        resetTally();
        if (caster == null || spell == null || !nothingWorked) {
            return;
        }
        try {
            var data = caster.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA);
            int restored = Math.min(data.maxMana(), data.currentMana() + Math.max(0, spell.manaCost()));
            if (restored != data.currentMana()) {
                caster.setData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA,
                        data.withMana(restored));
                SpellCastHandler.sync(caster);
            }
            if (com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    caster, "spell_invalid_target", 1_000L)) {
                caster.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "qianxiang.spell.ally_only", spell.effect()), true);
            }
        } catch (Throwable t2) {
            Qianxiang.LOGGER.debug("[Qianxiang] 无效目标补偿失败（不影响主流程）：{}", t2.toString());
        }
    }

    /**
     * 命中音：按元素分派（与施法音成对，反馈闭环）。
     * 注：1.21.1 无 SCULK_BREAK，shadow 用最接近的 {@code SCULK_BLOCK_BREAK}。
     */
    private static SoundEvent hitSoundFor(String element) {
        return switch (element) {
            case "fire" -> SoundEvents.GENERIC_BURN;
            case "frost" -> SoundEvents.GLASS_BREAK;
            case "lightning" -> SoundEvents.LIGHTNING_BOLT_IMPACT;
            case "nature" -> SoundEvents.ROOTED_DIRT_PLACE;
            case "shadow" -> SoundEvents.SCULK_BLOCK_BREAK;
            case "holy" -> SoundEvents.BEACON_POWER_SELECT;
            case "blood" -> SoundEvents.WARDEN_STEP;
            case "ender" -> SoundEvents.ENDERMAN_HURT;
            default -> SoundEvents.AMETHYST_CLUSTER_BREAK; // arcane
        };
    }

    /** 命中音音量：雷劈太炸压到 0.3，其余与历史值 0.5 相当。 */
    private static float hitVolumeFor(String element) {
        return "lightning".equals(element) ? 0.3f : 0.5f;
    }

    /**
     * 施法音：元素定主音、form 微调音高（见 {@link #pitchForForm}）。
     * 注：1.21.1 无 BLOCK_GLASS_BREAK，frost 用 {@code GLASS_BREAK}（同一事件）。
     */
    private static SoundEvent castSoundFor(String form, String element) {
        return switch (element) {
            case "fire" -> SoundEvents.BLAZE_SHOOT;
            case "frost" -> SoundEvents.GLASS_BREAK;
            case "lightning" -> SoundEvents.TRIDENT_THUNDER.value(); // 1.21.1 该字段是 Holder
            case "nature" -> SoundEvents.BONE_MEAL_USE;
            case "shadow" -> SoundEvents.SCULK_CLICKING; // 无 SCULK_CLICK，用最接近的 SCULK_CLICKING
            case "holy" -> SoundEvents.TOTEM_USE;
            case "blood" -> SoundEvents.WARDEN_HEARTBEAT;
            case "ender" -> SoundEvents.ENDERMAN_TELEPORT;
            default -> SoundEvents.AMETHYST_BLOCK_CHIME; // arcane
        };
    }

    /** 施法音音量：雷声/图腾太炸，压一档；其余与历史值 0.6 相当。 */
    private static float castVolumeFor(String element) {
        return switch (element) {
            case "lightning" -> 0.4f;
            case "holy" -> 0.5f;
            default -> 0.6f;
        };
    }

    /** 施法音音高：form 微调——aoe 低沉、touch 尖细，听感区分施法形态。 */
    private static float pitchForForm(String form) {
        return switch (form) {
            case "beam" -> 0.9f;
            case "aoe" -> 0.8f;
            case "self" -> 1.1f;
            case "touch" -> 1.2f;
            default -> 1.0f; // projectile
        };
    }

    /** 元素 → 粒子。 */
    public static ParticleOptions particleFor(ServerLevel level, String element) {
        return switch (element) {
            case "fire" -> ParticleTypes.FLAME;
            case "frost" -> ParticleTypes.SNOWFLAKE;
            case "lightning" -> ParticleTypes.ELECTRIC_SPARK;
            case "nature" -> ParticleTypes.HAPPY_VILLAGER;
            case "shadow" -> level.random.nextBoolean() ? ParticleTypes.SMOKE : ParticleTypes.SCULK_SOUL;
            case "holy" -> ParticleTypes.END_ROD;
            case "blood" -> new DustParticleOptions(new Vector3f(0.75f, 0.05f, 0.05f), 1.0f);
            case "ender" -> ParticleTypes.PORTAL;
            default -> ParticleTypes.WITCH; // arcane
        };
    }

    /** 元素 → 自身 buff（220tick 由调用方控制）。 */
    private static net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> buffFor(String element) {
        return switch (element) {
            case "fire" -> MobEffects.FIRE_RESISTANCE;
            case "frost" -> MobEffects.DAMAGE_RESISTANCE;
            case "lightning" -> MobEffects.MOVEMENT_SPEED;
            case "nature" -> MobEffects.REGENERATION;
            case "shadow" -> MobEffects.INVISIBILITY;
            case "holy" -> MobEffects.ABSORPTION;
            case "blood" -> MobEffects.DAMAGE_BOOST;
            case "ender" -> MobEffects.SLOW_FALLING;
            default -> MobEffects.NIGHT_VISION; // arcane
        };
    }

    private static int amplifierOf(float power) {
        return Math.max(0, Math.min(2, (int) (power / 3.0f)));
    }

    private static boolean isAlly(@Nullable ServerPlayer caster, LivingEntity target) {
        if (caster == null) {
            return true;
        }
        return target == caster || target instanceof Player || caster.isAlliedTo(target);
    }

    private static void burstAt(ServerLevel level, String element, LivingEntity target, int count) {
        burst(level, element, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(), count, 0.3);
    }

    private static void burst(ServerLevel level, String element, double x, double y, double z,
                              int count, double spread) {
        level.sendParticles(particleFor(level, element), x, y, z, count, spread, spread, spread, 0.02);
    }

    private static void beamParticles(ServerLevel level, ParticleOptions particle, Vec3 from, Vec3 to) {
        Vec3 dir = to.subtract(from);
        double len = dir.length();
        if (len < 1.0E-4) {
            return;
        }
        dir = dir.normalize();
        for (double d = 0.0; d <= len; d += 0.4) {
            Vec3 p = from.add(dir.scale(d));
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static String norm(@Nullable Object o) {
        return o == null ? "" : String.valueOf(o).toLowerCase(Locale.ROOT);
    }

    /** 修饰列表规整为小写集合；对 String / 枚举元素都安全。 */
    public static Set<String> modSet(@Nullable Iterable<?> modifiers) {
        Set<String> set = new HashSet<>();
        if (modifiers != null) {
            for (Object m : modifiers) {
                String s = norm(m);
                if (!s.isEmpty()) {
                    set.add(s);
                }
            }
        }
        return set;
    }
}
