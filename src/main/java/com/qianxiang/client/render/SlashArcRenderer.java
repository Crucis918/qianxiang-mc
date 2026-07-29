package com.qianxiang.client.render;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.particle.SparkParticleOptions;
import com.qianxiang.phase.ComposedAttributes;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 刀光剑气：近战攻击瞬间，在攻击者面前沿攻击方向摆出一道 90° 弧形刀光。
 * <p>
 * 触发点：客户端 {@link AttackEntityEvent}（原版客户端对本地玩家的攻击做完整预测，
 * {@code MultiPlayerGameMode.attack} 会同步调 {@code Player.attack}，事件在客户端同样触发；
 * 改造客户端发假包也只影响自己屏幕，无作弊面）。事件里只<b>快照入队</b>（位置/朝向/刃缘色），
 * 真正的粒子摆弧在 {@link RenderLevelStageEvent} 的 {@code AFTER_PARTICLES} 阶段做——
 * 一次挥击拆成约 180ms 的逐帧扫描，视觉上刀光从左到右「摆」过去而非一团糊。
 * </p>
 *
 * <h3>刃缘色（读组件，与 {@link DynamicWeaponTexture} 同源语义）</h3>
 * 主手千相武器：先看 {@code appearanceKey} 效果色（ember/blood/shadow…——对应贴图的刃缘白热档），
 * 没有主导效果再看 {@code baseFamily} 基底族色（metal/bone/wood/hide）；
 * 非千相武器/空手回落白金色。
 *
 * <h3>防刷屏</h3>
 * 同一攻击者两次刀光间隔 ≥ 0.5s（连点器/高攻速只出第一道）；全局窗口每秒最多 4 道；
 * 队列上限 8，溢出丢最早。时间基准用 {@link System#currentTimeMillis()}：
 * 纯客户端演出，不受 {@code /tick freeze} 影响，也无需与服务端对时。
 *
 * <h3>已知边界</h3>
 * 客户端只能观测到<b>本地玩家</b>的攻击事件（无新网络包，边界不动 network/），
 * 因此看不到其他玩家的刀光；Epic Fight 战斗模式下若其旁路原版客户端攻击预测，
 * 该模式下的刀光可能不触发（玩法与伤害不受影响）。
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class SlashArcRenderer {

    /** 一道刀光的总粒子数（12~16 取中 14）。 */
    private static final int TOTAL_SPARKS = 14;
    /** 刀光扫描时长（毫秒）：粒子在这段时间内沿弧逐个摆出。 */
    private static final long SWEEP_MS = 180L;
    /** 弧半径（格）：刀光在攻击者面前约一臂之外。 */
    private static final double ARC_RADIUS = 1.3;
    /** 刀光高度（相对攻击者脚底）：胸口位置。 */
    private static final double ARC_HEIGHT = 1.2;
    /** 同一攻击者两道刀光的最小间隔（毫秒）：0.5s 防刷屏。 */
    private static final long MIN_INTERVAL_MS = 500L;
    /** 全局每秒刀光上限。 */
    private static final int MAX_ARCS_PER_SECOND = 4;
    /** 队列上限（溢出丢最早）。 */
    private static final int MAX_ACTIVE = 8;

    /** 进行中的刀光（主线程访问，无需同步）。 */
    private static final List<SlashArc> ACTIVE = new ArrayList<>();
    private static long lastArcMs = Long.MIN_VALUE;
    private static long windowStartMs;
    private static int arcsInWindow;

    private SlashArcRenderer() {}

    /** 攻击事件：快照一道刀光入队（命中/未命中都出光——挥空也有剑风，符合荣耀演出感）。 */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        try {
            if (!(event.getEntity() instanceof Player player) || !player.level().isClientSide()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            // 只画本地玩家的刀光：客户端拿不到其他玩家的攻击事件（不加网络包的前提下）。
            if (mc.player == null || player != mc.player) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastArcMs < MIN_INTERVAL_MS) {
                return;
            }
            if (now - windowStartMs >= 1000L) {
                windowStartMs = now;
                arcsInWindow = 0;
            }
            if (arcsInWindow >= MAX_ARCS_PER_SECOND) {
                return;
            }
            arcsInWindow++;
            lastArcMs = now;
            ACTIVE.add(new SlashArc(player.getX(), player.getY() + ARC_HEIGHT, player.getZ(),
                    player.getYRot(), bladeColor(player), now));
            if (ACTIVE.size() > MAX_ACTIVE) {
                ACTIVE.remove(0);
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] SlashArcRenderer 入队刀光异常", t);
        }
    }

    /** 渲染阶段：按进度把弧上的火花逐帧摆出（扫掠感），摆完即移除。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            long now = System.currentTimeMillis();
            Iterator<SlashArc> it = ACTIVE.iterator();
            while (it.hasNext()) {
                SlashArc arc = it.next();
                float progress = (now - arc.startMs) / (float) SWEEP_MS;
                if (progress >= 1.0f) {
                    it.remove();
                    continue;
                }
                int upto = Math.min(TOTAL_SPARKS, (int) (progress * TOTAL_SPARKS) + 1);
                var options = new SparkParticleOptions(arc.color);
                double yawRad = Math.toRadians(arc.yaw);
                for (int i = arc.spawned; i < upto; i++) {
                    // 90° 弧：-45° ~ +45° 相对视线；MC 前向 = (-sin yaw, cos yaw)
                    double a = -Math.PI / 4.0 + (Math.PI / 2.0) * i / (TOTAL_SPARKS - 1.0);
                    double dx = -Math.sin(yawRad + a);
                    double dz = Math.cos(yawRad + a);
                    mc.level.addParticle(options,
                            arc.x + dx * ARC_RADIUS, arc.y, arc.z + dz * ARC_RADIUS,
                            dx * 0.12, 0.06, dz * 0.12);
                }
                arc.spawned = Math.max(arc.spawned, upto);
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] SlashArcRenderer 渲染异常", t);
            ACTIVE.clear();
        }
    }

    /** 断线/换世界时清空，避免旧世界的刀光挂到新世界。 */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ACTIVE.clear();
        lastArcMs = Long.MIN_VALUE;
    }

    /**
     * 刃缘色解析：主手千相武器读 {@code composed_attributes} 组件——
     * 效果色（appearanceKey）优先，其次基底族（baseFamily），其余回落白金色。
     */
    private static Vector3f bladeColor(Player player) {
        ItemStack stack = player.getMainHandItem();
        ComposedAttributes attr = stack.isEmpty()
                ? null : stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr != null) {
            Vector3f effect = APPEARANCE_EDGE.get(attr.appearanceKey());
            if (effect != null) {
                return effect;
            }
            Vector3f family = FAMILY_EDGE.get(attr.baseFamily());
            if (family != null) {
                return family;
            }
        }
        return DEFAULT_EDGE;
    }

    /** appearanceKey → 刃缘白热色（与 DynamicWeaponTexture 的 PALETTES main 同色系）。plain/bulwark 不映射，落族色。 */
    private static final Map<String, Vector3f> APPEARANCE_EDGE = Map.of(
            "ember", new Vector3f(0.88f, 0.35f, 0.12f),
            "blood", new Vector3f(0.69f, 0.08f, 0.08f),
            "thorn", new Vector3f(0.35f, 0.63f, 0.24f),
            "shadow", new Vector3f(0.35f, 0.16f, 0.45f),
            "life", new Vector3f(0.91f, 0.81f, 0.43f),
            "arcane", new Vector3f(0.61f, 0.35f, 0.82f),
            "bone", new Vector3f(0.91f, 0.88f, 0.80f));

    /** baseFamily → 刃体亮色（钢灰/象牙白/木棕/革棕，比贴图 body 略提亮，刀光要显眼）。 */
    private static final Map<String, Vector3f> FAMILY_EDGE = Map.of(
            "metal", new Vector3f(0.75f, 0.78f, 0.82f),
            "bone", new Vector3f(0.91f, 0.88f, 0.80f),
            "wood", new Vector3f(0.55f, 0.38f, 0.22f),
            "hide", new Vector3f(0.60f, 0.47f, 0.33f));

    /** 默认刃缘色：白金色（非千相武器/空手）。 */
    private static final Vector3f DEFAULT_EDGE = new Vector3f(0.95f, 0.90f, 0.65f);

    /** 一道进行中的刀光：触发瞬间的快照 + 已摆出粒子数。 */
    private static final class SlashArc {
        final double x;
        final double y;
        final double z;
        final float yaw;
        final Vector3f color;
        final long startMs;
        int spawned;

        SlashArc(double x, double y, double z, float yaw, Vector3f color, long startMs) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.color = color;
            this.startMs = startMs;
        }
    }
}
