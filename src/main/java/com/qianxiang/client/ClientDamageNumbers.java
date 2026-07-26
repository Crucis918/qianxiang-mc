package com.qianxiang.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qianxiang.Qianxiang;
import com.qianxiang.network.DamageNumberPayload;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 客户端伤害浮字渲染器。
 * <p>
 * 收到 {@link DamageNumberPayload}（来自
 * {@link com.qianxiang.combat.CombatEffectHandler}）后，把伤害数字塞进
 * {@code Map<entityId, List<DamageNumber>>}，然后在 {@link RenderLevelStageEvent}
 * 的 {@code AFTER_PARTICLES} 阶段画字：在目标头顶向上漂、逐渐淡出，约 30 tick 后移除。
 * </p>
 *
 * <h3>API 查证（neoforge-21.1.219 + mc 1.21.1）</h3>
 * <ul>
 *   <li>{@link RenderLevelStageEvent} 提供 {@code getCamera()}/{@code getPoseStack()}/
 *       {@code getPartialTick()}（DeltaTracker）。用 {@code Stage.AFTER_PARTICLES}
 *       保证画在粒子和实体之上。</li>
 *   <li>{@link Font#drawInBatch(String, float, float, int, boolean, org.joml.Matrix4f,
 *       MultiBufferSource, Font.DisplayMode, int, int)} —— javap 确认十参重载存在，
 *       内部按 DisplayMode 自选 RenderType，故本类无需直接引用 RenderType。</li>
 *   <li>{@link MultiBufferSource.BufferSource#endBatch()} flush 已写入的字形缓冲。</li>
 *   <li>{@link Camera#rotation()} 返回相机朝向四元数，conjugate 后 mulPose 抵消视角旋转，
 *       实现 billboard（文字始终面向玩家）。</li>
 * </ul>
 *
 * <h3>线程与去重</h3>
 * payload handler 通过 {@code enqueueWork} 排进客户端主线程，故这里的数据结构无需并发保护。
 * 同一实体一帧多次受伤：都画出来（错峰向上漂），不合并——玩家能看到连击次数。
 *
 * <p>{@link OnlyIn}({@link Dist#CLIENT}) + {@code @EventBusSubscriber(value=Dist.CLIENT)}
 * 双保险，服务端绝不加载此类。</p>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class ClientDamageNumbers {

    /** 浮字存活 tick：约 1.5 秒。 */
    private static final int LIFE_TICKS = 30;
    /** 向上漂移总距离（方块）：从头顶再上浮约 1 格。 */
    private static final float RISE_BLOCKS = 1.0f;
    /** 最大同时显示条数，防止极端情况刷屏。 */
    private static final int MAX_ENTRIES = 64;

    /** entityId → 该实体身上正在漂浮的伤害数字列表。主线程访问，无需同步。 */
    private static final Map<Integer, List<DamageNumber>> ACTIVE = new HashMap<>();

    private ClientDamageNumbers() {}

    /** 收到网络包：塞一个新浮字。在客户端主线程（enqueueWork）被调用。 */
    public static void receive(DamageNumberPayload payload) {
        List<DamageNumber> list = ACTIVE.computeIfAbsent(payload.targetEntityId(), k -> new ArrayList<>());
        list.add(new DamageNumber(payload.amount(), 0));
        // 防爆：单实体过多就丢最早的。
        if (list.size() > 8) {
            list.remove(0);
        }
        // 全局防爆：总数过多清最早的实体桶。
        if (ACTIVE.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<Integer, List<DamageNumber>>> it = ACTIVE.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /** 每帧渲染：漂移、淡出、画字。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        try {
            renderAll(event);
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] ClientDamageNumbers 渲染异常", t);
            // 渲染崩了就清空，避免持续报错刷屏。
            ACTIVE.clear();
        }
    }

    private static void renderAll(RenderLevelStageEvent event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        // getGameTimeDeltaPartialTick(true) = 渲染插值因子（0~1），用作浮字存活推进 + entity 插值。
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Font font = mc.font;
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        PoseStack pose = event.getPoseStack();

        // 推进所有浮字的存活时间（用 partialTick 让动画在低帧率下也平滑）。
        // 这里用 1 帧 ≈ 1 tick 的近似（渲染每帧调用），配合 LIFE_TICKS=30 视觉上 ≈ 1.5 秒。
        Iterator<Map.Entry<Integer, List<DamageNumber>>> entryIt = ACTIVE.entrySet().iterator();
        while (entryIt.hasNext()) {
            Map.Entry<Integer, List<DamageNumber>> entry = entryIt.next();
            int entityId = entry.getKey();
            Entity entity = level.getEntity(entityId);

            List<DamageNumber> list = entry.getValue();
            Iterator<DamageNumber> it = list.iterator();
            while (it.hasNext()) {
                DamageNumber dn = it.next();
                dn.age += partialTick;
                if (dn.age >= LIFE_TICKS) {
                    it.remove();
                    continue;
                }
                // 实体不在视野（死亡/卸载）就跳过绘制，但仍保留存活倒计时让其自然消失。
                if (entity == null || !entity.isAlive()) {
                    continue;
                }
                drawOne(pose, font, bufferSource, camera, entity, dn, partialTick);
            }
            if (list.isEmpty()) {
                entryIt.remove();
            }
        }

        // flush：把 drawInBatch 写入缓冲的字形真正提交绘制。
        bufferSource.endBatch();
    }

    /**
     * 画一个伤害数字：定位到实体头顶 → 偏移相机（世界→相机空间）→ 向上漂+淡出 →
     * 让文字始终面向相机（billboard）→ drawInBatch。
     */
    private static void drawOne(PoseStack pose, Font font, MultiBufferSource bufferSource,
                                Camera camera, Entity entity, DamageNumber dn, float partialTick) {
        Vec3 camPos = camera.getPosition();
        // —— 定位：实体包围盒顶 + 少量基准上浮 ——
        double headY = entity.getBoundingBox().maxY;
        Vec3 entityPos = entity.getPosition(partialTick);
        // 向上漂：随存活时间线性上浮 RISE_BLOCKS。
        float rise = (dn.age / (float) LIFE_TICKS) * RISE_BLOCKS;
        double wx = entityPos.x;
        double wy = headY + 0.4 + rise;
        double wz = entityPos.z;

        // —— 淡出：前 70% 时间全不透明，后 30% 线性淡到 0 ——
        float alpha;
        if (dn.age < LIFE_TICKS * 0.7f) {
            alpha = 1.0f;
        } else {
            alpha = Mth.clamp(1.0f - (dn.age - LIFE_TICKS * 0.7f) / (LIFE_TICKS * 0.3f), 0.0f, 1.0f);
        }

        // —— 颜色：小伤白偏黄、大伤偏红 ——
        int color = colorFor(dn.amount, alpha);

        // 构造文本：取整 + 保留 1 位小数（伤害通常 < 100，1 位小数够看）。
        String text = String.format("%.1f", dn.amount);

        // —— 渲染变换 ——
        pose.pushPose();
        // 世界坐标 → 相机相对坐标
        pose.translate((float) (wx - camPos.x), (float) (wy - camPos.y), (float) (wz - camPos.z));

        // billboard：撤销相机旋转，让文字始终正面朝玩家。
        // Camera.rotation() 返回相机朝向四元数，取共轭即逆旋转，施加到 PoseStack 抵消视角旋转。
        pose.mulPose(camera.rotation().conjugate(new org.joml.Quaternionf()));

        // 居中：按字宽左移一半；y 上移半个字高（lineHeight≈9）。
        float halfWidth = font.width(text) / 2.0f;
        pose.translate(-halfWidth, 0.0f, 0.0f);

        // 字号微缩：让数字比标准 GUI 字稍大、更显眼。
        float scale = 0.025f; // 世界坐标下的标准 GUI 字缩放（≈ 名字牌大小）
        pose.scale(scale, -scale, scale); // y 取负：世界 y 朝上，而 Font y 朝下

        // —— 画字：SEE_THROUGH 让数字不被实体/方块遮挡，更易读 ——
        // drawInBatch(text, x, y, color, dropShadow, matrix, bufferSource, displayMode, bgColor=0透明, packedLight=15728880=全亮)
        font.drawInBatch(
                text,
                0.0f,
                0.0f,
                color,
                true,
                pose.last().pose(),
                bufferSource,
                Font.DisplayMode.SEE_THROUGH,
                0,
                15728880);

        pose.popPose();
    }

    /**
     * 伤害→颜色映射：低伤（&lt;5）淡黄、中伤（5~15）橙、重伤（&gt;15）红。
     * alpha 编码进颜色高 8 位（ARGB）。
     */
    private static int colorFor(float amount, float alpha) {
        int a = Mth.clamp((int) (alpha * 255), 0, 255);
        int r, g, b;
        if (amount < 5.0f) {
            // 浅黄 → 白
            r = 255; g = 240; b = 120;
        } else if (amount < 15.0f) {
            // 橙
            r = 255; g = 160; b = 40;
        } else {
            // 大伤：红
            r = 255; g = 60; b = 60;
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 单条浮字的运行时状态：伤害值 + 已存活 tick。 */
    private static final class DamageNumber {
        final float amount;
        float age; // 已存活「tick」（实际由 partialTick 累加）

        DamageNumber(float amount, float age) {
            this.amount = amount;
            this.age = age;
        }
    }
}
