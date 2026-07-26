package com.qianxiang.client;

import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 流浪相师渲染器 —— 复用原版 {@link VillagerRenderer}，使用默认村民纹理。
 *
 * <p>MVP 阶段不新增纹理资源，直接沿用原版视觉即可。
 */
@OnlyIn(Dist.CLIENT)
public class QianxiangSageRenderer extends VillagerRenderer {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");

    public QianxiangSageRenderer(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(Villager entity) {
        return TEXTURE;
    }
}
