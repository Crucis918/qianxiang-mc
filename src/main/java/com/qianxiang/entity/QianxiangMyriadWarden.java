package com.qianxiang.entity;

import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.level.Level;

/**
 * 森罗守望者 —— 万象森罗的漫游 Boss。
 * <p>
 * 万象生机凝聚出的守望之躯，游荡在维度各处看护相之源头。
 * 继承 {@link Zombie} 复用完整近战 AI/寻路，在其上：
 * <ul>
 *   <li>高属性：120 HP / 12 攻击 / 高击退抗性（见 {@link #createAttributes()}）。</li>
 *   <li>紫色 Boss 血条（{@link ServerBossEvent}，进入视野的玩家可见）。</li>
 *   <li><b>裂隙冲击波</b>：目标近身时周期性声爆 AOE + 击退——不许玩家贴脸站桩。</li>
 *   <li><b>裂隙追踪弹</b>：目标拉开距离时发射追踪弹（{@link ShulkerBullet}），
 *       命中悬浮——风筝流要边跑边处理"被裂隙托起"。</li>
 *   <li><b>半血狂化</b>（一次性相位转换）：血条转红、移速 +30%、召唤 3 只裂隙蠹
 *       （{@link Endermite}），并缩短两个技能的冷却。狂化状态落盘。</li>
 *   <li>音效走 Warden 系（吼/伤/亡/步），摆脱僵尸嗓。</li>
 *   <li>不怕阳光、不转化溺尸——它不是亡灵，是维度的免疫应答。</li>
 *   <li>战斗中不脱战消失（{@link #removeWhenFarAway}），脱战后照常随距离清除。</li>
 *   <li>掉落传奇材料（见 {@code loot_table/entities/myriad_warden.json}：
 *       void_shard / rift_essence / myriad_fragment）。</li>
 * </ul>
 * 自然生成：万象森罗 biome monster 池低权重（正常怪的 1/20 左右），
 * 让玩家"偶遇"而非刷屏。
 */
public class QianxiangMyriadWarden extends Zombie {

    /** 冲击波触发半径（格）平方；也是"近身/远程"的分界线。 */
    private static final double SHOCKWAVE_RANGE_SQ = 25.0;
    /** 冲击波伤害（声爆伤害源，无视护甲，逼走位而非堆甲）。 */
    private static final float SHOCKWAVE_DAMAGE = 8.0f;
    /** 冲击波冷却（tick）：常态 8 秒，狂化 5 秒。 */
    private static final int SHOCKWAVE_COOLDOWN = 160, SHOCKWAVE_COOLDOWN_ENRAGED = 100;
    /** 裂隙弹冷却（tick）：常态 5 秒，狂化 3 秒。 */
    private static final int BOLT_COOLDOWN = 100, BOLT_COOLDOWN_ENRAGED = 60;
    /** 狂化召唤的裂隙蠹数量。 */
    private static final int ENRAGE_MINIONS = 3;

    private static final ResourceLocation ENRAGE_SPEED_ID =
            ResourceLocation.fromNamespaceAndPath("qianxiang", "warden_enrage_speed");

    private final ServerBossEvent bossEvent = (ServerBossEvent) new ServerBossEvent(
            getDisplayName(), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_6)
            .setDarkenScreen(false);

    private boolean enraged;
    private int shockwaveCooldown = SHOCKWAVE_COOLDOWN;
    private int boltCooldown = BOLT_COOLDOWN;

    public QianxiangMyriadWarden(EntityType<? extends QianxiangMyriadWarden> type, Level level) {
        super(type, level);
        this.xpReward = 50;
    }

    /** Boss 属性：以 Monster 基线拔高，速度略慢于玩家疾跑（能走位风筝）。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 120.0)
                .add(Attributes.ATTACK_DAMAGE, 12.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.8)
                .add(Attributes.FOLLOW_RANGE, 40.0)
                .add(Attributes.ARMOR, 8.0)
                .add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0);
    }

    // ---- 不是亡灵：不怕阳光、不水转、不叫增援 ----

    /** Boss 永远是成年体（屏蔽 Zombie 5% 幼年概率）。 */
    @Override
    public boolean isBaby() {
        return false;
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    protected boolean convertsInWater() {
        return false;
    }

    // ---- Boss 血条 + 技能循环 ----

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) {
            return;
        }
        bossEvent.setProgress(getHealth() / getMaxHealth());

        if (shockwaveCooldown > 0) shockwaveCooldown--;
        if (boltCooldown > 0) boltCooldown--;

        if (!enraged && getHealth() < getMaxHealth() * 0.5f) {
            enrage();
        }

