package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangMenus;
import com.qianxiang.entity.QianxiangEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** 客户端注册：Screen + 实体渲染器。隔离在 client 包，避免服务端加载 GUI/渲染类。 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(QianxiangMenus.FORGE_TABLE.get(), ForgeTableScreen::new);
        event.register(QianxiangMenus.ALCHEMY_TABLE.get(), AlchemyTableScreen::new);
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(QianxiangEntities.WANDERING_SAGE.get(), QianxiangSageRenderer::new);
        event.registerEntityRenderer(QianxiangEntities.ABYSS_MERCHANT.get(), QianxiangAbyssMerchantRenderer::new);
        event.registerEntityRenderer(QianxiangEntities.SPELL_PROJECTILE.get(), SpellProjectileRenderer::new);
        event.registerEntityRenderer(QianxiangEntities.MYRIAD_WARDEN.get(), QianxiangMyriadWardenRenderer::new);
        // 功能台「去格子化」：漂浮材料/产物虚影（两台共用同一泛型 BER）
        event.registerBlockEntityRenderer(com.qianxiang.QianxiangBlockEntities.FORGE_TABLE.get(),
                com.qianxiang.client.render.FloatingItemsRenderer::new);
        event.registerBlockEntityRenderer(com.qianxiang.QianxiangBlockEntities.ALCHEMY_TABLE.get(),
                com.qianxiang.client.render.FloatingItemsRenderer::new);
    }

    /** 武器外貌即时生成：把动态产物的静态模型包上 DynamicWeaponModel（无组件时静态纹理兜底）。 */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        DynamicWeaponModel.wrap(event.getModels());
    }

    /** 自定义粒子（spark/shockwave）provider 注册。 */
    @SubscribeEvent
    public static void registerParticles(net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(com.qianxiang.QianxiangParticles.SPARK.get(),
                com.qianxiang.client.particle.SparkParticle.Provider::new);
        event.registerSpriteSet(com.qianxiang.QianxiangParticles.SHOCKWAVE.get(),
                com.qianxiang.client.particle.ShockwaveParticle.Provider::new);
    }
}
