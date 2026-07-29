package com.qianxiang.handler;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangBlocks;
import com.qianxiang.QianxiangDimensions;
import com.qianxiang.QianxiangMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 裂隙试炼：可重复挑战 + 词缀怪 + 词缀材料（借鉴 DawnCraft 稀有度怪梯队）。
 * <p>
 * <b>触发</b>：在万象森罗维度，<b>手持非裂隙精髓的物品</b>右键裂隙岩
 * （空手/持精髓的回程传送归 {@link MyriadWildsPortalHandler} 管，互不抢占）。
 * 创造/旁观不触发；冷却 {@value COOLDOWN_TICKS} tick（60s）防刷。
 * <p>
 * <b>波次</b>：在玩家周围 6~10 格环形刷一波原版僵尸/骷髅（不新增实体类型），
 * 数量 = 3 + min(wins, 7)。每只怪按概率带一个 {@link RiftAffix} 词缀
 * （比例随 wins 微升，首只保底带词缀）：自定义名带「·词缀·」前缀（lang 键），
 * HP = 原版 × (1.5 + 0.1×min(wins, 5))，外加词缀对应的属性/效果。
 * <p>
 * <b>词缀效果</b>（{@link #onLivingDamagePost}，攻击者带词缀 tag 即生效，
 * 骷髅的箭也算命中）：燃焰=点燃目标 4s；噬血=伤害 30% 回血；
 * 雷霆=附加 2 点魔法真伤（魔法源无攻击者实体，天然不递归）。
 * <p>
 * <b>掉落</b>：词缀怪 50% 掉 1 个词缀材料（{@link RiftAffix#materialStack()}），
 * 材料带 {@code AFFIX} 组件——凭它在 PhaseFunctionResolver 注入算子，直接进锻造台当零件。
 * <p>
 * <b>结算</b>：全灭 → wins+1（存玩家 PersistentData {@code qianxiang:trial_wins}，
 * 零新 attachment 基建）、actionbar 祝贺、保底掉 1~2 个词缀材料。
 * 玩家死亡 / 离开试炼维度超过 {@value LEAVE_GRACE_TICKS} tick（60s）/ 登出 →
 * 判失败，剩余试炼怪（带 {@value TAG_TRIAL_MOB} tag）全部 despawn。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class RiftTrialHandler {

    /** 试炼怪标记 tag——失败/登出时按它清场。 */
    public static final String TAG_TRIAL_MOB = "qianxiang_trial_mob";
    /** 词缀 tag 前缀：完整 tag = {@value TAG_AFFIX_PREFIX} + 词缀 id。 */
    public static final String TAG_AFFIX_PREFIX = "qianxiang_affix_";
    /** 完成次数的 PersistentData 键。 */
    public static final String WINS_KEY = "qianxiang:trial_wins";

    private static final long COOLDOWN_TICKS = 20L * 60;
    private static final long LEAVE_GRACE_TICKS = 20L * 60;
    /** 词缀怪掉落词缀材料的概率。 */
    private static final float MATERIAL_DROP_CHANCE = 0.5f;

    /** 进行中的试炼：玩家 → 试炼状态。会话级内存状态（结算/失败/登出即清）。 */
    private static final Map<UUID, TrialState> ACTIVE = new HashMap<>();
    /** 冷却：玩家 → 冷却截止 gameTime。 */
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();

    private RiftTrialHandler() {}

    /** 一场试炼的运行时状态。直接持玩家引用——mock player 不在 PlayerList 里，按 UUID 反查会落空。 */
    private record TrialState(ServerPlayer player, ResourceKey<Level> dimension,
                              List<UUID> mobs, List<RiftAffix> waveAffixes, long leftDimensionAt) {}

    // ============================ 触发 ============================

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getLevel().getBlockState(event.getPos()).is(QianxiangBlocks.RIFT_STONE.get())) return;
        if (player.serverLevel().dimension() != QianxiangDimensions.MYRIAD_WILDS) return;
        if (player.isCreative() || player.isSpectator()) return;
        // 空手/持裂隙精髓 = 回程传送（MyriadWildsPortalHandler 的既有语义），试炼不抢占
        if (event.getItemStack().isEmpty() || event.getItemStack().is(QianxiangMaterials.RIFT_ESSENCE.get())) return;
        startTrial(player, event.getPos());
    }

    /**
     * 尝试开启一波试炼（含「已有进行中试炼」与冷却检查；<b>不</b>检查维度与手持物——
     * 那是事件入口的职责，拆开是为了 GameTest 能直接驱动）。
     *
     * @return true=已开波；false=被冷却/进行中试炼拒绝
     */
    public static boolean startTrial(ServerPlayer player, BlockPos anchor) {
        UUID id = player.getUUID();
        if (ACTIVE.containsKey(id)) return false;
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        long until = COOLDOWN_UNTIL.getOrDefault(id, Long.MIN_VALUE);
        if (now < until) {
            player.displayClientMessage(Component.translatable(
                    "qianxiang.trial.cooldown", (until - now) / 20 + 1), true);
            return false;
        }

        int wins = getWins(player);
        int count = 3 + Math.min(wins, 7);
        float hpMult = 1.5f + 0.1f * Math.min(wins, 5);
        double affixChance = Math.min(0.6 + 0.1 * wins, 1.0);
        RandomSource rand = level.getRandom();

        List<UUID> mobs = new ArrayList<>();
        List<RiftAffix> waveAffixes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 首只保底带词缀——每波至少一只词缀怪（也是测试的确定性锚点）
            boolean affixed = i == 0 || rand.nextDouble() < affixChance;
            Mob mob = spawnTrialMob(level, player.blockPosition(), rand, affixed, hpMult, waveAffixes);
            if (mob != null) mobs.add(mob.getUUID());
        }
        if (mobs.isEmpty()) return false;

        ACTIVE.put(id, new TrialState(player, level.dimension(), mobs, waveAffixes, -1L));
        COOLDOWN_UNTIL.put(id, now + COOLDOWN_TICKS);
        level.playSound(null, anchor, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 0.6f, 1.6f);
        player.displayClientMessage(Component.translatable("qianxiang.trial.start"), true);
        return true;
    }

    /** 在环形带刷一只试炼怪；词缀怪套用名称/HP/词缀增益。生成失败返回 null。 */
    private static Mob spawnTrialMob(ServerLevel level, BlockPos center, RandomSource rand,
                                     boolean affixed, float hpMult, List<RiftAffix> waveAffixes) {
        double angle = rand.nextDouble() * Math.PI * 2.0;
        int radius = 6 + rand.nextInt(5); // 6~10 格环形
        int x = center.getX() + Mth.floor(Math.cos(angle) * radius);
        int z = center.getZ() + Mth.floor(Math.sin(angle) * radius);
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,
                new BlockPos(x, center.getY(), z));

        Mob mob = (rand.nextBoolean() ? EntityType.ZOMBIE : EntityType.SKELETON).create(level);
        if (mob == null) return null;
        mob.moveTo(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5,
                rand.nextFloat() * 360.0f, 0.0f);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(surface), MobSpawnType.EVENT, null);
        mob.setPersistenceRequired();
        mob.addTag(TAG_TRIAL_MOB);
        level.addFreshEntity(mob);

        if (affixed) {
            RiftAffix affix = RiftAffix.values()[rand.nextInt(RiftAffix.values().length)];
            applyAffix(mob, affix, hpMult);
            waveAffixes.add(affix);
        }
        return mob;
    }

    /** 词缀怪的增益：「·词缀·」前缀名、HP 倍率、词缀属性/效果。 */
    private static void applyAffix(Mob mob, RiftAffix affix, float hpMult) {
        mob.addTag(TAG_AFFIX_PREFIX + affix.id());
        boolean zombie = mob.getType() == EntityType.ZOMBIE;
        mob.setCustomName(Component.translatable(
                zombie ? "qianxiang.trial.mob.zombie" : "qianxiang.trial.mob.skeleton",
                Component.translatable(affix.langKey())));

        var maxHealth = mob.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(maxHealth.getBaseValue() * hpMult);
            mob.setHealth(mob.getMaxHealth());
        }
        switch (affix) {
            case EMBER -> mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,
                    MobEffectInstance.INFINITE_DURATION, 0, true, false));
            case BASTION -> {
                var armor = mob.getAttribute(Attributes.ARMOR);
                if (armor != null) armor.setBaseValue(armor.getBaseValue() + 6.0);
                var kbRes = mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
                if (kbRes != null) kbRes.setBaseValue(kbRes.getBaseValue() + 0.8);
            }
            case GALE -> {
                var speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.4);
                var attackSpeed = mob.getAttribute(Attributes.ATTACK_SPEED);
                if (attackSpeed != null) attackSpeed.setBaseValue(attackSpeed.getBaseValue() * 1.4);
            }
            // 噬血/雷霆是命中触发效果，无常驻属性——见 onLivingDamagePost
            case LEECH, STORM -> {}
        }
    }

    // ============================ 词缀命中效果 ============================

    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) return;
        RiftAffix affix = affixOf(attacker);
        if (affix == null) return;
        LivingEntity target = event.getEntity();
        switch (affix) {
            case EMBER -> target.igniteForSeconds(4.0f);
            case LEECH -> attacker.heal(event.getNewDamage() * 0.3f);
            // 魔法源不带攻击者实体（getEntity()==null），本条不会自我递归
            case STORM -> target.hurt(target.damageSources().magic(), 2.0f);
            default -> {}
        }
    }

    // ============================ 词缀材料掉落 ============================

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        RiftAffix affix = affixOf(entity);
        if (affix == null) return;
        if (entity.getRandom().nextFloat() >= MATERIAL_DROP_CHANCE) return;
        event.getDrops().add(new ItemEntity(entity.level(),
                entity.getX(), entity.getY(), entity.getZ(), affix.materialStack()));
    }

    /** 实体词缀：按 tag 解析（tag = qianxiang_affix_&lt;id&gt;）。 */
    private static RiftAffix affixOf(LivingEntity entity) {
        if (!entity.getTags().contains(TAG_TRIAL_MOB)) return null;
        for (String tag : entity.getTags()) {
            if (tag.startsWith(TAG_AFFIX_PREFIX)) {
                return RiftAffix.byId(tag.substring(TAG_AFFIX_PREFIX.length()));
            }
        }
        return null;
    }

    // ============================ 结算 / 失败 ============================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickTrials(event.getServer());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            endTrial(player, false);
        }
    }

    /**
     * 推进所有进行中的试炼：全灭→胜；玩家死亡/离维超时/消失→败。
     * 从 {@link #onServerTick} 每 tick 调用；GameTest 可直接调用驱动结算。
     */
    public static void tickTrials(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        Iterator<Map.Entry<UUID, TrialState>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            TrialState state = it.next().getValue();
            ServerPlayer player = state.player();
            if (player == null || player.isRemoved() || player.isDeadOrDying()) {
                endTrial(state, false);
                it.remove();
                continue;
            }
            if (player.serverLevel().dimension() != state.dimension()) {
                long now = player.serverLevel().getGameTime();
                long leftAt = state.leftDimensionAt();
                if (leftAt < 0) {
                    state = new TrialState(player, state.dimension(), state.mobs(),
                            state.waveAffixes(), now);
                    ACTIVE.put(player.getUUID(), state);
                } else if (now - leftAt > LEAVE_GRACE_TICKS) {
                    endTrial(state, false);
                    it.remove();
                }
                continue;
            }
            boolean anyAlive = false;
            for (UUID mobId : state.mobs()) {
                Entity e = player.serverLevel().getEntity(mobId);
                if (e != null && e.isAlive()) {
                    anyAlive = true;
                    break;
                }
            }
            if (!anyAlive) {
                endTrial(state, true);
                it.remove();
            }
        }
    }

    /** 按玩家结算并清场（公开入口：登出/测试清理）。 */
    public static void endTrial(ServerPlayer player, boolean success) {
        TrialState state = ACTIVE.remove(player.getUUID());
        if (state != null) endTrial(state, success);
    }

    /** 结算：成功=wins+1+祝贺+保底材料；失败=清场+提示。两侧都 despawn 剩余试炼怪。 */
    private static void endTrial(TrialState state, boolean success) {
        ServerPlayer player = state.player();
        if (player == null || player.isRemoved()) return;
        ServerLevel level = player.server.getLevel(state.dimension());
        if (level != null) {
            for (UUID mobId : state.mobs()) {
                Entity e = level.getEntity(mobId);
                if (e != null) e.discard();
            }
        }
        if (success) {
            int wins = getWins(player) + 1;
            player.getPersistentData().putInt(WINS_KEY, wins);
            player.displayClientMessage(Component.translatable("qianxiang.trial.success", wins), true);
            // 保底回报：1~2 个词缀材料，优先从本波出现过的词缀里挑
            var rand = player.getRandom();
            int bonus = 1 + rand.nextInt(2);
            for (int i = 0; i < bonus; i++) {
                RiftAffix affix = state.waveAffixes().isEmpty()
                        ? RiftAffix.values()[rand.nextInt(RiftAffix.values().length)]
                        : state.waveAffixes().get(rand.nextInt(state.waveAffixes().size()));
                if (level != null) {
                    level.addFreshEntity(new ItemEntity(level,
                            player.getX(), player.getY(), player.getZ(), affix.materialStack()));
                }
            }
        } else {
            player.displayClientMessage(Component.translatable("qianxiang.trial.fail"), true);
        }
    }

    // ============================ 查询 / 测试入口 ============================

    /** 玩家累计完成次数（PersistentData，零新 attachment 基建）。 */
    public static int getWins(ServerPlayer player) {
        return player.getPersistentData().getInt(WINS_KEY);
    }

    /** 玩家是否有进行中的试炼。 */
    public static boolean hasActiveTrial(ServerPlayer player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    /** 进行中试炼的怪 UUID 列表（测试观测用；无试炼返回空表）。 */
    public static List<UUID> activeTrialMobs(ServerPlayer player) {
        TrialState state = ACTIVE.get(player.getUUID());
        return state == null ? List.of() : List.copyOf(state.mobs());
    }

    /** 测试清理：放弃试炼（清场）并抹掉冷却，避免跨用例串状态。 */
    public static void resetForTest(ServerPlayer player) {
        endTrial(player, false);
        COOLDOWN_UNTIL.remove(player.getUUID());
    }
}
