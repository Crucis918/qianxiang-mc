package com.qianxiang;

import com.qianxiang.block.ForgeTableBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class QianxiangBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Qianxiang.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ForgeTableBlockEntity>> FORGE_TABLE =
            BLOCK_ENTITIES.register("forge_table", () -> BlockEntityType.Builder
                    .of(ForgeTableBlockEntity::new, QianxiangBlocks.FORGE_TABLE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.qianxiang.block.AlchemyTableBlockEntity>> ALCHEMY_TABLE =
            BLOCK_ENTITIES.register("alchemy_table", () -> BlockEntityType.Builder
                    .of(com.qianxiang.block.AlchemyTableBlockEntity::new,
                            QianxiangBlocks.ALCHEMY_TABLE.get()).build(null));
}
