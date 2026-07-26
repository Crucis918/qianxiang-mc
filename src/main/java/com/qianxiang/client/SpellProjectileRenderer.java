package com.qianxiang.client;

import com.qianxiang.entity.SpellProjectileEntity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 法术弹体渲染器：空渲染 —— 弹体视觉完全由服务端下发的元素轨迹粒子承担，
 * 不渲染任何模型，避免同步法术参数到客户端。
 */
@OnlyIn(Dist.CLIENT)
public class SpellProjectileRenderer extends EntityRenderer<SpellProjectileEntity> {

    public SpellProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(SpellProjectileEntity entity) {
        // 不会被用到（无模型），返回原版占位纹理即可
        return ResourceLocation.withDefaultNamespace("textures/item/snowball.png");
    }
}
