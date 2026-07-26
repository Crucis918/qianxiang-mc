package com.qianxiang.spell;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 法术效果执行器：把 {@link Spell} 的定义兑现为游戏内效果。
 * <p>
 * 所有方法只在服务端调用；粒子和效果由服务端同步给客户端。
 *
 * @deprecated 旧法术系统的执行器，仅为旧存档兼容保留（见 {@link Spell} 的封存说明）。
 *             新效果一律写进 {@link SpellEffectEngine}。
 */
@Deprecated
public final class SpellEffects {

    private SpellEffects() {}

    public static void cast(Spell spell, ServerPlayer player) {
        switch (spell.type()) {
            case FIREBALL -> castFireball(player);
            case HEAL -> castHeal(player);
            case SHIELD -> castShield(player);
            case SLOW -> castSlow(player);
        }
    }

    /** 发射一枚小火球，方向沿玩家视线。 */
    private static void castFireball(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle();
        Vec3 eye = player.getEyePosition(1.0f);
        SmallFireball fireball = new SmallFireball(level, player, look);
        fireball.setPos(eye.x, eye.y, eye.z);
        fireball.setDeltaMovement(look.scale(1.5));
        level.addFreshEntity(fireball);

        level.sendParticles(ParticleTypes.FLAME,
                eye.x, eye.y, eye.z, 6,
                0.1, 0.1, 0.1, 0.05);
    }

    /** 治疗：恢复 6 点生命值（3 颗心）。 */
    private static void castHeal(ServerPlayer player) {
        player.heal(6.0f);
        player.serverLevel().sendParticles(ParticleTypes.HAPPY_VILLAGER,
                player.getX(), player.getY() + player.getBbHeight() * 0.5, player.getZ(), 12,
                0.4, 0.5, 0.4, 0.0);
    }

    /** 护盾：获得 10 秒伤害吸收（+4 黄心）。 */
    private static void castShield(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        player.serverLevel().sendParticles(ParticleTypes.ENCHANTED_HIT,
                player.getX(), player.getY() + player.getBbHeight() * 0.5, player.getZ(), 16,
                0.4, 0.5, 0.4, 0.0);
    }

    /** 迟缓：令玩家周围 4 格内的敌对/中立生物减速 4 秒。 */
    private static void castSlow(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(4.0),
                e -> e != player && e.isAlive()
        );
        for (LivingEntity target : targets) {
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 1));
        }
        level.sendParticles(ParticleTypes.SMOKE,
                player.getX(), player.getY() + 0.2, player.getZ(), 20,
                1.5, 0.2, 1.5, 0.02);
    }
}
