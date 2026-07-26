package com.qianxiang.entity;

import com.qianxiang.Qianxiang;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

/**
 * 注册自定义实体的默认属性。
 *
 * <p>必须在 MOD 总线监听 {@link EntityAttributeCreationEvent}，否则实体生成时会因缺少
 * {@code MAX_HEALTH} 等核心属性而崩溃。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class QianxiangEntityAttributes {
    private QianxiangEntityAttributes() {}

    @SubscribeEvent
    public static void register(EntityAttributeCreationEvent event) {
        // 直接复用原版 Villager 属性，保持 Villager-like 行为一致
        event.put(QianxiangEntities.WANDERING_SAGE.get(), Villager.createAttributes().build());
        event.put(QianxiangEntities.ABYSS_MERCHANT.get(), Villager.createAttributes().build());
    }
}
