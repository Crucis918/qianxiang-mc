package com.qianxiang.cap;

import com.qianxiang.Qianxiang;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.IronGolem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职业伙伴与拳法家攻速的 tick 驱动（召唤师=狼 / 机械师=铁傀儡 / 拳法家蓄力加速）。
 * <p>
 * 伙伴规则：玩家职业匹配且伙伴死亡/不存在超过 {@link ClassCoreHelper#COMPANION_RESPAWN_TICKS}
 * tick（60s）后在玩家身旁重新召唤——召唤师=驯服的狼（setOwner），机械师=铁傀儡
 * （原版 AI 天然护主），命名带职业色。状态纯内存（伙伴是消耗品，不值得落盘）；
 * 换职业后旧伙伴不回收（已是你的狼），只停发新的。
 * </p>
 * <p>
 * 拳法家攻速：蓄力槽（attackStrengthTicker）每 20t 额外 +2t = 恰好 +10% 充能速率，
 * 效果等同攻速 ×1.10，不动属性/物品管线。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class CompanionHandler {

    private CompanionHandler() {}

    /** 检查间隔（tick）：伙伴巡检与蓄力加速共用。 */
    private static final long CHECK_INTERVAL = 20L;

    private record CompanionState(UUID entityUuid, long respawnAtTick) {}

    private static final Map<UUID, CompanionState> COMPANIONS = new ConcurrentHashMap<>();

    /** 蓄力槽字段（拳法家加速用，反射一次缓存）。 */
    private static java.lang.reflect.Field TICKER_FIELD;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        long gameTime = server.overworld().getGameTime();
        if (gameTime % CHECK_INTERVAL != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                tickCompanion(player, gameTime);
                tickPugilist(player);
            } catch (Throwable t) {
                Qianxiang.LOGGER.debug("[Qianxiang] 伙伴/攻速 tick 失败（不影响主流程）：{}", t.toString());
            }
        }
    }

    /** 伙伴：死亡/缺席 60s 后在玩家身旁补一只。 */
    private static void tickCompanion(ServerPlayer player, long gameTime) {        String kind = ClassCoreHelper.companionOf(player);
        if (kind.isEmpty()) return;
        ServerLevel level = player.serverLevel();
        CompanionState state = COMPANIONS.get(player.getUUID());

        if (state != null && level.getEntity(state.entityUuid()) instanceof LivingEntity alive
                && alive.isAlive()) {
            return; // 伙伴健在
        }
        if (state == null) {
            // 首次（或换世界后）：直接补一只
            COMPANIONS.put(player.getUUID(), new CompanionState(spawnCompanion(player, kind), 0));
            return;
        }
        if (state.respawnAtTick() == 0) {
            // 刚发现伙伴没了：开始 60s 倒计时
            COMPANIONS.put(player.getUUID(),
                    new CompanionState(state.entityUuid(), gameTime + ClassCoreHelper.COMPANION_RESPAWN_TICKS));
            return;
        }
        if (gameTime >= state.respawnAtTick()) {
            COMPANIONS.put(player.getUUID(), new CompanionState(spawnCompanion(player, kind), 0));
        }
    }

    /** 测试入口：手动跑一轮伙伴巡检（GameTest 的 mock 玩家不在 playerList 里）。 */
    public static void tickCompanionForTest(ServerPlayer player) {
        tickCompanion(player, player.level().getGameTime());
    }

    private static UUID spawnCompanion(ServerPlayer player, String kind) {
        ServerLevel level = player.serverLevel();
        LivingEntity companion;
        if ("wolf".equals(kind)) {
            Wolf wolf = new Wolf(EntityType.WOLF, level);
            wolf.setTame(true, true);
            wolf.setOwnerUUID(player.getUUID());
            wolf.setCustomName(Component.literal("§6兽契者之狼"));
            companion = wolf;
        } else {
            IronGolem golem = new IronGolem(EntityType.IRON_GOLEM, level);
            golem.setCustomName(Component.literal("§b械师的造物"));
            companion = golem;
        }
        companion.setPos(player.getX() + 1.5, player.getY(), player.getZ() + 1.5);
        level.addFreshEntity(companion);
        return companion.getUUID();
    }

    /** 拳法家：蓄力槽每 20t 额外 +2t（= +10% 充能速率，即攻速 ×1.10）。 */
    private static void tickPugilist(ServerPlayer player) throws Exception {
        if (!ClassCoreHelper.isPugilist(player)) return;
        if (TICKER_FIELD == null) {
            TICKER_FIELD = LivingEntity.class.getDeclaredField("attackStrengthTicker");
            TICKER_FIELD.setAccessible(true);
        }
        TICKER_FIELD.setInt(player, TICKER_FIELD.getInt(player) + 2);
    }
}
