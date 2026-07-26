package com.qianxiang.util;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按「玩家 × 动作」的轻量限流器 —— 服务端上行包与命令的统一节流点。
 * <p>
 * 存在的理由：本模组有多条「一个上行包 → 一次昂贵服务端操作」的链路
 * （AI HTTP 请求、全物品注册表扫描、整库序列化回包、actionbar 回包），
 * 改造过的客户端可以用最小成本让服务端替它做重活。这里给每条链路一个闸门。
 * <p>
 * 时间基准用 {@link System#nanoTime()} 而非游戏 tick：命令与网络包可能在
 * 服务端尚未开始 tick 时到达，且不受 {@code /tick freeze} 影响。
 * <p>
 * 线程模型：{@link ConcurrentHashMap} + 原子的 {@code merge}，
 * 允许网络线程与主线程并发查询（虽然当前调用点都在主线程）。
 * 玩家下线不主动清理——条目只有 (UUID, action) 两个键，
 * 长期运行的服务器上占用可忽略；{@link #clearAll()} 供关服/测试重置。
 */
public final class PlayerRateLimiter {

    /** key = playerUuid + '|' + action，value = 允许下一次通过的 nanoTime。 */
    private static final Map<String, Long> NEXT_ALLOWED = new ConcurrentHashMap<>();

    private PlayerRateLimiter() {}

    /**
     * 尝试为该玩家的该动作放行一次。
     *
     * @param player       发起者（null 视为放行，例如命令方块/控制台）
     * @param action       动作标识（如 {@code "ai_request"}）
     * @param cooldownMs   两次放行之间的最小间隔（毫秒）
     * @return true = 放行（并已记下下次可用时刻）；false = 仍在冷却，调用方应静默丢弃
     */
    public static boolean tryAcquire(Player player, String action, long cooldownMs) {
        if (player == null) {
            return true;
        }
        return tryAcquire(player.getUUID(), action, cooldownMs);
    }

    /** 同 {@link #tryAcquire(Player, String, long)}，直接给 UUID。 */
    public static boolean tryAcquire(UUID playerId, String action, long cooldownMs) {
        if (playerId == null || cooldownMs <= 0) {
            return true;
        }
        long now = System.nanoTime();
        long cooldownNanos = cooldownMs * 1_000_000L;
        String key = playerId + "|" + action;
        // compute 对该 key 是原子的：并发调用里只有一个能把「下次可用时刻」推到未来
        boolean[] allowed = {false};
        NEXT_ALLOWED.compute(key, (k, nextAllowed) -> {
            // 减法比较而非绝对值比较，nanoTime 回绕时仍正确
            if (nextAllowed != null && nextAllowed - now > 0) {
                return nextAllowed;   // 仍在冷却，保持原时刻
            }
            allowed[0] = true;
            return now + cooldownNanos;
        });
        return allowed[0];
    }

    /** 清空全部限流状态（关服/测试用）。 */
    public static void clearAll() {
        NEXT_ALLOWED.clear();
    }

    /** 当前记录的条目数（诊断用）。 */
    public static int trackedEntries() {
        return NEXT_ALLOWED.size();
    }
}
