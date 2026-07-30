package com.qianxiang.cap;

import com.qianxiang.Qianxiang;
import com.qianxiang.spell.AmplifierHelper;
import com.qianxiang.spell.ComboTracker;
import com.qianxiang.spell.CustomSpell;
import com.qianxiang.spell.SpellCastHandler;
import com.qianxiang.spell.SpellEffectEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职业技能结算（{@link ClassSkill} 的 9 种机制 + 标准法术通道）。
 * <p>
 * 入口 {@link #activate}：校验职业/冷却/蓝耗 → 兑现 → 记冷却扣蓝。
 * {@code NONE} 机制与标准项直接装配 {@link CustomSpell} 走
 * {@link SpellEffectEngine#cast}（增幅器/熟练度/职业内核/招牌被动/连招乘区照吃——
 * 连招经 {@link ComboTracker#onCast} 自然参与）；机制项复用引擎与原版件实现：
 * DASH/BLINK=视线推进+安全落点、FLURRY=3 段预约近战（tick 驱动）、SHIELD=吸收心、
 * SUMMON_*=60s 临时伙伴、PULL=牵拉+减速、SMOKE=隐身+致盲、FAN3=扇形 3 弹、
 * EMPOWER=伙伴增伤加速（无伙伴时给自身力量，魔剑共鸣的近似，见注释）。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class ClassSkillMechanics {

    private ClassSkillMechanics() {}

    // ===================== 调平常量区 =====================

    /** DASH 突进距离（格）与路径伤害半径。 */
    public static final double DASH_DISTANCE = 4.0;
    public static final double DASH_HIT_RADIUS = 1.6;
    /** BLINK 闪现距离（格）。 */
    public static final double BLINK_DISTANCE = 6.0;
    /** FLURRY 段数与段间隔（tick）。 */
    public static final int FLURRY_STRIKES = 3;
    public static final int FLURRY_INTERVAL_TICKS = 4;
    /** SHIELD 吸收时长与等级（20s、16 点吸收=8 心）。 */
    public static final int SHIELD_TICKS = 400;
    public static final int SHIELD_AMPLIFIER = 3;
    /** 临时伙伴寿命（60s）。 */
    public static final long SUMMON_LIFETIME_TICKS = 1200;
    /** PULL 牵拉范围（前方格数）与减速时长。 */
    public static final double PULL_RANGE = 6.0;
    public static final int PULL_SLOW_TICKS = 60;
    /** SMOKE 自身隐身/周围致盲时长（3s）。 */
    public static final int SMOKE_TICKS = 60;
    /** FAN3 扇形张角（度）。 */
    public static final float FAN_ANGLE_DEG = 15.0f;
    /** EMPOWER 伙伴增伤/加速时长（30s）；无伙伴时自身力量时长（10s，魔剑共鸣近似：
     *  力量 I 约等于 +15% 低面板近战，真百分比乘区要做进 ProficiencyCombatHooks
     *  的时限窗口，复杂度高——先用原版力量，注释留档）。 */
    public static final int EMPOWER_PET_TICKS = 600;
    public static final int EMPOWER_SELF_TICKS = 200;
    /** 血怒斩自伤（参考血换蓝 self-cost）。 */
    public static final float BLOODSLASH_SELF_COST = 4.0f;

    /** 冷却表：玩家 UUID:技能 id → 上次使用 gameTime。 */
    private static final Map<String, Long> COOLDOWNS = new ConcurrentHashMap<>();
    /** FLURRY 预约：玩家 → 待打段（tick 驱动）。 */
    private static final Map<UUID, FlurryState> FLURRIES = new ConcurrentHashMap<>();
    /** 临时伙伴：实体 UUID → 到期 gameTime。 */
    private static final Map<UUID, Long> TEMP_SUMMONS = new ConcurrentHashMap<>();

    private record FlurryState(ClassSkill skill, int strikesLeft, long nextTick) {}

    // ===================== 入口 =====================

    /**
     * 发动职业第 slot 个技能（0=J / 1=潜行+J）。
     * 校验：有职业（模板匹配）→ 冷却未就绪拒、蓝不够拒（均不改变状态）；
     * 通过后兑现机制、记冷却、扣蓝、同步。
     *
     * @return true = 实际发动
     */
    public static boolean activate(ServerPlayer player, int slot) {
        String classId = ClassCoreHelper.signatureOf(player);
        if (classId.isEmpty()) return false;
        ClassSkill skill = ClassSkill.skillOf(classId, slot);
        if (skill == null) return false;

        long now = player.level().getGameTime();
        Long last = COOLDOWNS.get(cooldownKey(player, skill));
        if (last != null && now - last < skill.cooldownTicks()) return false;

        var data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        if (data.currentMana() < skill.manaCost()) return false;

        boolean ok;
        try {
            ok = execute(player, skill);
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 职业技能 {} 执行失败：{}", skill.id(), t.toString());
            return false;
        }
        if (!ok) return false;

        COOLDOWNS.put(cooldownKey(player, skill), now);
        player.setData(QianxiangAttachments.PLAYER_SPELL_DATA,
                data.withMana(data.currentMana() - skill.manaCost()));
        SpellCastHandler.sync(player);
        return true;
    }

    private static String cooldownKey(ServerPlayer player, ClassSkill skill) {
        return player.getUUID() + ":" + skill.id();
    }

    // ===================== 结算 =====================

    private static boolean execute(ServerPlayer player, ClassSkill skill) {
        return switch (skill.special()) {
            case NONE -> castStandard(player, skill);
            case DASH -> doDash(player, skill);
            case BLINK -> doBlink(player, skill);
            case FLURRY -> doFlurry(player, skill);
            case SHIELD -> doShield(player, skill);
            case SUMMON_WOLF -> doSummon(player, "wolf");
            case SUMMON_GOLEM -> doSummon(player, "iron_golem");
            case PULL -> doPull(player, skill);
            case SMOKE -> doSmoke(player, skill);
            case FAN3 -> doFan3(player, skill);
            case EMPOWER -> doEmpower(player, skill);
        };
    }

    /** 与 SpellCastHandler 同配方的伤害乘区（增幅器 × 熟练度 × 职业内核 × 招牌 × 连招）。 */
    private static float spellDamageMult(ServerPlayer player, CustomSpell spell, int combo) {
        return (float) (AmplifierHelper.damageMultiplier(player)
                * ProficiencyHelper.spellDamageMult(player, spell.power())
                * ClassCoreHelper.spellDamageMult(player, spell)
                * ClassCoreHelper.spellFormSignatureMult(player, spell)
                * ComboTracker.damageMultiplier(player, combo));
    }

    private static CustomSpell toSpell(ClassSkill skill) {
        return new CustomSpell(
                ResourceLocation.fromNamespaceAndPath("qianxiang", "classskill/" + skill.id()),
                skill.element(), skill.form(), skill.effect(), skill.modifiers(),
                skill.manaCost(), skill.cooldownTicks(), skill.power());
    }

    /** NONE：装配 CustomSpell 走引擎标准结算（连招自然参与）；血怒斩附加自伤。 */
    private static boolean castStandard(ServerPlayer player, ClassSkill skill) {
        CustomSpell spell = toSpell(skill);
        int combo = ComboTracker.onCast(player, spell.id());
        SpellEffectEngine.cast(spell, player, spellDamageMult(player, spell, combo));
        if ("avenger_bloodslash".equals(skill.id()) && player.getHealth() > BLOODSLASH_SELF_COST) {
            player.hurt(player.damageSources().magic(), BLOODSLASH_SELF_COST);
        }
        return true;
    }

    /** 单体近战式结算（DASH 路径/BLINK 落点/FLURRY 各段共用）：找前方目标按 touch 伤害。 */
    private static int hitInFront(ServerPlayer player, ClassSkill skill, Vec3 center, double radius) {
        CustomSpell spell = toSpell(skill);
        float mult = spellDamageMult(player, spell, 1);
        int hits = 0;
        for (LivingEntity target : player.serverLevel().getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius),
                e -> e.isAlive() && e != player)) {
            SpellEffectEngine.resolveHit(player.serverLevel(), player, player, target,
                    skill.element(), "damage", skill.power(), java.util.Set.copyOf(skill.modifiers()), mult);
            hits++;
        }
        return hits;
    }

    /** 视线推进安全落点：方向 dir、距离 dist，撞到方块则贴停。 */
    private static Vec3 dashTarget(ServerPlayer player, double dist) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(dist));
        var hit = player.level().clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return end;
        return hit.getLocation().subtract(look.scale(0.6));
    }

    private static boolean doDash(ServerPlayer player, ClassSkill skill) {
        Vec3 from = player.getEyePosition(1.0f);
        Vec3 to = dashTarget(player, DASH_DISTANCE);
        if (to.distanceToSqr(from) < 0.25) return false; // 被贴脸堵死（<0.5m），不算发动
        // moveTo 立即落服务端位置，teleport 包同步客户端（防止假连接/不回包时位置漂移）
        player.moveTo(to.x, to.y, to.z, player.getYRot(), player.getXRot());
        player.connection.teleport(to.x, to.y, to.z, player.getYRot(), player.getXRot());
        player.hasImpulse = true;
        // 路径伤害：起点到落点中扫一遍
        Vec3 dir = to.subtract(from);
        int hits = 0;
        for (double f = 0.0; f <= 1.0; f += 0.25) {
            hits += hitInFront(player, skill, from.add(dir.scale(f)), DASH_HIT_RADIUS);
        }
        return true;
    }

    private static boolean doBlink(ServerPlayer player, ClassSkill skill) {
        Vec3 from = player.getEyePosition(1.0f);
        Vec3 to = dashTarget(player, BLINK_DISTANCE);
        if (to.distanceToSqr(from) < 0.25) return false;
        player.moveTo(to.x, to.y, to.z, player.getYRot(), player.getXRot());
        player.connection.teleport(to.x, to.y, to.z, player.getYRot(), player.getXRot());
        player.hasImpulse = true;
        // 刺客影袭：落点补一刀
        hitInFront(player, skill, to, 2.5);
        return true;
    }

    private static boolean doFlurry(ServerPlayer player, ClassSkill skill) {
        // 首段立即，后两段预约（tick 驱动）
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 reach = eye.add(player.getLookAngle().scale(3.5));
        hitInFront(player, skill, reach, 1.6);
        FLURRIES.put(player.getUUID(),
                new FlurryState(skill, FLURRY_STRIKES - 1,
                        player.level().getGameTime() + FLURRY_INTERVAL_TICKS));
        return true;
    }

    private static boolean doShield(ServerPlayer player, ClassSkill skill) {
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, SHIELD_TICKS, SHIELD_AMPLIFIER));
        // 视觉统一：技能元素色 spark 由引擎 self buff 通道补一记
        return true;
    }

    private static boolean doSummon(ServerPlayer player, String kind) {
        ServerLevel level = player.serverLevel();
        LivingEntity companion;
        if ("wolf".equals(kind)) {
            Wolf wolf = new Wolf(EntityType.WOLF, level);
            wolf.setTame(true, true);
            wolf.setOwnerUUID(player.getUUID());
            wolf.setCustomName(net.minecraft.network.chat.Component.literal("§6召唤援军"));
            companion = wolf;
        } else {
            IronGolem golem = new IronGolem(EntityType.IRON_GOLEM, level);
            golem.setCustomName(net.minecraft.network.chat.Component.literal("§b召唤炮塔"));
            companion = golem;
        }
        companion.setPos(player.getX() + 1.5, player.getY(), player.getZ() + 1.5);
        level.addFreshEntity(companion);
        TEMP_SUMMONS.put(companion.getUUID(),
                player.level().getGameTime() + SUMMON_LIFETIME_TICKS);
        return true;
    }

    private static boolean doPull(ServerPlayer player, ClassSkill skill) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 front = eye.add(player.getLookAngle().scale(PULL_RANGE));
        int pulled = 0;
        for (LivingEntity target : player.serverLevel().getEntitiesOfClass(LivingEntity.class,
                new AABB(eye, front).inflate(1.5),
                e -> e.isAlive() && e != player)) {
            Vec3 pull = player.position().subtract(target.position()).normalize().scale(1.2);
            target.setDeltaMovement(pull.x, Math.max(0.2, pull.y), pull.z);
            target.hasImpulse = true;
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, PULL_SLOW_TICKS, 1));
            pulled++;
        }
        return pulled > 0;
    }

    private static boolean doSmoke(ServerPlayer player, ClassSkill skill) {
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, SMOKE_TICKS, 0));
        for (LivingEntity target : player.serverLevel().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(5.0),
                e -> e.isAlive() && e != player)) {
            target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, SMOKE_TICKS, 0));
        }
        return true;
    }

    private static boolean doFan3(ServerPlayer player, ClassSkill skill) {
        CustomSpell spell = toSpell(skill);
        float mult = spellDamageMult(player, spell, 1);
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        for (float offset : new float[]{-FAN_ANGLE_DEG, 0.0f, FAN_ANGLE_DEG}) {
            var bolt = new com.qianxiang.entity.SpellProjectileEntity(
                    com.qianxiang.entity.QianxiangEntities.SPELL_PROJECTILE.get(), player.serverLevel());
            bolt.setOwner(player);
            bolt.setPos(eye.x + look.x * 0.5, eye.y - 0.1, eye.z + look.z * 0.5);
            bolt.configure(spell.element(), spell.effect(), spell.power(),
                    java.util.Set.copyOf(spell.modifiers()), mult);
            bolt.shootFromRotation(player, player.getXRot(), player.getYRot() + offset, 0.0F,
                    1.6F * ClassCoreHelper.projectileSpeedMult(player), 0.0F);
            player.serverLevel().addFreshEntity(bolt);
        }
        return true;
    }

    private static boolean doEmpower(ServerPlayer player, ClassSkill skill) {
        // 伙伴（拥有的狼/铁傀儡，含职业伙伴与临时召唤）30s 增伤+加速
        List<LivingEntity> pets = new ArrayList<>();
        pets.addAll(player.serverLevel().getEntitiesOfClass(Wolf.class,
                player.getBoundingBox().inflate(10.0),
                w -> player.getUUID().equals(w.getOwnerUUID())));
        pets.addAll(player.serverLevel().getEntitiesOfClass(IronGolem.class,
                player.getBoundingBox().inflate(10.0), g -> true));
        if (pets.isEmpty()) {
            // 魔剑共鸣近似：无伙伴时给自身力量（≈ +15% 低面板近战，常量注释见类顶）
            player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, EMPOWER_SELF_TICKS, 0));
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, EMPOWER_SELF_TICKS, 0));
        } else {
            for (LivingEntity pet : pets) {
                pet.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, EMPOWER_PET_TICKS, 0));
                pet.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, EMPOWER_PET_TICKS, 0));
            }
        }
        return true;
    }

    // ===================== tick：FLURRY 预约段 + 临时伙伴到期 =====================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (FLURRIES.isEmpty() && TEMP_SUMMONS.isEmpty()) return;
        long now = event.getServer().overworld().getGameTime();

        FLURRIES.values().removeIf(state -> {
            if (now < state.nextTick()) return false;
            // 找 owner 玩家补一段（玩家下线则丢弃整段预约）
            var player = event.getServer().getPlayerList().getPlayers().stream()
                    .filter(p -> FLURRIES.containsKey(p.getUUID())
                            && FLURRIES.get(p.getUUID()) == state)
                    .findFirst().orElse(null);
            if (player != null) {
                Vec3 eye = player.getEyePosition(1.0f);
                Vec3 reach = eye.add(player.getLookAngle().scale(3.5));
                hitInFront(player, state.skill(), reach, 1.6);
            }
            int left = state.strikesLeft() - 1;
            if (left > 0 && player != null) {
                FLURRIES.put(player.getUUID(),
                        new FlurryState(state.skill(), left, now + FLURRY_INTERVAL_TICKS));
            }
            return true;
        });

        TEMP_SUMMONS.entrySet().removeIf(entry -> {
            if (now < entry.getValue()) return false;
            for (ServerLevel level : event.getServer().getAllLevels()) {
                if (level.getEntity(entry.getKey()) instanceof LivingEntity alive) {
                    alive.discard(); // 60s 临时伙伴到期消散（不掉落、不占刷怪位）
                }
            }
            return true;
        });
    }
}
