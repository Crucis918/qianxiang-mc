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

import java.util.HashMap;
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
    /** 材料环半径。 */
    private static final float RING_RADIUS = 0.35f;
    /** 转变动画时长（tick，0.5s）。 */
    private static final float TRANSITION_TICKS = 10.0f;
    /** 点缀粒子间隔（tick，约 2s）。 */
    private static final long SPARKLE_INTERVAL = 40L;

    /** 每个台子的客户端动画状态：上一帧产物 key、转变动画起点、上次点缀 tick。 */
    private static final class AnimState {
        String lastResultKey = "";
        float transitionStart = -TRANSITION_TICKS;
        long lastSparkleTick = Long.MIN_VALUE;
    }

    private final BlockEntityRendererProvider.Context context;
    private final Map<BlockPos, AnimState> animStates = new HashMap<>();

    public FloatingItemsRenderer(BlockEntityRendererProvider.Context context) {
        this.context = context;
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

        // —— 材料虚影环 ——
        float radius = RING_RADIUS * eased;
        int slotCount = be.materialSlotCount();
        int index = 0;
        for (int i = 0; i < slotCount; i++) {
            ItemStack stack = be.getItems().get(i);
            if (stack.isEmpty()) continue;
            float angle = baseAngle + index * (float) (Math.PI * 2.0 / Math.max(1, slotCount));
            double bob = Math.sin(time * 0.12 + index * 1.7) * 0.04;
            renderGhost(level, stack,
                    0.5 + Math.cos(angle) * radius, 1.05 + bob, 0.5 + Math.sin(angle) * radius,
                    0.35f, time, poseStack, bufferSource, packedOverlay);
            sparkle(level, be.getBlockPos(), gameTime, index, state);
            index++;
        }

        // —— 产物虚影：中心上方缓慢自转（转变期从台面升起并放大） ——
        if (!displayResult.isEmpty()) {
            double y = 0.85 + 0.35 * eased;
            poseStack.pushPose();
            poseStack.translate(0.5, y, 0.5);
            poseStack.scale(0.5f * eased, 0.5f * eased, 0.5f * eased);
            poseStack.mulPose(Axis.YP.rotationDegrees(time * 2.25f));
            context.getItemRenderer().renderStatic(displayResult,
                    ItemDisplayContext.FIXED, FULL_BRIGHT, packedOverlay, poseStack, bufferSource, level, 0);
            poseStack.popPose();
        }
    }

    /** 渲染一个 billboard 虚影（始终面向玩家）。 */
    private void renderGhost(Level level, ItemStack stack, double x, double y, double z, float scale,
                             float time, com.mojang.blaze3d.vertex.PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedOverlay) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.scale(scale, scale, scale);
        poseStack.mulPose(context.getEntityRenderer().cameraOrientation());
        context.getItemRenderer().renderStatic(stack,
                ItemDisplayContext.FIXED, FULL_BRIGHT, packedOverlay, poseStack, bufferSource, level, 0);
        poseStack.popPose();
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
