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
@EventBusSubscriber(modid = Qianxiang.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(QianxiangMenus.FORGE_TABLE.get(), ForgeTableScreen::new);
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(QianxiangEntities.WANDERING_SAGE.get(), QianxiangSageRenderer::new);
        event.registerEntityRenderer(QianxiangEntities.ABYSS_MERCHANT.get(), QianxiangAbyssMerchantRenderer::new);
        event.registerEntityRenderer(QianxiangEntities.SPELL_PROJECTILE.get(), SpellProjectileRenderer::new);
    }

    /** 武器外貌即时生成：把动态产物的静态模型包上 DynamicWeaponModel（无组件时静态纹理兜底）。 */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        DynamicWeaponModel.wrap(event.getModels());
    }
}
