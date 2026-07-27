package com.qianxiang.entity;

import com.qianxiang.Qianxiang;
import com.qianxiang.spell.SpellEffectEngine;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自由法术弹体：承载一枚 CustomSpell 的 element/effect/power/modifiers 飞行，
 * 命中后回调 {@link SpellEffectEngine#resolveHit} 做统一结算。
 *
 * <p>视觉完全由服务端 {@code sendParticles} 轨迹粒子承担（渲染器为空实现），
 * 因此法术参数只需存在于服务端字段，不做客户端同步。
 *
 * <p>修饰支持：homing（tick 中向最近敌人小角度转向）、piercing（命中不消失，
 * 继续穿 power 个实体）、extended / amplified / chain 在结算层处理。
 */
public class SpellProjectileEntity extends ThrowableItemProjectile {

    private static final int MAX_LIFETIME_TICKS = 160;
    private static final double HOMING_RANGE = 10.0;
    private static final double HOMING_STEER = 0.12;

    /** 追踪目标重选间隔（tick）：范围查询昂贵，追踪手感不需要每 tick 重算。 */
    private static final int HOMING_RETARGET_INTERVAL = 4;

    /** 缓存的追踪目标 entity id（-1 = 无）。不落盘：弹体寿命只有 160 tick。 */
    private int cachedTargetId = -1;

    private String element = "arcane";
    private String effect = "damage";
    private float power = 1.0f;
    private Set<String> mods = Set.of();
    /** 增幅器伤害倍率（发射瞬间快照，命中经 resolveHit 放大 damage/heal）。 */
    private float damageMult = 1.0f;
    private boolean homing;
    private int pierceRemaining;
    /** 已经命中过的实体 id，穿透时避免对同一目标反复结算。 */
    private final Set<Integer> hitIds = new HashSet<>();

    public SpellProjectileEntity(EntityType<? extends SpellProjectileEntity> type, Level level) {
        super(type, level);
    }

    /** 发射前由引擎写入法术参数（仅服务端有意义）。 */
    public void configure(String element, String effect, float power, Set<String> mods) {
        configure(element, effect, power, mods, 1.0f);
    }

    /** 发射前由引擎写入法术参数 + 增幅器伤害倍率（仅服务端有意义）。 */
    public void configure(String element, String effect, float power, Set<String> mods, float damageMult) {
        this.element = element == null ? "arcane" : element;
        this.effect = effect == null ? "damage" : effect;
        this.power = power;
        this.mods = mods == null ? Set.of() : Set.copyOf(mods);
        this.damageMult = damageMult <= 0.0f ? 1.0f : damageMult;
        this.homing = this.mods.contains("homing");
        this.pierceRemaining = this.mods.contains("piercing") ? Math.max(0, (int) power) : 0;
    }

    @Override
    protected net.minecraft.world.item.Item getDefaultItem() {
        return Items.FIRE_CHARGE;
    }

    /** 魔法弹体不受重力，直线飞行。 */
    @Override
    protected double getDefaultGravity() {
        return 0.0;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        try {
            // 元素轨迹粒子（服务端下发，客户端只负责显示）
            if (level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(SpellEffectEngine.particleFor(serverLevel, element),
                        getX(), getY(), getZ(), 2, 0.02, 0.02, 0.02, 0.0);
            }
            if (homing) {
                steerTowardsNearestEnemy();
            }
            if (tickCount > MAX_LIFETIME_TICKS) {
                discard();
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 法术弹体 tick 异常", t);
            discard();
        }
    }

    /** homing：向范围内最近的敌人小角度转向，保持速度大小不变。 */
    private void steerTowardsNearestEnemy() {
        Vec3 motion = getDeltaMovement();
        double speed = motion.length();
        if (speed < 1.0E-4) {
            return;
        }
        // 目标选择每 HOMING_RETARGET_INTERVAL tick 做一次并缓存目标 id：
        // 原实现每 tick 都做一次半径 10 的范围查询 + 排序，弹幕多时是可观的服务端开销，
        // 而追踪效果并不需要每 tick 重选目标。
        LivingEntity target = null;
        if (cachedTargetId != -1 && tickCount % HOMING_RETARGET_INTERVAL != 0) {
            if (level().getEntity(cachedTargetId) instanceof LivingEntity cached
                    && cached.isAlive() && !hitIds.contains(cached.getId())
                    && distanceToSqr(cached) <= HOMING_RANGE * HOMING_RANGE * 4) {
                target = cached;
            }
        }
        if (target == null) {
            List<LivingEntity> candidates = level().getEntitiesOfClass(LivingEntity.class,
                    getBoundingBox().inflate(HOMING_RANGE),
                    e -> e.isAlive() && e != getOwner() && !hitIds.contains(e.getId()));
            if (candidates.isEmpty()) {
                cachedTargetId = -1;
                return;
            }
            candidates.sort(Comparator.comparingDouble(this::distanceToSqr));
            target = candidates.get(0);
            cachedTargetId = target.getId();
        }
        Vec3 toTarget = target.getBoundingBox().getCenter().subtract(position()).normalize();
        Vec3 steered = motion.normalize().add(toTarget.scale(HOMING_STEER)).normalize().scale(speed);
        setDeltaMovement(steered);
    }

    /**
     * 命中分发：自行实现而不走 {@link ThrowableItemProjectile#onHit}，
     * 因为父类命中即 discard，无法支持 piercing 穿透。
     */
    @Override
    protected void onHit(HitResult hitResult) {
        if (hitResult.getType() == HitResult.Type.ENTITY) {
            onHitEntity((EntityHitResult) hitResult);
        } else if (hitResult.getType() == HitResult.Type.BLOCK) {
            onHitBlock((BlockHitResult) hitResult);
            if (!level().isClientSide) {
                discard();
            }
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        if (level().isClientSide) {
            return;
        }
        try {
            if (!(hitResult.getEntity() instanceof LivingEntity target)) {
                return;
            }
            if (target == getOwner() || hitIds.contains(target.getId())) {
                return;
            }
            hitIds.add(target.getId());

            ServerPlayer caster = getOwner() instanceof ServerPlayer sp ? sp : null;
            if (level() instanceof ServerLevel serverLevel) {
                SpellEffectEngine.resolveHit(serverLevel, caster, this, target,
                        element, effect, power, mods, damageMult);
            }

            // piercing：还能穿就不消失，否则消散
            if (pierceRemaining > 0) {
                pierceRemaining--;
            } else {
                discard();
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 法术弹体命中结算异常", t);
            discard();
        }
    }

    /** 允许穿过实体（piercing 命中后继续飞行），其余照旧。 */
    @Override
    protected boolean canHitEntity(net.minecraft.world.entity.Entity target) {
        return super.canHitEntity(target) && !hitIds.contains(target.getId());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("qx_element", element);
        tag.putString("qx_effect", effect);
        tag.putFloat("qx_power", power);
        tag.putFloat("qx_dmg_mult", damageMult);
        tag.putString("qx_mods", String.join(",", mods));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // 旧存档无 qx_dmg_mult 键，缺省 1.0（无增幅）。
        float mult = tag.contains("qx_dmg_mult") ? tag.getFloat("qx_dmg_mult") : 1.0f;
        configure(tag.getString("qx_element"), tag.getString("qx_effect"),
                tag.getFloat("qx_power"), parseMods(tag.getString("qx_mods")), mult);
    }

    private static Set<String> parseMods(String csv) {
        Set<String> set = new HashSet<>();
        if (csv != null && !csv.isEmpty()) {
            for (String m : csv.split(",")) {
                if (!m.isBlank()) {
                    set.add(m.trim());
                }
            }
        }
        return set;
    }
}
