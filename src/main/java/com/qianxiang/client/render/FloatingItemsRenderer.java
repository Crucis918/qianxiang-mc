package com.qianxiang.client.render;

import com.mojang.math.Axis;
import com.qianxiang.block.FloatingTableView;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 功能台「去格子化」的漂浮材料虚影渲染器（锻造台/炼金台共用，按 {@link FloatingTableView} 参数化）。
 * <ul>
 *   <li>材料虚影：绕台面中心半径 0.35 环形排布，约 4s/圈公转 + 相位错开 bob，
 *       billboard 始终面向玩家；PARSING 状态公转 ×3 速。</li>
 *   <li>产物虚影（displayResult 非空）：中心上方缓慢自转。</li>
 *   <li>转变动画（纯客户端状态）：产物从无到有/变化 → 0.5s 材料螺旋内收 + 产物升起放大；
 *       产物消失 → 中心粒子四溅。</li>
 *   <li>点缀粒子暂用原版 END_ROD（客户端本地生成，零网络成本）。
 *       TODO(下一步)：换自定义 spark/shockwave 粒子类型——接入点见 sparkle()。</li>
 * </ul>
 */
public class FloatingItemsRenderer<T extends BlockEntity & FloatingTableView>
        implements BlockEntityRenderer<T> {

    /** 全亮光照（虚影不受环境光影响）。 */
    private static final int FULL_BRIGHT = 15728880;
    /** 公转一圈的 tick 数（约 4 秒）。 */
    private static final float ORBIT_TICKS = 80.0f;
    /** 材料环半径（单圈，≤8 件）。 */
    private static final float RING_RADIUS = 0.45f;
    /** 材料数 > 8 时的双圈：内圈最多 8 个、外圈承接其余（实拍：单圈 r=0.35 挤成白团不可辨）。 */
    private static final float INNER_RING_RADIUS = 0.28f;
    private static final float OUTER_RING_RADIUS = 0.55f;
    private static final int INNER_RING_MAX = 8;
    /** 材料虚影缩放（0.35→0.45，与环半径同步放大提升可辨度）。 */
    private static final float GHOST_SCALE = 0.45f;
    /** 转变动画时长（tick，0.5s）。 */
    private static final float TRANSITION_TICKS = 10.0f;
    /** 点缀粒子间隔（tick，约 2s）。 */
    private static final long SPARKLE_INTERVAL = 40L;

    /** 每个台子的客户端动画状态：上一帧产物 key、转变动画起点、上次点缀 tick、仪式状态跟踪。 */
    private static final class AnimState {
        String lastResultKey = "";
        float transitionStart = -TRANSITION_TICKS;
        long lastSparkleTick = Long.MIN_VALUE;
        /** 仪式：本地检测到的状态与进入时刻（VFX 进度用本地时间平滑推进）。 */
        com.qianxiang.block.RitualState lastRitualState = com.qianxiang.block.RitualState.NONE;
        float ritualEnterTime = 0.0f;
        long lastRitualFxTick = Long.MIN_VALUE;
    }

    private final BlockEntityRendererProvider.Context context;
    private final Map<BlockPos, AnimState> animStates = new HashMap<>();
    /** true = 炼金台风格（FORMING 画魔法阵）；false = 锻造台风格（锻打火花）。 */
    private final boolean alchemyStyle;

    public FloatingItemsRenderer(BlockEntityRendererProvider.Context context, boolean alchemyStyle) {
        this.context = context;
        this.alchemyStyle = alchemyStyle;
    }

    @Override
    public void render(T be, float partialTick, com.mojang.blaze3d.vertex.PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null) return;
        long gameTime = level.getGameTime();
        float time = gameTime + partialTick;

        AnimState state = animStates.computeIfAbsent(be.getBlockPos(), p -> new AnimState());
        if (animStates.size() > 256) {
            animStates.clear(); // 防长期游玩累积：状态可重建，清空无成本
        }

        // —— 合成仪式 VFX：本地跟踪状态切换（切 DONE 瞬间放收尾特效） ——
        var ritual = be.ritualState();
        if (ritual != state.lastRitualState) {
            if (ritual == com.qianxiang.block.RitualState.DONE
                    && state.lastRitualState == com.qianxiang.block.RitualState.FORMING) {
                onRitualDoneLocal(level, be);
            }
            state.lastRitualState = ritual;
            state.ritualEnterTime = time;
        }
        if (ritual == com.qianxiang.block.RitualState.FLYING) {
            renderRitualFlying(be, level, time, gameTime, state, poseStack, bufferSource, packedOverlay);
            return;
        }
        if (ritual == com.qianxiang.block.RitualState.FORMING) {
            renderRitualForming(be, level, time, gameTime, state);
            return;
        }

        ItemStack displayResult = be.getDisplayResult();
        String resultKey = displayResult.isEmpty()
                ? "" : BuiltInRegistries.ITEM.getKey(displayResult.getItem()).toString();
        if (!resultKey.equals(state.lastResultKey)) {
            if (!state.lastResultKey.isEmpty() && resultKey.isEmpty()) {
                // 产物消失：中心粒子四溅（客户端点缀，无网络成本）
                burst(level, be.getBlockPos());
            }
            state.lastResultKey = resultKey;
            state.transitionStart = time;
        }
        // 转变进度 0→1（产物出现时材料螺旋内收、产物升起放大；无产物时恒 1=常态）
        float transition = resultKey.isEmpty()
                ? 1.0f : Math.min(1.0f, (time - state.transitionStart) / TRANSITION_TICKS);
        float eased = transition * transition * (3.0f - 2.0f * transition); // smoothstep

        // 公转速度：PARSING（AI 解析中）×3
        float orbitSpeed = (float) (Math.PI * 2.0 / ORBIT_TICKS) * (be.getCraftingState() == 1 ? 3.0f : 1.0f);
        float baseAngle = time * orbitSpeed;

        // —— 材料虚影环：≤8 件单圈（r=0.45），>8 件双圈（内 8 + 外其余），
        //    每件带索引固定的 ±15° 倾角，避免完全同向重叠成一团 ——
        List<ItemStack> mats = new ArrayList<>();
        int slotCount = be.materialSlotCount();
        for (int i = 0; i < slotCount; i++) {
            ItemStack stack = be.getItems().get(i);
            if (!stack.isEmpty()) mats.add(stack);
        }
        int total = mats.size();
        boolean twoRings = total > INNER_RING_MAX;
        for (int index = 0; index < total; index++) {
            int ringIndex, ringSize;
            float ringRadius;
            if (twoRings && index >= INNER_RING_MAX) {
                ringIndex = index - INNER_RING_MAX;
                ringSize = total - INNER_RING_MAX;
                ringRadius = OUTER_RING_RADIUS;
            } else {
                ringIndex = index;
                ringSize = twoRings ? INNER_RING_MAX : total;
                ringRadius = twoRings ? INNER_RING_RADIUS : RING_RADIUS;
            }
            float angle = baseAngle + (ringIndex + (twoRings && ringRadius == OUTER_RING_RADIUS ? 0.5f : 0.0f))
                    * (float) (Math.PI * 2.0 / Math.max(1, ringSize));
            double bob = Math.sin(time * 0.12 + index * 1.7) * 0.04;
            // 索引固定的伪随机倾角（-15°~+15°）：billboard 同向重叠的解糊
            float tilt = (((index * 37) % 31) / 30.0f - 0.5f) * 30.0f;
            renderGhost(level, mats.get(index),
                    0.5 + Math.cos(angle) * ringRadius * eased, 1.05 + bob,
                    0.5 + Math.sin(angle) * ringRadius * eased,
                    GHOST_SCALE, tilt, time, poseStack, bufferSource, packedOverlay);
            sparkle(level, be.getBlockPos(), gameTime, index, state);
        }

        // —— 产物虚影：中心上方缓慢自转（转变期从台面升起并放大；终点 1.35 与材料环拉开层次） ——
        if (!displayResult.isEmpty()) {
            double y = 1.0 + 0.35 * eased;
            poseStack.pushPose();
            poseStack.translate(0.5, y, 0.5);
            poseStack.scale(0.5f * eased, 0.5f * eased, 0.5f * eased);
            poseStack.mulPose(Axis.YP.rotationDegrees(time * 2.25f));
            context.getItemRenderer().renderStatic(displayResult,
                    ItemDisplayContext.FIXED, FULL_BRIGHT, packedOverlay, poseStack, bufferSource, level, 0);
            poseStack.popPose();
        }
    }

    /** 渲染一个 billboard 虚影（面向玩家 + tiltDeg 屏幕内倾角解重叠）。 */
    private void renderGhost(Level level, ItemStack stack, double x, double y, double z, float scale,
                             float tiltDeg, float time, com.mojang.blaze3d.vertex.PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedOverlay) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.scale(scale, scale, scale);
        poseStack.mulPose(context.getEntityRenderer().cameraOrientation());
        if (tiltDeg != 0.0f) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(tiltDeg));
        }
        context.getItemRenderer().renderStatic(stack,
                ItemDisplayContext.FIXED, FULL_BRIGHT, packedOverlay, poseStack, bufferSource, level, 0);
        poseStack.popPose();
    }

    /** 旧签名（仪式飞材等不需要倾角的调用点）。 */
    private void renderGhost(Level level, ItemStack stack, double x, double y, double z, float scale,
                             float time, com.mojang.blaze3d.vertex.PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedOverlay) {
        renderGhost(level, stack, x, y, z, scale, 0.0f, time, poseStack, bufferSource, packedOverlay);
    }

    // ============================ 合成仪式 VFX（粒子全部复用 spark/shockwave） ============================

    /** FLYING：材料 ghost 从仪式发起者位置抛物线飞入台面，拖 spark 尾迹，到达即消散。 */
    private void renderRitualFlying(T be, Level level, float time, long gameTime, AnimState state,
                                    com.mojang.blaze3d.vertex.PoseStack poseStack,
                                    MultiBufferSource bufferSource, int packedOverlay) {
        BlockPos pos = be.getBlockPos();
        var owner = be.ritualOwner() != null ? level.getPlayerByUUID(be.ritualOwner()) : null;
        double sx = owner != null ? owner.getX() : pos.getX() + 0.5;
        double sy = owner != null ? owner.getY() + 1.2 : pos.getY() + 2.0;
        double sz = owner != null ? owner.getZ() : pos.getZ() + 0.5;
        double ex = pos.getX() + 0.5, ey = pos.getY() + 1.1, ez = pos.getZ() + 0.5;
        float elapsed = time - state.ritualEnterTime;

        var inputs = be.ritualInputs();
        for (int i = 0; i < inputs.size(); i++) {
            // 每件材料错峰 3t 出发，约 20t 飞抵
            float t = Math.min(1.0f, Math.max(0.0f, (elapsed - i * 3.0f) / 20.0f));
            if (t <= 0.0f) continue;
            double x = sx + (ex - sx) * t;
            double y = sy + (ey - sy) * t + Math.sin(t * Math.PI) * 0.8;
            double z = sz + (ez - sz) * t;
            renderGhost(level, inputs.get(i), x - pos.getX(), y - pos.getY(), z - pos.getZ(),
                    0.35f, time, poseStack, bufferSource, packedOverlay);
            // 拖尾 + 到达消散（客户端本地粒子，无网络成本）
            if (t < 1.0f && gameTime % 2 == 0) {
                level.addParticle(new com.qianxiang.particle.SparkParticleOptions(SPARKLE_COLOR),
                        x, y, z, 0.0, 0.0, 0.0);
            }
            if (t >= 1.0f && state.lastRitualFxTick != gameTime) {
                state.lastRitualFxTick = gameTime;
                level.addParticle(new com.qianxiang.particle.SparkParticleOptions(SPARKLE_COLOR),
                        ex, ey, ez, 0.0, 0.06, 0.0);
            }
        }
    }

    /** FORMING：炼金台画魔法阵（双层反向环+内接五边形），锻造台锻打火花（每 10t 一轮）。 */
    private void renderRitualForming(T be, Level level, float time, long gameTime, AnimState state) {
        BlockPos pos = be.getBlockPos();
        float progress = Math.min(1.0f,
                (time - state.ritualEnterTime) / com.qianxiang.block.RitualLogic.FORMING_TICKS);
        org.joml.Vector3f color = ritualColor(be);
        double cx = pos.getX() + 0.5, cy = pos.getY() + 1.5, cz = pos.getZ() + 0.5;
        var spark = new com.qianxiang.particle.SparkParticleOptions(color);

        if (alchemyStyle) {
            // 偶数 tick 才摆（密度减半，形状不变）
            if (gameTime % 2 != 0) return;
            double coverage = progress * Math.PI * 2.0;
            // 双层反向旋转同心环（外 r=0.8 正转，内 r=0.5 反转），覆盖角随 progress 涨满
            for (int k = 0; k < 40; k++) {
                double a = k / 40.0 * coverage + time * 0.05;
                level.addParticle(spark, cx + Math.cos(a) * 0.8, cy, cz + Math.sin(a) * 0.8, 0, 0, 0);
                double b = -k / 40.0 * coverage - time * 0.05;
                level.addParticle(spark, cx + Math.cos(b) * 0.5, cy, cz + Math.sin(b) * 0.5, 0, 0, 0);
            }
            // 内接五边形（外环内）：逐条边随 progress 画出
            for (int v = 0; v < 5 && progress * 5 > v; v++) {
                double a1 = v * (Math.PI * 2.0 / 5.0) - Math.PI / 2.0;
                double a2 = (v + 1) * (Math.PI * 2.0 / 5.0) - Math.PI / 2.0;
                for (int s = 0; s <= 5; s++) {
                    double f = s / 5.0;
                    level.addParticle(spark,
                            cx + Math.cos(a1) * 0.8 + (Math.cos(a2) - Math.cos(a1)) * 0.8 * f, cy,
                            cz + Math.sin(a1) * 0.8 + (Math.sin(a2) - Math.sin(a1)) * 0.8 * f,
                            0, 0, 0);
                }
            }
        } else {
            // 锻打火花：每 10t 一轮（spark 四溅 + 烟 + ANVIL_LAND 0.4）
            if (gameTime % 10 != 0 || state.lastRitualFxTick == gameTime) return;
            state.lastRitualFxTick = gameTime;
            for (int i = 0; i < 6; i++) {
                double a = Math.PI * 2.0 * i / 6.0;
                level.addParticle(spark, cx, cy - 0.4, cz,
                        Math.cos(a) * 0.12, 0.18, Math.sin(a) * 0.12);
            }
            level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                    cx + 0.1, cy - 0.3, cz, 0.0, 0.03, 0.0);
            level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                    cx - 0.1, cy - 0.25, cz + 0.05, 0.0, 0.03, 0.0);
            level.playLocalSound(pos, net.minecraft.sounds.SoundEvents.ANVIL_LAND,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.4f, 1.1f, false);
        }
    }

    /** 切 DONE 瞬间（本地检测）：shockwave + 白闪 + 完成音（客户端 playLocalSound，无网络成本）。 */
    private void onRitualDoneLocal(Level level, T be) {
        BlockPos pos = be.getBlockPos();
        double cx = pos.getX() + 0.5, cy = pos.getY() + 1.1, cz = pos.getZ() + 0.5;
        level.addParticle(new com.qianxiang.particle.ShockwaveParticleOptions(SPARKLE_COLOR, 1.5f),
                cx, cy, cz, 0.0, 0.0, 0.0);
        for (int i = 0; i < 5; i++) {
            level.addParticle(new com.qianxiang.particle.SparkParticleOptions(SPARKLE_COLOR),
                    cx, cy + 0.2, cz, (level.random.nextDouble() - 0.5) * 0.3,
                    0.2 + level.random.nextDouble() * 0.2, (level.random.nextDouble() - 0.5) * 0.3);
        }
        var sound = alchemyStyle
                ? net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE
                : net.minecraft.sounds.SoundEvents.ANVIL_USE;
        level.playLocalSound(pos, sound, net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.0f, false);
    }

    /** 仪式粒子颜色：pendingResult 法术元素色（读不到用白金色）。 */
    private static org.joml.Vector3f ritualColor(com.qianxiang.block.FloatingTableView view) {
        ItemStack pending = view.pendingResult();
        if (!pending.isEmpty()) {
            var spell = pending.get(com.qianxiang.QianxiangDataComponents.CUSTOM_SPELL.get());
            if (spell != null) {
                return com.qianxiang.spell.SpellEffectEngine.colorFor(spell.element());
            }
        }
        return SPARKLE_COLOR;
    }

    /** 点缀用白金色（虚影的相之辉光，不随元素变）。 */
    private static final org.joml.Vector3f SPARKLE_COLOR = new org.joml.Vector3f(0.95f, 0.90f, 0.65f);

    /** 每个虚影约 2s 一颗点缀粒子（客户端本地，无网络成本）。 */
    private void sparkle(Level level, BlockPos pos, long gameTime, int index, AnimState state) {
        if ((gameTime + index * 7L) % SPARKLE_INTERVAL != 0 || state.lastSparkleTick == gameTime) {
            return;
        }
        state.lastSparkleTick = gameTime;
        // 自定义 spark 火花：白金色、缓慢上升的相之辉光
        level.addParticle(new com.qianxiang.particle.SparkParticleOptions(SPARKLE_COLOR),
                pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                0.0, 0.03, 0.0);
    }

    /** 产物消失时的中心冲击波 + 火花四溅（客户端点缀）。 */
    private static void burst(Level level, BlockPos pos) {
        // 自定义 shockwave 冲击波环
        level.addParticle(new com.qianxiang.particle.ShockwaveParticleOptions(SPARKLE_COLOR, 1.2f),
                pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5,
                0.0, 0.0, 0.0);
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI * 2.0 * i / 6.0;
            level.addParticle(new com.qianxiang.particle.SparkParticleOptions(SPARKLE_COLOR),
                    pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                    Math.cos(angle) * 0.1, 0.12, Math.sin(angle) * 0.1);
        }
    }
}
