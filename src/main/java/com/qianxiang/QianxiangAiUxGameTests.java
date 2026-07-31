package com.qianxiang;

import com.qianxiang.ai.MovesetComposer;
import com.qianxiang.combat.AnimationLibrary;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.AiPlaceMaterialsHandler;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * AI 体验三件事：动作编排（AI 校验 + 关键词兜底）、口语关键词、自动备料来源拆分。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangAiUxGameTests {

    private QianxiangAiUxGameTests() {}

    /** 兜底「三连快斩」→ ≥3 段且含 speed≥2 动画；无关键词输入也必出默认三段（不空）。 */
    @GameTest(template = "item_concept")
    public static void fallbackMovesetThreeFast(GameTestHelper helper) {
        var segs = MovesetComposer.fallbackSegments("三连快斩");
        helper.assertTrue(segs.size() >= 3,
                "三连快斩应 ≥3 段，实际 " + segs.size());
        helper.assertTrue(segs.stream().anyMatch(a -> a.speed() >= 2),
                "快斩应含 speed≥2 动画");
        var dflt = MovesetComposer.fallbackSegments("随便挥挥");
        helper.assertTrue(dflt.size() == 3,
                "无关键词应落默认三段剑连击，实际 " + dflt.size());
        helper.succeed();
    }

    /** 兜底「重劈」→ 含 power≥3 动画；「突进然后横扫」首段是 DASH（顺序语义）。 */
    @GameTest(template = "item_concept")
    public static void fallbackMovesetHeavy(GameTestHelper helper) {
        var heavy = MovesetComposer.fallbackSegments("重劈");
        helper.assertTrue(heavy.stream().anyMatch(a -> a.power() >= 3),
                "重劈应含 power≥3 动画");
        var seq = MovesetComposer.fallbackSegments("突进然后横扫");
        helper.assertTrue(!seq.isEmpty()
                        && seq.get(0).type() == AnimationLibrary.AnimType.DASH,
                "突进开头首段应为 DASH，实际 " + (seq.isEmpty() ? "空" : seq.get(0).type()));
        helper.succeed();
    }

    /** AI JSON 校验路径（与提案同款）：合法库内动画通过；库外动画/非法 category/非 JSON 全拒。 */
    @GameTest(template = "item_concept")
    public static void aiMovesetJsonValidation(GameTestHelper helper) {
        String valid = "{\"category\":\"sword\",\"combos\":[\"epicfight:biped/combat/sword_auto1\","
                + "\"epicfight:biped/combat/sword_auto2\"],\"collider\":\"sword\"}";
        String ok = MovesetComposer.validate(valid);
        helper.assertTrue(ok != null && ok.contains("sword_auto1"),
                "合法库内动画应通过校验，实际 " + ok);

        helper.assertTrue(MovesetComposer.validate(
                        "{\"category\":\"sword\",\"combos\":[\"minecraft:stone_sword\"]}") == null,
                "非 EF 命名空间动画应被剔除到空（拒）；注：既有校验只查 namespace+prefix+category，"
                        + "epicfight 前缀内未登记 id 按现状放行");
        helper.assertTrue(MovesetComposer.validate(
                        "{\"category\":\"banana\",\"combos\":[\"epicfight:biped/combat/sword_auto1\"]}") == null,
                "非法 category 应拒");
        helper.assertTrue(MovesetComposer.validate("这不是 JSON") == null,
                "非 JSON 应拒");
        helper.succeed();
    }

    /** 自动备料来源拆分：背包 1 + 箱子 2 → fromInventory==1、fromStorage==2、missing==0。 */
    @GameTest(template = "item_concept")
    public static void placeMaterialsSourceBreakdown(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos tablePos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        net.minecraft.core.BlockPos chestPos = helper.absolutePos(new net.minecraft.core.BlockPos(3, 1, 2));
        level.setBlockAndUpdate(tablePos, QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        level.setBlockAndUpdate(chestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        if (!(level.getBlockEntity(tablePos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)
                || !(level.getBlockEntity(chestPos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)) {
            helper.fail("锻造台/箱子方块实体应存在");
            return;
        }
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 1));

        var result = AiPlaceMaterialsHandler.placeMaterials(player, be,
                ForgeTableMenu.SLOT_FILL_ORDER,
                List.of(Items.IRON_INGOT, Items.IRON_INGOT, Items.IRON_INGOT), false);
        helper.assertTrue(result.placedCount() == 3,
                "应放入 3 个铁锭，实际 " + result.placedCount());
        helper.assertTrue(result.fromInventory() == 1,
                "背包来源应恰为 1，实际 " + result.fromInventory());
        helper.assertTrue(result.fromStorage() == 2,
                "存储来源应恰为 2，实际 " + result.fromStorage());
        helper.assertTrue(result.missing().isEmpty(),
                "不应有缺料，实际 " + result.missing());
        level.removeBlock(tablePos, false);
        level.removeBlock(chestPos, false);
        helper.succeed();
    }

    /** 口语关键词：「猛」比「随便」多选出攻击向材料（正向增量 + 反向基线）。 */
    @GameTest(template = "item_concept")
    public static void colloquialKeywords(GameTestHelper helper) {
        var fierce = com.qianxiang.ai.FallbackRecipes.keywordPicksForTest("整把猛一点的刀");
        var plain = com.qianxiang.ai.FallbackRecipes.keywordPicksForTest("随便一把刀");
        helper.assertTrue(!fierce.isEmpty(), "「猛」应选出材料");
        helper.assertTrue(fierce.stream().anyMatch(m -> !plain.contains(m)),
                "「猛」应比「随便」多出攻击向材料（口语增量），fierce=" + fierce + " plain=" + plain);
        helper.assertTrue(!com.qianxiang.ai.FallbackRecipes.keywordPicksForTest("想要快速一点的剑").isEmpty(),
                "「快速」应选出攻速向材料");
        helper.succeed();
    }
}
