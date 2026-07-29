package com.qianxiang;

import com.qianxiang.compat.RefinedStorageCompat;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * compat/ 修复批次的回归测试——当前覆盖 RS2 组件级匹配谓词
 * {@link RefinedStorageCompat.ItemMatch}。
 * <p>
 * dev 环境未装 Refined Storage，无法对真实 RS 网络做运行时测试
 * （{@code RefinedStorageCompat} 的 RS 类型一碰就 NoClassDefFoundError），
 * 因此匹配逻辑抽成不引用任何 RS 类型的纯谓词在此单测；
 * 网络侧 count/extract 的实机验证步骤见 {@link RefinedStorageCompat} 类 javadoc。
 * </p>
 * <p>规矩（同 MiscFix 批次）：断言可观测语义，单向否定断言必配正向断言。</p>
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangCompatFixGameTests {

    private QianxiangCompatFixGameTests() {}

    /**
     * 核心回归：带组件（phase_data 之类）的千相材料存进 RS 后是带组件条目，
     * 白板查询（自动取料的实际形态）必须能匹配到它——旧实现裸 key 查询直接漏掉。
     * 正向配套：同 Item 的白板条目同样匹配；否定配套：不同 Item 必须拒绝。
     */
    @GameTest(template = "item_concept")
    public static void rsBlankQueryAcceptsAnySameItemEntry(GameTestHelper helper) {
        ItemStack query = new ItemStack(QianxiangItems.EMBER_CRYSTAL.get());

        ItemStack blankEntry = new ItemStack(QianxiangItems.EMBER_CRYSTAL.get());
        helper.assertTrue(
                RefinedStorageCompat.ItemMatch.matches(
                        query, blankEntry.getItem(), blankEntry.getComponentsPatch()),
                "白板查询必须匹配同 Item 的白板条目");

        ItemStack componentEntry = new ItemStack(QianxiangItems.EMBER_CRYSTAL.get());
        componentEntry.set(DataComponents.CUSTOM_NAME, Component.literal("相性材料"));
        helper.assertTrue(!componentEntry.getComponentsPatch().isEmpty(),
                "测试前提：带组件条目的 patch 必须非空");
        helper.assertTrue(
                RefinedStorageCompat.ItemMatch.matches(
                        query, componentEntry.getItem(), componentEntry.getComponentsPatch()),
                "白板查询必须匹配同 Item 的带组件条目（旧裸 key 实现的漏匹配回归）");

        ItemStack otherItem = new ItemStack(Items.BONE);
        helper.assertTrue(
                !RefinedStorageCompat.ItemMatch.matches(
                        query, otherItem.getItem(), otherItem.getComponentsPatch()),
                "不同 Item 的条目必须拒绝");
        helper.succeed();
    }

    /**
     * 带组件查询的覆盖语义：组件精确相等才匹配——白板条目与同 Item 异组件条目都必须拒绝，
     * 相同组件条目必须接受（正向配套）。
     */
    @GameTest(template = "item_concept")
    public static void rsComponentQueryRequiresExactComponents(GameTestHelper helper) {
        ItemStack query = new ItemStack(QianxiangItems.EMBER_CRYSTAL.get());
        query.set(DataComponents.CUSTOM_NAME, Component.literal("火相"));

        ItemStack same = new ItemStack(QianxiangItems.EMBER_CRYSTAL.get());
        same.set(DataComponents.CUSTOM_NAME, Component.literal("火相"));
        helper.assertTrue(
                RefinedStorageCompat.ItemMatch.matches(
                        query, same.getItem(), same.getComponentsPatch()),
                "组件精确相等的同 Item 条目必须匹配");

        ItemStack blankEntry = new ItemStack(QianxiangItems.EMBER_CRYSTAL.get());
        helper.assertTrue(
                !RefinedStorageCompat.ItemMatch.matches(
                        query, blankEntry.getItem(), blankEntry.getComponentsPatch()),
                "带组件查询不得匹配白板条目（条目组件未覆盖查询所需）");

        ItemStack different = new ItemStack(QianxiangItems.EMBER_CRYSTAL.get());
        different.set(DataComponents.CUSTOM_NAME, Component.literal("风相"));
        helper.assertTrue(
                !RefinedStorageCompat.ItemMatch.matches(
                        query, different.getItem(), different.getComponentsPatch()),
                "带组件查询不得匹配同 Item 异组件条目");

        helper.assertTrue(
                !RefinedStorageCompat.ItemMatch.matches(
                        query, Items.BONE, DataComponentPatch.EMPTY),
                "不同 Item 即使组件为空也必须拒绝");
        helper.succeed();
    }
}