        LivingEntity target = getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        double distSq = distanceToSqr(target);
        if (distSq <= SHOCKWAVE_RANGE_SQ) {
            if (shockwaveCooldown <= 0) {
                riftShockwave();
                shockwaveCooldown = enraged ? SHOCKWAVE_COOLDOWN_ENRAGED : SHOCKWAVE_COOLDOWN;
            }
        } else if (boltCooldown <= 0 && hasLineOfSight(target)) {
            riftBolt(target);
            boltCooldown = enraged ? BOLT_COOLDOWN_ENRAGED : BOLT_COOLDOWN;
        }
    }

    /** 裂隙冲击波：以自身为心 5 格声爆 AOE，无视护甲并强击退——反贴脸站桩。 */
    private void riftShockwave() {
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        server.playSound(null, blockPosition(), SoundEvents.WARDEN_SONIC_BOOM,
                SoundSource.HOSTILE, 2.0f, 1.0f);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2 * i / 8;
            server.sendParticles(ParticleTypes.SONIC_BOOM,
                    getX() + Math.cos(angle) * 2.0, getY() + 1.0, getZ() + Math.sin(angle) * 2.0,
                    1, 0, 0, 0, 0);
        }
        for (LivingEntity victim : server.getEntitiesOfClass(LivingEntity.class,
                getBoundingBox().inflate(5.0),
                e -> e.isAlive() && e != this && !(e instanceof QianxiangMyriadWarden)
                        && !(e instanceof Endermite)
                        // 观战/创造模式玩家 hurt() 是空操作，但击退不是——
                        // 不排除的话旁观者会被冲击波推着走
                        && !(e instanceof net.minecraft.world.entity.player.Player p
                                && (p.isSpectator() || p.isCreative())))) {
            victim.hurt(damageSources().sonicBoom(this), SHOCKWAVE_DAMAGE);
            double dx = victim.getX() - getX();
            double dz = victim.getZ() - getZ();
            victim.knockback(1.5, -dx, -dz);
        }
    }

    /** 裂隙追踪弹：潜影贝弹自带追踪与命中悬浮——"被裂隙托起"。 */
    private void riftBolt(LivingEntity target) {
        level().addFreshEntity(new ShulkerBullet(level(), this, target, Direction.Axis.Y));
        level().playSound(null, blockPosition(), SoundEvents.SHULKER_SHOOT,
                SoundSource.HOSTILE, 1.5f, 0.8f);
    }

    /** 半血狂化（一次性）：血条转红、移速 +30%、召唤裂隙蠹、怒吼。 */
    private void enrage() {
        enraged = true;
        applyEnrageSpeed();
        bossEvent.setColor(BossEvent.BossBarColor.RED);
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        server.playSound(null, blockPosition(), SoundEvents.WARDEN_ROAR,
                SoundSource.HOSTILE, 2.5f, 0.9f);
        server.sendParticles(ParticleTypes.PORTAL, getX(), getY() + 1.5, getZ(),
                80, 1.5, 1.0, 1.5, 0.5);
        for (int i = 0; i < ENRAGE_MINIONS; i++) {
            Endermite mite = EntityType.ENDERMITE.create(server);
            if (mite == null) {
                continue;
            }
            double angle = Math.PI * 2 * i / ENRAGE_MINIONS;
            mite.moveTo(getX() + Math.cos(angle) * 1.5, getY(), getZ() + Math.sin(angle) * 1.5,
                    random.nextFloat() * 360f, 0);
            mite.finalizeSpawn(server, server.getCurrentDifficultyAt(blockPosition()),
                    MobSpawnType.MOB_SUMMONED, null);
            if (getTarget() != null) {
                mite.setTarget(getTarget());
            }
            server.addFreshEntity(mite);
        }
    }

    /** 狂化移速：transient modifier，读档恢复时重挂（见 {@link #readAdditionalSaveData}）。 */
    private void applyEnrageSpeed() {
        AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(ENRAGE_SPEED_ID) == null) {
            speed.addTransientModifier(new AttributeModifier(ENRAGE_SPEED_ID, 0.3,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    // ---- 持久化 ----

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("QxEnraged", enraged);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.getBoolean("QxEnraged")) {
            enraged = true;
            applyEnrageSpeed();
            bossEvent.setColor(BossEvent.BossBarColor.RED);
        }
    }

    /** 战斗中绝不脱战消失；无目标时照常随距离清除（低权重刷新，避免维度攒满 Boss）。 */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return getTarget() == null && super.removeWhenFarAway(distanceToClosestPlayer);
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossEvent.removePlayer(player);
    }

    @Override
    public void setCustomName(net.minecraft.network.chat.Component name) {
        super.setCustomName(name);
        bossEvent.setName(getDisplayName());
    }

    // ---- Warden 系音效：摆脱僵尸嗓 ----

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.WARDEN_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.WARDEN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.WARDEN_DEATH;
    }

    @Override
    protected SoundEvent getStepSound() {
        return SoundEvents.WARDEN_STEP;
    }

    /** 守望者不捡装备（保持掉落表纯净，也避免捡走玩家掉落）。 */
    @Override
    protected void populateDefaultEquipmentSlots(net.minecraft.util.RandomSource random,
                                                 net.minecraft.world.DifficultyInstance difficulty) {
        // 无装备
    }

    @Override
    public boolean canPickUpLoot() {
        return false;
    }

    /** 免疫悬浮与中毒——虚空裂片/剧毒流派对它无效，逼玩家换思路。 */
    @Override
    public boolean canBeAffected(net.minecraft.world.effect.MobEffectInstance effect) {
        if (effect.is(net.minecraft.world.effect.MobEffects.LEVITATION)
                || effect.is(net.minecraft.world.effect.MobEffects.POISON)) {
            return false;
        }
        return super.canBeAffected(effect);
    }
}
