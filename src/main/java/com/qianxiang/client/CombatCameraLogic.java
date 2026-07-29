package com.qianxiang.client;

import java.util.Optional;

/**
 * 战斗镜头状态机（<b>纯逻辑</b>：零 Minecraft 依赖，GameTest 直接驱动）。
 * <p>
 * 规则（需求：全职高手·荣耀风——PVP 第一人称 / 平时与 PVE 第三人称）：
 * <ul>
 *   <li>近 {@link #PVP_WINDOW_TICKS} tick（6 秒）内与<b>玩家</b>互相伤害/攻击 → PVP 态，
 *       目标镜头 = 第一人称；窗口内再有 PVP 事件只刷新时间，不重复发切换信号。</li>
 *   <li>PVP 态消失（最后 PVP 事件超过 6 秒）后再过 {@link #EXIT_DELAY_TICKS} tick（4 秒）
 *       → 退出 PVP，目标镜头 = 第三人称。</li>
 *   <li>PVE（与怪物战斗）事件：PVP 窗口内被忽略（PVP 优先）；窗口外若当前挂着 PVP 镜头
 *       则立刻退回第三人称，否则<b>不发任何信号</b>——玩家手动切的第一人称不被干涉。
 *       本类只在「状态切换瞬间」输出一次目标镜头，绝不持续输出，配合 handler 的
 *       「只在切换瞬间写镜头」实现不霸占。</li>
 * </ul>
 * <p>
 * 调用约定：战斗事件走 {@link #onCombatEvent}，每 tick 走 {@link #onTick}；
 * 两者返回 {@link Optional#empty()} 表示无状态切换（调用方什么都不做），
 * 非空表示此刻应把镜头切到返回值。<b>必须单线程调用</b>（handler 已把所有来源
 * 归并到客户端主线程）。
 */
public final class CombatCameraLogic {

    /** 目标镜头。 */
    public enum CameraGoal {
        FIRST_PERSON,
        THIRD_PERSON
    }

    /** 战斗事件类型：PVP = 与玩家互相伤害/攻击；PVE = 与怪物（非玩家生物）战斗。 */
    public enum CombatEventType {
        PVP,
        PVE
    }

    /** PVP 判定窗口：最后一次 PVP 事件后 6 秒（120 tick）内仍算 PVP 态。 */
    public static final long PVP_WINDOW_TICKS = 120;
    /** PVP 态消失后回第三人称的延迟：4 秒（80 tick）。 */
    public static final long EXIT_DELAY_TICKS = 80;

    /**
     * 最后一次 PVP 事件的游戏时刻。初始值取极小值，使「无记录」天然落在窗口外
     * （差值极大且不会溢出：游戏时刻为非负小量级）。
     */
    private long lastPvpTick = Long.MIN_VALUE / 2;

    /** 我们是否已发出过「切第一人称」信号且尚未退出。true 期间重复 PVP 事件不再发信号。 */
    private boolean pvpActive;

    /**
     * 输入一个战斗事件。返回非空 = 状态切换瞬间的目标镜头。
     *
     * @param type     PVP / PVE
     * @param gameTime 当前游戏时刻（level.getGameTime()）
     */
    public Optional<CameraGoal> onCombatEvent(CombatEventType type, long gameTime) {
        if (type == CombatEventType.PVP) {
            lastPvpTick = gameTime;
            if (!pvpActive) {
                pvpActive = true;
                return Optional.of(CameraGoal.FIRST_PERSON);
            }
            return Optional.empty();
        }
        // PVE：PVP 窗口内不抢镜头（PVP 优先）；窗口外把挂着的 PVP 镜头立刻退掉。
        if (gameTime - lastPvpTick <= PVP_WINDOW_TICKS) {
            return Optional.empty();
        }
        if (pvpActive) {
            pvpActive = false;
            return Optional.of(CameraGoal.THIRD_PERSON);
        }
        return Optional.empty();
    }

    /**
     * 每 tick 推进：PVP 态消失满 {@link #EXIT_DELAY_TICKS} 后退出，回第三人称。
     * 只在退出瞬间返回一次 {@link CameraGoal#THIRD_PERSON}。
     */
    public Optional<CameraGoal> onTick(long gameTime) {
        if (pvpActive && gameTime - lastPvpTick > PVP_WINDOW_TICKS + EXIT_DELAY_TICKS) {
            pvpActive = false;
            return Optional.of(CameraGoal.THIRD_PERSON);
        }
        return Optional.empty();
    }

    /** 当前是否处于 PVP 镜头态（已发第一人称信号且未退出）。仅观测用。 */
    public boolean isPvpActive() {
        return pvpActive;
    }

    /** 换世界/断线时复位：新世界 entity 与时刻都不可比，镜头交还玩家手动控制。 */
    public void reset() {
        lastPvpTick = Long.MIN_VALUE / 2;
        pvpActive = false;
    }
}
