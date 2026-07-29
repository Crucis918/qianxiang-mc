package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import com.qianxiang.client.CombatCameraLogic.CameraGoal;
import com.qianxiang.client.CombatCameraLogic.CombatEventType;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.Optional;

/**
 * 战斗镜头 handler（全职高手·荣耀风）：<b>PVP 自动第一人称，平时与 PVE 第三人称</b>。
 * <p>
 * 状态机全部在 {@link CombatCameraLogic}（纯逻辑，GameTest 覆盖）；本类只负责
 * 「客户端事件 → 逻辑输入」和「逻辑输出 → 写镜头/actionbar」两段翻译。
 *
 * <h3>事件来源</h3>
 * <ul>
 *   <li>{@link LivingIncomingDamageEvent}：本地玩家被打（攻击者是玩家 → PVP，是怪物 → PVE），
 *       本地玩家打人/打怪（同理分类）。伤害结算在逻辑服务端，单机=整合服务端与本类同一
 *       事件总线，事件可能来自服务端线程，故一律 {@code mc.execute} 归并到客户端主线程
 *       再喂状态机（逻辑类约定单线程）。专用服务器下联机时客户端收不到该事件，
 *       此时仅靠下面的攻击输入检测——已在本类注释声明，属已知边界。</li>
 *   <li>{@link InputEvent.InteractionKeyMappingTriggered}（主手攻击动作）：准星指着
 *       玩家 → PVP，指着非玩家生物 → PVE。客户端线程触发，直接喂。</li>
 * </ul>
 *
 * <h3>写镜头纪律</h3>
 * 只在状态机输出切换信号的瞬间写一次 {@link net.minecraft.client.Options#setCameraType}，
 * 且不持续霸占：
 * <ul>
 *   <li>切第一人称：当前已是第一人称（玩家自己切的）就不动也不发提示；</li>
 *   <li>回第三人称：当前已不是第一人称（玩家中途手动切走了）就尊重玩家、不再写；</li>
 *   <li>切换之间玩家手动按 F5 完全自由。</li>
 * </ul>
 * 进出 PVP 各发一条 actionbar（lang 键见 docs/lang-pending-camera.json，缺键期用
 * {@code translatableWithFallback} 中文兜底）。
 *
 * <p>开关与过渡延迟读 {@link CombatCameraConfig}（{@code config/qianxiang-camera.json}）；
 * 进世界时重读一次配置并复位状态机。</p>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class CombatCameraHandler {

    /** actionbar lang 键（缺键期有中文兜底）。 */
    public static final String KEY_PVP_ENTER = "qianxiang.camera.pvp_enter";
    public static final String KEY_PVP_EXIT = "qianxiang.camera.pvp_exit";
    private static final String FALLBACK_PVP_ENTER = "PVP 交锋！已切换第一人称";
    private static final String FALLBACK_PVP_EXIT = "脱离 PVP，已回到第三人称";

    /** 单例状态机。只在客户端主线程访问（伤害事件已 mc.execute 归并）。 */
    private static final CombatCameraLogic LOGIC = new CombatCameraLogic();

    /** 过渡延迟：≥0 表示有一次待生效的镜头切换，数值为剩余 tick。 */
    private static int pendingTicks = -1;
    private static CameraGoal pendingGoal = CameraGoal.THIRD_PERSON;

    private CombatCameraHandler() {}

    // ============================ 事件 → 逻辑输入 ============================

    /** 伤害事件：按「受害者/攻击者是否为玩家」分类 PVP/PVE。可能在整合服务端线程触发。 */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        if (self == null) {
            return;
        }
        LivingEntity victim = event.getEntity();
        Entity attacker = event.getSource().getEntity();

        CombatEventType type = null;
        if (victim == self) {
            if (attacker instanceof Player && attacker != self) {
                type = CombatEventType.PVP;   // 被其他玩家打
            } else if (attacker != null && attacker != self) {
                type = CombatEventType.PVE;   // 被怪物打
            }
        } else if (attacker == self) {
            type = victim instanceof Player ? CombatEventType.PVP   // 打其他玩家
                    : CombatEventType.PVE;                          // 打怪物
        }
        if (type == null) {
            return;
        }
        // 归并到客户端主线程再喂状态机（逻辑类约定单线程访问）。
        CombatEventType finalType = type;
        mc.execute(() -> feed(finalType));
    }

    /** 主手攻击动作：准星指着玩家 → PVP，指着非玩家生物 → PVE。客户端线程。 */
    @SubscribeEvent
    public static void onAttackInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        Entity target = mc.crosshairPickEntity;
        if (target instanceof Player && target != mc.player) {
            feed(CombatEventType.PVP);
        } else if (target instanceof LivingEntity) {
            feed(CombatEventType.PVE);
        }
    }

    /** 每 tick：推进过渡延迟 + 状态机的 PVP 退出冷却。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        CombatCameraConfig cfg = CombatCameraConfig.get();
        if (!cfg.autoPvpFirstPerson) {
            pendingTicks = -1; // 关掉开关时丢弃未生效的延迟切换
            return;
        }
        if (pendingTicks >= 0) {
            pendingTicks--;
            if (pendingTicks < 0) {
                writeCamera(mc, pendingGoal);
            }
        }
        apply(mc, LOGIC.onTick(mc.level.getGameTime()), cfg);
    }

    /** 进世界：重读配置（手改 json 重进即生效）+ 复位状态机。 */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        CombatCameraConfig.reload();
        resetState();
    }

    /** 断线/退图：复位状态机，镜头交还玩家手动控制。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetState();
    }

    // ============================ 逻辑输出 → 镜头/actionbar ============================

    private static void feed(CombatEventType type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        CombatCameraConfig cfg = CombatCameraConfig.get();
        if (!cfg.autoPvpFirstPerson) {
            return;
        }
        apply(mc, LOGIC.onCombatEvent(type, mc.level.getGameTime()), cfg);
    }

    /** 应用一次状态切换：按配置立即写镜头或挂起过渡延迟。 */
    private static void apply(Minecraft mc, Optional<CameraGoal> goal, CombatCameraConfig cfg) {
        if (goal.isEmpty()) {
            return;
        }
        if (cfg.transitionTicks > 0) {
            // 最新状态赢：直接覆盖尚未生效的旧延迟切换。
            pendingGoal = goal.get();
            pendingTicks = cfg.transitionTicks;
        } else {
            writeCamera(mc, goal.get());
        }
    }

    /**
     * 真正写镜头（只在切换瞬间调一次，不持续霸占）+ actionbar 提示。
     * 当前镜头已与目标同侧时不写也不提示——尊重玩家手动 F5。
     */
    private static void writeCamera(Minecraft mc, CameraGoal goal) {
        CameraType current = mc.options.getCameraType();
        if (goal == CameraGoal.FIRST_PERSON) {
            if (!current.isFirstPerson()) {
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                actionbar(mc, KEY_PVP_ENTER, FALLBACK_PVP_ENTER);
            }
        } else {
            if (current.isFirstPerson()) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                actionbar(mc, KEY_PVP_EXIT, FALLBACK_PVP_EXIT);
            }
        }
    }

    private static void actionbar(Minecraft mc, String key, String fallback) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatableWithFallback(key, fallback), true);
        }
    }

    private static void resetState() {
        LOGIC.reset();
        pendingTicks = -1;
    }
}
