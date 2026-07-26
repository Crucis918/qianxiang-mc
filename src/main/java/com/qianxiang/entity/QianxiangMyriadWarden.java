package com.qianxiang.entity;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * 森罗守望者 —— 万象森罗的漫游 Boss。
 * <p>
 * 万象生机凝聚出的守望之躯，游荡在维度各处看护相之源头。
 * 继承 {@link Zombie} 复用完整近战 AI/寻路/音效，在其上：
 * <ul>
 *   <li>高属性：120 HP / 12 攻击 / 高击退抗性（见 {@link #createAttributes()}）。</li>
 *   <li>紫色 Boss 血条（{@link ServerBossEvent}，进入视野的玩家可见）。</li>
 *   <li>不怕阳光、不转化溺尸——它不是亡灵，是维度的免疫应答。</li>
 *   <li>掉落传奇材料（见 {@code loot_table/entities/myriad_warden.json}：
 *       void_shard / rift_essence / myriad_fragment）。</li>
 * </ul>
 * 自然生成：万象森罗 biome monster 池低权重（正常怪的 1/20 左右），
 * 让玩家"偶遇"而非刷屏。
 */
public class QianxiangMyriadWarden extends Zombie {

    private final ServerBossEvent bossEvent = (ServerBossEvent) new ServerBossEvent(
            getDisplayName(), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_6)
            .setDarkenScreen(false);

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

    // ---- Boss 血条 ----

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide) {
            bossEvent.setProgress(getHealth() / getMaxHealth());
        }
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
