package com.qianxiang.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 森罗守望者渲染器 —— 复用原版 {@link ZombieRenderer}，放大 1.6 倍。
 * <p>
 * MVP 不新增纹理：用原版溺尸（drowned）纹理近似"生机凝聚的守望之躯"的青蓝色调。
 * 体型放大让它在人群里一眼可辨。
 */
@OnlyIn(Dist.CLIENT)
public class QianxiangMyriadWardenRenderer extends ZombieRenderer {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/zombie/drowned.png");

    private static final float SCALE = 1.6F;

    public QianxiangMyriadWardenRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius *= SCALE;
    }

    @Override
    protected void scale(Zombie entity, PoseStack poseStack, float partialTickTime) {
        poseStack.scale(SCALE, SCALE, SCALE);
        super.scale(entity, poseStack, partialTickTime);
    }

    @Override
    public ResourceLocation getTextureLocation(Zombie entity) {
        return TEXTURE;
    }
}
