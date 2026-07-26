package com.qianxiang;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式分组「千相」。
 * <p>
 * 把所有材料（10）、产物武器（3）、锻造台（1）塞进一个 tab，方便创造模式直接取用，
 * 也作为mod的「门面」——玩家打开创造栏第一眼就能看到这套零件库。
 * <p>
 * 集成契约：在 {@link Qianxiang} 主类构造器里加一行
 * {@code QianxiangCreativeTab.CREATIVE_TABS.register(modEventBus);}
 * （位置无依赖，放其它 register 附近即可）。
 */
public final class QianxiangCreativeTab {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Qianxiang.MOD_ID);

    // 主分组 qianxiang:main。图标用灼烧之刃（代表性产物武器）。
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.qianxiang"))
                    .icon(() -> new ItemStack(QianxiangItems.EMBER_BLADE.get()))
                    .displayItems((params, output) -> {
                        // —— 10 个材料（8 在 QianxiangMaterials，2 在 QianxiangItems）——
                        output.accept(QianxiangMaterials.GLIMMER_WOOD_SAP.get());
                        output.accept(QianxiangMaterials.SHADOWHIDE_PATCH.get());
                        output.accept(QianxiangMaterials.EMBER_IRON.get());
                        output.accept(QianxiangMaterials.BLOODROOT.get());
                        output.accept(QianxiangMaterials.ABYSS_IRON.get());
                        output.accept(QianxiangMaterials.SALAMANDER_GLAND.get());
                        output.accept(QianxiangMaterials.DRAGON_BONE.get());
                        output.accept(QianxiangMaterials.RIFT_ESSENCE.get());
                        // 元素扩展材料（冰/雷/毒/暗影/光明/自然/虚空）
                        output.accept(QianxiangMaterials.FROST_CRYSTAL.get());
                        output.accept(QianxiangMaterials.THUNDER_STONE.get());
                        output.accept(QianxiangMaterials.VENOM_GLAND.get());
                        output.accept(QianxiangMaterials.SHADOW_DUST.get());
                        output.accept(QianxiangMaterials.HOLY_SHARD.get());
                        output.accept(QianxiangMaterials.NATURE_BREATH.get());
                        output.accept(QianxiangMaterials.VOID_SHARD.get());
                        output.accept(QianxiangItems.EMBER_CRYSTAL.get());
                        output.accept(QianxiangItems.BEAST_FANG.get());

                        // —— 3 把产物武器 ——
                        output.accept(QianxiangItems.EMBER_BLADE.get());
                        output.accept(QianxiangItems.PHASE_STAFF.get());
                        output.accept(QianxiangItems.BONE_BLADE.get());

                        // —— 功能性工具产物 ——
                        output.accept(QianxiangItems.PHASE_HOE.get());
                        output.accept(QianxiangItems.PHASE_WATERING_CAN.get());

                        // —— 自由法术载体 ——
                        output.accept(QianxiangItems.SPELL_BOOK.get());

                        // —— 防具产物（相盾 + 四件可穿戴护甲）——
                        output.accept(QianxiangItems.PHASE_SHIELD.get());
                        output.accept(QianxiangItems.PHASE_HELMET.get());
                        output.accept(QianxiangItems.PHASE_CHESTPLATE.get());
                        output.accept(QianxiangItems.PHASE_LEGGINGS.get());
                        output.accept(QianxiangItems.PHASE_BOOTS.get());

                        // —— 锻造台（相之凝结台）——
                        output.accept(QianxiangBlocks.FORGE_TABLE_ITEM.get());

                        // —— 万象森罗维度 MVP ——
                        output.accept(QianxiangItems.MYRIAD_FRAGMENT.get());
                        // —— 反转器材料 ——
                        output.accept(QianxiangItems.REVERSE_CORE.get());
                        output.accept(QianxiangBlocks.RIFT_STONE_ITEM.get());
                        output.accept(QianxiangBlocks.WILDLIGHT_GRASS_ITEM.get());
                        output.accept(QianxiangBlocks.GLIMMER_LOG_ITEM.get());
                        output.accept(QianxiangBlocks.GLIMMER_LEAVES_ITEM.get());
                        output.accept(QianxiangBlocks.VOID_ORE_ITEM.get());

                        // —— NPC 刷怪蛋 ——
                        output.accept(QianxiangItems.WANDERING_SAGE_SPAWN_EGG.get());
                        output.accept(QianxiangItems.ABYSS_MERCHANT_SPAWN_EGG.get());
                    })
                    .build());

    private QianxiangCreativeTab() {}
}
