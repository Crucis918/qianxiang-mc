package com.qianxiang.spell;

import com.qianxiang.Qianxiang;
import com.qianxiang.entity.SpellProjectileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

            ServerLevel level = player.serverLevel();
            switch (form) {
                case "self" -> castSelf(level, player, element, effect, power, mods);
                case "aoe" -> castAoe(level, player, element, effect, power, mods);
                case "beam" -> castBeam(level, player, element, effect, power, mods);
                case "touch" -> castTouch(level, player, element, effect, power, mods);
                default -> castProjectile(level, player, element, effect, power, mods);
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 自由法术施放失败 spell={}", spell, t);
        }
    }

    // ---------- 形式实现 ----------

    /** projectile：发射一枚法术弹体，命中逻辑在 {@link SpellProjectileEntity} 中回调 resolveHit。 */
    private static void castProjectile(ServerLevel level, ServerPlayer player, String element,
                                       String effect, float power, Set<String> mods) {
        Vec3 look = player.getLookAngle();
        Vec3 eye = player.getEyePosition(1.0f);
        SpellProjectileEntity bolt = new SpellProjectileEntity(
                com.qianxiang.entity.QianxiangEntities.SPELL_PROJECTILE.get(), level);
        bolt.setOwner(player);
        bolt.setPos(eye.x + look.x * 0.5, eye.y - 0.1, eye.z + look.z * 0.5);
        bolt.configure(element, effect, power, mods);
        bolt.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.6F, 0.0F);
        level.addFreshEntity(bolt);
        burst(level, element, eye.x, eye.y, eye.z, 6, 0.15);
    }

    /** self：对自身结算。heal 回血 power×2；buff 按元素给正面状态 220tick；damage 自身周围 3 格小 aoe。 */
    private static void castSelf(ServerLevel level, ServerPlayer player, String element,
                                 String effect, float power, Set<String> mods) {
        switch (effect) {
            case "heal" -> {
                player.heal(power * 2.0f);
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
                    resolveHit(level, player, player, target, element, effect, power, mods);
                }
                burst(level, element, player.getX(), player.getY() + 0.5, player.getZ(), 24, 2.0);
            }
        }
    }

    /** aoe：以玩家前方 4 格为圆心，(2+power) 半径范围结算，外加环形粒子。 */
    private static void castAoe(ServerLevel level, ServerPlayer player, String element,
                                String effect, float power, Set<String> mods) {
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
            resolveHit(level, player, player, target, element, effect, power, mods);
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
                                 String effect, float power, Set<String> mods) {
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
            resolveHit(level, player, player, target, element, effect, power, mods);
        }
    }

    /** touch：准星指向 4 格内最近实体结算。 */
    private static void castTouch(ServerLevel level, ServerPlayer player, String element,
                                  String effect, float power, Set<String> mods) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(4.0));

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(level, player, eye, end,
                player.getBoundingBox().expandTowards(look.scale(4.0)).inflate(2.0),
                e -> e instanceof LivingEntity && e.isAlive() && e != player);

        if (entityHit != null && entityHit.getEntity() instanceof LivingEntity target) {
            resolveHit(level, player, player, target, element, effect, power, mods);
        } else {
            burst(level, element, end.x, end.y, end.z, 6, 0.2);
        }
    }

    // ---------- 命中结算（投射物与各形式共用） ----------

    /**
     * 对单个目标按 effect + element + power 结算。
     *
     * @param direct 伤害的直接来源实体（投射物或玩家自身），可为 null
     */
    public static void resolveHit(ServerLevel level, @Nullable ServerPlayer caster, @Nullable Entity direct,
                                  LivingEntity target, String element, String effect,
                                  float power, Set<String> mods) {
        try {
            int durMul = mods.contains("extended") ? 2 : 1;
            switch (effect) {
                case "heal" -> {
                    if (isAlly(caster, target)) {
                        target.heal(power * 2.0f);
                        burstAt(level, element, target, 12);
                    }
                }
                case "buff" -> {
                    if (isAlly(caster, target)) {
                        target.addEffect(new MobEffectInstance(buffFor(element), 220 * durMul, amplifierOf(power)));
                        burstAt(level, element, target, 12);
                    }
                }
                case "debuff" -> {
                    int dur = (int) (power * 40) * durMul;
                    target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, dur, amplifierOf(power)));
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, dur, amplifierOf(power)));
                    burstAt(level, element, target, 12);
                }
                case "utility" -> resolveUtility(level, caster, target.blockPosition(), target, element);
                default -> resolveDamage(level, caster, direct, target, element, power, mods, durMul);
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 法术命中结算失败 element={} effect={}", element, effect, t);
        }
    }

    /** damage：power×2.0 魔法伤害 + 元素附加。 */
    private static void resolveDamage(ServerLevel level, @Nullable ServerPlayer caster, @Nullable Entity direct,
                                      LivingEntity target, String element, float power,
                                      Set<String> mods, int durMul) {
        float dmg = power * 2.0f;
        if (caster != null) {
            target.hurt(caster.damageSources().indirectMagic(direct != null ? direct : caster, caster), dmg);
        } else {
            target.hurt(level.damageSources().magic(), dmg);
        }
        burstAt(level, element, target, 14);
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
            default -> { /* nature / holy / arcane 无附加 */ }
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

    /** utility：按元素 —— nature=骨粉效果，ender=传送施法者到目标点，其余仅粒子。 */
    private static void resolveUtility(ServerLevel level, @Nullable ServerPlayer caster, BlockPos pos,
                                       @Nullable Entity focus, String element) {
        switch (element) {
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
            default -> {
                Vec3 v = focus != null ? focus.position() : Vec3.atCenterOf(pos);
                burst(level, element, v.x, v.y + 0.5, v.z, 16, 0.4);
            }
        }
    }

    // ---------- 工具 ----------

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
