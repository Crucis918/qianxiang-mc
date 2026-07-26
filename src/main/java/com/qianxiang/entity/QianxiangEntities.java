package com.qianxiang.entity;

import com.qianxiang.Qianxiang;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 《千相》自定义实体注册。
 *
 * <p>MVP 先加入两个站桩/慢走 NPC：
 * <ul>
 *   <li>流浪相师（wandering_sage）</li>
 *   <li>深渊商人（abyss_merchant）</li>
 * </ul>
 */
public final class QianxiangEntities {
    private QianxiangEntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Qianxiang.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<QianxiangWanderingSage>> WANDERING_SAGE =
            ENTITIES.register("wandering_sage", () -> EntityType.Builder.of(QianxiangWanderingSage::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .build(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "wandering_sage").toString()));

    public static final DeferredHolder<EntityType<?>, EntityType<QianxiangAbyssMerchant>> ABYSS_MERCHANT =
            ENTITIES.register("abyss_merchant", () -> EntityType.Builder.of(QianxiangAbyssMerchant::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .build(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "abyss_merchant").toString()));

    /** 森罗守望者：万象森罗漫游 Boss（高属性 + Boss 血条 + 传奇材料掉落）。 */
    public static final DeferredHolder<EntityType<?>, EntityType<QianxiangMyriadWarden>> MYRIAD_WARDEN =
            ENTITIES.register("myriad_warden", () -> EntityType.Builder.of(QianxiangMyriadWarden::new, MobCategory.MONSTER)
                    .sized(0.96F, 3.1F)
                    .clientTrackingRange(10)
                    .build(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "myriad_warden").toString()));

    /** 自由法术弹体：视觉靠服务端粒子，渲染器为空实现。 */
    public static final DeferredHolder<EntityType<?>, EntityType<SpellProjectileEntity>> SPELL_PROJECTILE =
            ENTITIES.register("spell_projectile", () -> EntityType.Builder.of(SpellProjectileEntity::new, MobCategory.MISC)
                    .sized(0.35F, 0.35F)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "spell_projectile").toString()));
}
