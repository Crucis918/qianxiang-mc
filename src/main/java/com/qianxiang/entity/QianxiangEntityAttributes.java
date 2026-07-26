package com.qianxiang.entity;

import com.qianxiang.Qianxiang;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

/**
 * 注册自定义实体的默认属性与自然生成规则。
 *
 * <p>必须在 MOD 总线监听 {@link EntityAttributeCreationEvent}，否则实体生成时会因缺少
 * {@code MAX_HEALTH} 等核心属性而崩溃。
 * <p>生成规则：两个 NPC 在万象森罗 biome 的 creature 池自然生成（见
 * {@code worldgen/biome/myriad_wilds.json}），限制为地表落脚，避免悬空/嵌墙。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class QianxiangEntityAttributes {
    private QianxiangEntityAttributes() {}

    @SubscribeEvent
    public static void register(EntityAttributeCreationEvent event) {
        // 直接复用原版 Villager 属性，保持 Villager-like 行为一致
        event.put(QianxiangEntities.WANDERING_SAGE.get(), Villager.createAttributes().build());
        event.put(QianxiangEntities.ABYSS_MERCHANT.get(), Villager.createAttributes().build());
        event.put(QianxiangEntities.MYRIAD_WARDEN.get(), QianxiangMyriadWarden.createAttributes().build());
    }

    @SubscribeEvent
    public static void registerPlacements(RegisterSpawnPlacementsEvent event) {
        event.register(QianxiangEntities.WANDERING_SAGE.get(),
                SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mob::checkMobSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(QianxiangEntities.ABYSS_MERCHANT.get(),
                SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mob::checkMobSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE);
        // Boss：地表生成、不受光照限制（万象森罗昼夜都可能遭遇）
        event.register(QianxiangEntities.MYRIAD_WARDEN.get(),
                SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mob::checkMobSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }
}
