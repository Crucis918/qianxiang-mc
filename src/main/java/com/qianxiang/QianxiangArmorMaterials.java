package com.qianxiang;

import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/**
 * 千相护甲材质注册。
 * <p>
 * {@code phase_hide} 只是「兜底壳」：提供的防御/韧性是空壳默认值（皮革水准），
 * 真正的防御/耐久/韧性由 {@link com.qianxiang.item.QianxiangArmorItem}
 * 在 {@code getDefaultAttributeModifiers(ItemStack)} / {@code getMaxDamage(ItemStack)}
 * 里从产物的 {@code composed_attributes} 组件动态读取——强度靠材料，不硬编码。
 * </p>
 */
public final class QianxiangArmorMaterials {
    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, Qianxiang.MOD_ID);

    // 相革 phase_hide —— 千相防具共用材质。兜底层级≈皮革；穿戴纹理取
    // assets/qianxiang/textures/models/armor/phase_hide_layer_1.png / _layer_2.png。
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> PHASE_HIDE =
            ARMOR_MATERIALS.register("phase_hide", () -> new ArmorMaterial(
                    Util.make(new EnumMap<>(ArmorItem.Type.class), map -> {
                        map.put(ArmorItem.Type.HELMET, 1);
                        map.put(ArmorItem.Type.CHESTPLATE, 3);
                        map.put(ArmorItem.Type.LEGGINGS, 2);
                        map.put(ArmorItem.Type.BOOTS, 1);
                        map.put(ArmorItem.Type.BODY, 3);
                    }),
                    15,
                    SoundEvents.ARMOR_EQUIP_LEATHER,
                    () -> Ingredient.of(QianxiangMaterials.SHADOWHIDE_PATCH.get()),
                    List.of(new ArmorMaterial.Layer(
                            ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "phase_hide"))),
                    0.0F,
                    0.0F
            ));

    private QianxiangArmorMaterials() {}
}
