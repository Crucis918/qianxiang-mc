package com.qianxiang;

import com.qianxiang.block.ForgeTableBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class QianxiangBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, Qianxiang.MOD_ID);
    public static final DeferredRegister<Item> BLOCK_ITEMS = DeferredRegister.create(Registries.ITEM, Qianxiang.MOD_ID);

    // 自定义台（相之凝结台）
    public static final DeferredHolder<Block, ForgeTableBlock> FORGE_TABLE =
            BLOCKS.register("forge_table", () -> new ForgeTableBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE).strength(3.5f)));

    public static final DeferredHolder<Item, BlockItem> FORGE_TABLE_ITEM =
            BLOCK_ITEMS.register("forge_table", () -> new BlockItem(FORGE_TABLE.get(), new Item.Properties()));

    // —— 万象森罗维度 MVP 方块 ——

    // 裂隙岩：传送门框材料，用裂隙精髓右键可往返万象森罗
    public static final DeferredHolder<Block, Block> RIFT_STONE =
            BLOCKS.register("rift_stone", () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE).strength(3.0f, 6.0f).sound(SoundType.STONE).requiresCorrectToolForDrops()));

    public static final DeferredHolder<Item, BlockItem> RIFT_STONE_ITEM =
            BLOCK_ITEMS.register("rift_stone", () -> new BlockItem(RIFT_STONE.get(), new Item.Properties()));

    // 幽明草：万象森罗地表的发光植被
    public static final DeferredHolder<Block, Block> WILDLIGHT_GRASS =
            BLOCKS.register("wildlight_grass", () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GREEN).strength(0.6f).sound(SoundType.GRASS)
                    .lightLevel(s -> 15).noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> WILDLIGHT_GRASS_ITEM =
            BLOCK_ITEMS.register("wildlight_grass", () -> new BlockItem(WILDLIGHT_GRASS.get(), new Item.Properties()));

    // 万象木：发光的树干
    public static final DeferredHolder<Block, RotatedPillarBlock> GLIMMER_LOG =
            BLOCKS.register("glimmer_log", () -> new RotatedPillarBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD).strength(2.0f).sound(SoundType.WOOD).lightLevel(s -> 8)));

    public static final DeferredHolder<Item, BlockItem> GLIMMER_LOG_ITEM =
            BLOCK_ITEMS.register("glimmer_log", () -> new BlockItem(GLIMMER_LOG.get(), new Item.Properties()));

    // 万象叶：发光的树叶
    public static final DeferredHolder<Block, LeavesBlock> GLIMMER_LEAVES =
            BLOCKS.register("glimmer_leaves", () -> new LeavesBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PLANT).strength(0.2f).randomTicks().sound(SoundType.GRASS)
                    .lightLevel(s -> 7).noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> GLIMMER_LEAVES_ITEM =
            BLOCK_ITEMS.register("glimmer_leaves", () -> new BlockItem(GLIMMER_LEAVES.get(), new Item.Properties()));

    // 虚痕矿：万象森罗稀有新材料矿脉
    public static final DeferredHolder<Block, Block> VOID_ORE =
            BLOCKS.register("void_ore", () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK).strength(3.0f, 3.0f).sound(SoundType.STONE)
                    .lightLevel(s -> 5).requiresCorrectToolForDrops()));

    public static final DeferredHolder<Item, BlockItem> VOID_ORE_ITEM =
            BLOCK_ITEMS.register("void_ore", () -> new BlockItem(VOID_ORE.get(), new Item.Properties()));
}
