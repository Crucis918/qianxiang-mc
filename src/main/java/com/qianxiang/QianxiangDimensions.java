package com.qianxiang;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * 《千相》自定义维度键值。
 * <p>
 * 维度与维度类型本身由数据包 JSON 定义（data/qianxiang/dimension 等），
 * 这里只保留代码侧需要的 ResourceKey，供传送命令与传送门事件使用。
 */
public final class QianxiangDimensions {
    public static final ResourceKey<Level> MYRIAD_WILDS = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "myriad_wilds"));
    public static final ResourceKey<DimensionType> MYRIAD_WILDS_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE,
            ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "myriad_wilds"));

    private QianxiangDimensions() {}
}
