package com.qianxiang;

import com.qianxiang.ai.FallbackRecipes;
import com.qianxiang.ai.MaterialLibrary;
import com.qianxiang.ai.PhaseAIRecipeService;
import com.qianxiang.handler.MyriadWildsPortalHandler;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.phase.ForgeComposer;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * WQ-72 / WQ-69 / WQ-70 返工批次的回归测试。
 * <p>规矩（来自工单复核报告）：一律断言<b>可观测的最终状态</b>——propose3 的最终方案、
 * 世界方块、成就进度；不断言内部判据函数；单向否定断言必配「该在的东西还在」的正向断言。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangMiscFixGameTests {

    private QianxiangMiscFixGameTests() {}

    // ============================ WQ-72① 档位塌缩 ============================

    /**
     * weapon+LEGENDARY 的严格档位方案必须全员 LEGENDARY（平均档自然 == LEGENDARY）；
     * weapon+COMMON 必须全员 COMMON（「普通武器不给 RARE 材料」）。
     * 旧实现 16 格里 8 格 base==effect 撞同一材料，weapon LEGENDARY 落 defaultFillers
     * （RARE 烬铁 + COMMON 兽牙），平均档只有 RARE。
     */
    @GameTest(template = "item_concept")
    public static void fallbackExactTierMatchesTargetTier(GameTestHelper helper) {
        List<String> legendary = exactTierProposal(
                FallbackRecipes.propose3("我要一把趁手的武器", "weapon", "legendary"));
        helper.assertTrue(legendary != null && legendary.size() >= 2,
                "weapon+LEGENDARY 应有严格档位方案且不少于 2 件材料，实际 " + legendary);
        assertAllTier(helper, legendary, PhaseTier.LEGENDARY, "weapon+LEGENDARY");

        List<String> common = exactTierProposal(
                FallbackRecipes.propose3("我要一把普通武器", "weapon", "common"));
        helper.assertTrue(common != null && common.size() >= 2,
                "weapon+COMMON 应有严格档位方案且不少于 2 件材料，实际 " + common);
        assertAllTier(helper, common, PhaseTier.COMMON, "weapon+COMMON（普通武器不得给 RARE 材料）");
        helper.succeed();
    }

    // ============================ WQ-72②④ 否定语义直达最终方案 ============================

    /**
     * 「不要火的剑」：propose3 的<b>每一个</b>方案都不得含烬系材料（玩家可见层面），
     * 且形状词「剑」的材料命中必须存活（正向断言：beast_fang/dragon_bone 在方案里）。
     */
    @GameTest(template = "item_concept")
    public static void fallbackNegationReachesAllProposals(GameTestHelper helper) {
        var result = FallbackRecipes.propose3("不要火的剑", "weapon", "rare");
        helper.assertTrue(!result.proposals().isEmpty(), "否定需求也应给出可用方案");
        for (var p : result.proposals()) {
            helper.assertTrue(p.materialNames().stream().noneMatch(m -> m.contains("ember")),
                    "「不要火的剑」所有方案都不得含烬系材料，实际 " + p.materialNames());
        }
        boolean shapeAlive = result.proposals().stream().anyMatch(p ->
                p.materialNames().contains("qianxiang:beast_fang")
                        || p.materialNames().contains("qianxiang:dragon_bone"));
        helper.assertTrue(shapeAlive,
                "形状词「剑」的材料命中（beast_fang/dragon_bone）必须存活，实际方案 "
                        + result.proposals().stream().map(PhaseAIRecipeService.RecipeProposal::materialNames).toList());
        helper.succeed();
    }

    /**
     * 「免疫火焰的靴子」：免疫不是否定词——抗火需求必须被听懂（magma_cream 进方案），
     * 且方案完整（≥2 件材料，证明需求没被剥成空壳）。
     */
    @GameTest(template = "item_concept")
    public static void fallbackImmunityIsNotStrippedAsNegation(GameTestHelper helper) {
        var result = FallbackRecipes.propose3("免疫火焰的靴子", "armor", "rare");
        helper.assertTrue(!result.proposals().isEmpty(), "免疫需求应给出可用方案");
        List<String> first = result.proposals().getFirst().materialNames();
        helper.assertTrue(first.contains("minecraft:magma_cream"),
                "「免疫火焰」是 FIRE_RESIST 功能需求，应命中抗火材料 magma_cream，实际 " + first);
        helper.assertTrue(first.size() >= 2,
                "方案不得退化成空壳（旧实现把「免疫火焰」当否定剥成「的靴子」），实际 " + first);
        helper.succeed();
    }

    /**
     * 「去除诅咒的剑」：去除不是否定词——净化诉求映射圣辉碎片（holy_shard），
     * 且不得被裸「诅咒」词组当成「全负面」塞 debuff 材料。
     */
    @GameTest(template = "item_concept")
    public static void fallbackCurseRemovalReadsAsCleanse(GameTestHelper helper) {
        var result = FallbackRecipes.propose3("去除诅咒的剑", "weapon", "rare");
        helper.assertTrue(!result.proposals().isEmpty(), "净化需求应给出可用方案");
        List<String> first = result.proposals().getFirst().materialNames();
        helper.assertTrue(first.contains("qianxiang:holy_shard"),
                "「去除诅咒」应读作净化诉求（圣辉碎片），实际 " + first);
        helper.assertTrue(first.stream().noneMatch(m -> m.contains("wither_rose")
                        || m.contains("fermented_spider_eye") || m.contains("spider_eye")),
                "「去除诅咒」不应被当成「全负面」塞 debuff 材料，实际 " + first);
        // 正向断言：形状词「剑」的命中同样在方案里
        helper.assertTrue(first.contains("qianxiang:beast_fang") || first.contains("qianxiang:dragon_bone"),
                "形状词「剑」的材料命中必须存活，实际 " + first);
        helper.succeed();
    }

    // ============================ WQ-72③ 短英文词边界 ============================

    /**
     * "a nice sword"：短英文词按词边界匹配——"ice" 不得被 "nice" 误命中。
     * <p>误命中的可观测特征是「nice 这个词让最终方案<b>多出</b>冰霜材料」——
     * 与基线 "a sword" 对比，多出的冰霜材料必须为空。
     * 注意口径：frost_crystal 经 (weapon,RARE) 档位池作为合法效果料进入方案是正常行为
     * （与输入里有没有 "nice" 无关），不算 ice 误命中——断言「任何方案都不含 frost」
     * 会把档位池的正常选择误判进来（本测试第一版因此误红）。
     * 正向断言：形状词 "sword" 的命中（beast_fang/dragon_bone）必须存活于最终方案。
     */
    @GameTest(template = "item_concept")
    public static void fallbackShortEnglishWordNeedsBoundary(GameTestHelper helper) {
        var withNice = FallbackRecipes.propose3("a nice sword", "weapon", "rare");
        var baseline = FallbackRecipes.propose3("a sword", "weapon", "rare");
        helper.assertTrue(!withNice.proposals().isEmpty(), "应给出可用方案");

        List<String> extraFrost = new ArrayList<>(frostMaterials(withNice));
        extraFrost.removeAll(frostMaterials(baseline));
        helper.assertTrue(extraFrost.isEmpty(),
                "\"nice\" 不得额外引入冰霜材料（ice ⊂ nice 的误命中），多出的：" + extraFrost);

        boolean shapeAlive = withNice.proposals().stream().anyMatch(p ->
                p.materialNames().contains("qianxiang:beast_fang")
                        || p.materialNames().contains("qianxiang:dragon_bone"));
        helper.assertTrue(shapeAlive, "\"sword\" 的材料命中必须存活于最终方案，实际 "
                + withNice.proposals().stream().map(PhaseAIRecipeService.RecipeProposal::materialNames).toList());
        helper.succeed();
    }

    /** 结果所有方案里出现过的冰霜系材料（去重）——ice 误命中路径会塞进 frost_crystal/snowball/ice。 */
    private static List<String> frostMaterials(PhaseAIRecipeService.RecipeResult result) {
        return result.proposals().stream()
                .flatMap(p -> p.materialNames().stream())
                .filter(m -> m.contains("frost") || m.contains("snowball") || m.equals("minecraft:ice"))
                .distinct()
                .toList();
    }

    // ============================ WQ-69 传送门落点 ============================

    /**
     * 全空气柱（虚空上方）去程：不得把玩家埋到世界最底层——
     * 应在参考高度铺黑曜石基座。（GameTest 世界是 FLAT 预设，先挖掉地基柱人为制造空柱。）
     */
    @GameTest(template = "item_concept")
    public static void portalVoidColumnLandsOnPlatformNotWorldFloor(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos col = helper.absolutePos(new BlockPos(15, 0, 15));
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, col);
        for (int y = level.getMinBuildHeight(); y < surface.getY(); y++) {
            level.setBlockAndUpdate(new BlockPos(col.getX(), y, col.getZ()), Blocks.AIR.defaultBlockState());
        }
        BlockPos ref = new BlockPos(col.getX(), surface.getY() + 20, col.getZ());

        BlockPos arrival = MyriadWildsPortalHandler.findArrivalPosForTest(level, ref);
        helper.assertTrue(arrival.getY() > level.getMinBuildHeight() + 8,
                "虚空柱去程不得把玩家埋到世界最底层，实际 y=" + arrival.getY()
                        + "（世界最低 " + level.getMinBuildHeight() + "）");
        helper.assertTrue(Math.abs(arrival.getY() - ref.getY()) <= 1,
                "落点应锚定在参考高度附近，实际 y=" + arrival.getY() + "（参考 " + ref.getY() + "）");
        helper.assertTrue(level.getBlockState(arrival.below()).is(Blocks.OBSIDIAN),
                "虚空柱应在参考高度铺黑曜石基座，实际脚下 " + level.getBlockState(arrival.below()));
        helper.succeed();
    }

    /**
     * 树冠可站立：落点应直接落在树叶上（与原版一致），
     * 不得在树冠上方凭空生成悬空黑曜石。
     */
    @GameTest(template = "item_concept")
    public static void portalTreeCanopyIsStandable(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos col = helper.absolutePos(new BlockPos(20, 0, 20));
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, col);
        for (int i = 0; i < 3; i++) {
            level.setBlockAndUpdate(ground.above(i), Blocks.OAK_LOG.defaultBlockState());
        }
        BlockPos leaves = ground.above(3);
        level.setBlockAndUpdate(leaves, Blocks.OAK_LEAVES.defaultBlockState());

        BlockPos arrival = MyriadWildsPortalHandler.findArrivalPosForTest(level, col);
        helper.assertTrue(arrival.getY() == leaves.getY() + 1,
                "落点应直接站在树冠上，实际 y=" + arrival.getY() + "（树叶顶 " + leaves.getY() + "）");
        helper.assertTrue(level.getBlockState(arrival.below()).is(Blocks.OAK_LEAVES),
                "落点脚下应是树叶，实际 " + level.getBlockState(arrival.below()));
        for (int y = leaves.getY() + 1; y <= leaves.getY() + 4; y++) {
            helper.assertTrue(!level.getBlockState(new BlockPos(col.getX(), y, col.getZ())).is(Blocks.OBSIDIAN),
                    "树冠上方不得生成悬空黑曜石（y=" + y + "）");
        }
        helper.succeed();
    }

    /**
     * 回程（mayBuildPlatform=false）绝不改地形：虚空柱参考柱全列必须保持全空气；
     * 且回退落点（世界出生点附近）脚下必须是非流体实心地面（出生点回退也过 isStandable）。
     */
    @GameTest(template = "item_concept")
    public static void portalReturnTripNeverModifiesTerrain(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos col = helper.absolutePos(new BlockPos(25, 0, 25));
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, col);
        for (int y = level.getMinBuildHeight(); y < surface.getY(); y++) {
            level.setBlockAndUpdate(new BlockPos(col.getX(), y, col.getZ()), Blocks.AIR.defaultBlockState());
        }
        BlockPos ref = new BlockPos(col.getX(), surface.getY() + 20, col.getZ());

        BlockPos arrival = MyriadWildsPortalHandler.findReturnPosForTest(level, ref);
        helper.assertTrue(arrival != null, "回程落点解析不应返回 null");
        var ground = level.getBlockState(arrival.below());
        helper.assertTrue(!ground.isAir() && ground.getFluidState().isEmpty(),
                "回程回退落点脚下应为非流体实心地面，实际 " + ground + "（落点 " + arrival + "）");
        for (int y = level.getMinBuildHeight(); y <= ref.getY() + 30; y++) {
            var state = level.getBlockState(new BlockPos(col.getX(), y, col.getZ()));
            helper.assertTrue(state.isAir(),
                    "回程不得在参考柱放置任何方块（y=" + y + "：" + state + "）");
        }
        helper.succeed();
    }

    // ============================ WQ-70① forge_legendary 判据 ============================

    /**
     * forge_legendary：核 + 普通底料（平均档 EPIC，powerScore 过旧阈值）<b>不得</b>授予；
     * 核 + 传奇材料（平均档 LEGENDARY）<b>必须</b>授予——文案「锻造出一件传奇相器」。
     * 旧判据（powerScore>=12）对两者恒真，形同虚设。
     */
    @GameTest(template = "item_concept")
    public static void forgeLegendaryRequiresLegendaryGrade(GameTestHelper helper) {
        var weakPlayer = QianxiangCoreGameTests.mockServerPlayer(helper);
        SimpleContainer weakTable = tableWith(
                new ItemStack(QianxiangItems.WARDEN_CORE.get()),
                new ItemStack(QianxiangMaterials.EMBER_IRON.get()));
        ForgeComposer.Composition weak = ForgeComposer.compose(materialList(weakTable));
        helper.assertTrue(weak.valid() && !weak.result().isEmpty(), "核+底料应能锻出产物");
        helper.assertTrue(weak.attributes().powerScore() >= 12.0,
                "前置：该产物 powerScore 应过旧阈值 12（否则测不出新判据的作用），实际 "
                        + weak.attributes().powerScore());
        ForgeTableMenu.afterTakeResult(weakPlayer, weak.result(), weakTable, () -> {});
        helper.assertTrue(!forgeLegendaryDone(weakPlayer),
                "核+普通底料（平均档 EPIC）不得授予 forge_legendary");

        var heroPlayer = QianxiangCoreGameTests.mockServerPlayer(helper);
        SimpleContainer heroTable = tableWith(
                new ItemStack(QianxiangItems.WARDEN_CORE.get()),
                new ItemStack(QianxiangMaterials.VOID_SHARD.get()));
        ForgeComposer.Composition hero = ForgeComposer.compose(materialList(heroTable));
        helper.assertTrue(hero.valid() && !hero.result().isEmpty(), "核+传奇料应能锻出产物");
        ForgeTableMenu.afterTakeResult(heroPlayer, hero.result(), heroTable, () -> {});
        helper.assertTrue(forgeLegendaryDone(heroPlayer),
                "核+传奇材料锻出的传奇产物应授予 forge_legendary（正向断言）");
        helper.succeed();
    }

    // ============================ 工具 ============================

    /** 取 propose3 结果中的「严格档位」方案（summary key = tier_exact）。 */
    private static List<String> exactTierProposal(PhaseAIRecipeService.RecipeResult result) {
        for (var p : result.proposals()) {
            if ("qianxiang.forge_table.summary.fallback.tier_exact".equals(p.summary())) {
                return p.materialNames();
            }
        }
        return null;
    }

    /** 断言方案里每件材料在材料库中的档位都等于期望值（用户可见材料清单即是最终状态）。 */
    private static void assertAllTier(GameTestHelper helper, List<String> materials, PhaseTier expected, String label) {
        for (String m : materials) {
            var entry = MaterialLibrary.find(m);
            helper.assertTrue(entry.isPresent(), label + "：材料应在材料库中 " + m);
            helper.assertTrue(entry.get().tier() == expected,
                    label + "：方案应全员 " + expected + "，但 " + m + " 是 "
                            + entry.get().tier() + "（方案 " + materials + "）");
        }
    }

    private static SimpleContainer tableWith(ItemStack... stacks) {
        SimpleContainer c = new SimpleContainer(ForgeTableMenu.MATERIAL_SLOTS + 1);
        for (int i = 0; i < stacks.length; i++) {
            c.setItem(i, stacks[i]);
        }
        return c;
    }

    private static List<ItemStack> materialList(SimpleContainer c) {
        List<ItemStack> list = new ArrayList<>(ForgeTableMenu.MATERIAL_SLOTS);
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            list.add(c.getItem(i));
        }
        return list;
    }

    private static boolean forgeLegendaryDone(net.minecraft.server.level.ServerPlayer player) {
        AdvancementHolder holder = player.server.getAdvancements().get(
                ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, QianxiangAdvancements.FORGE_LEGENDARY));
        if (holder == null) {
            throw new IllegalStateException("forge_legendary 成就未注册");
        }
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    // ============================ 恢复命令：learn 核心路径 ============================

    /**
     * /qianxiang learn 核心（{@code QianxiangRestoreCommand.learnPreset}）：
     * 预置 id 学入已学列表（幂等）、非法 id 拒绝且列表不变、all 学全部预置。
     * 断言全部落在可观测最终状态（已学列表），命令层只是薄壳。
     */
    @GameTest(template = "item_concept")
    public static void learnCommandCore(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        var attachment = com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA;
        var fireball = ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "fireball");

        // ① 预置 id：学入已学列表
        int ok = com.qianxiang.command.QianxiangRestoreCommand.learnPreset(player, "qianxiang:fireball");
        helper.assertTrue(ok == 1, "预置 id 应学习成功（返回 1），实际 " + ok);
        helper.assertTrue(player.getData(attachment).hasLearned(fireball),
                "已学列表应含 qianxiang:fireball");
        // ② 幂等：再学一次仍返回 1，列表不膨胀
        int again = com.qianxiang.command.QianxiangRestoreCommand.learnPreset(player, "qianxiang:fireball");
        helper.assertTrue(again == 1, "重复学习应幂等（返回 1），实际 " + again);
        helper.assertTrue(player.getData(attachment).learnedSpells().size() == 1,
                "重复学习后已学列表应仍只有 1 条，实际 "
                        + player.getData(attachment).learnedSpells().size());
        // ③ 非法 id：拒绝（返回 0），已学列表不变
        int bad = com.qianxiang.command.QianxiangRestoreCommand.learnPreset(player, "qianxiang:no_such_spell");
        helper.assertTrue(bad == 0, "非法 id 应拒绝（返回 0），实际 " + bad);
        helper.assertTrue(player.getData(attachment).learnedSpells().size() == 1,
                "非法 id 不得改变已学列表");
        // ④ all：学全部预置（已会的 fireball 不计入新学数量）
        int all = com.qianxiang.command.QianxiangRestoreCommand.learnPreset(player, "all");
        int presetCount = com.qianxiang.spell.CustomSpell.registry().size();
        helper.assertTrue(all == presetCount - 1,
                "all 应新学 " + (presetCount - 1) + " 个（扣除已会的 fireball），实际 " + all);
        helper.assertTrue(player.getData(attachment).learnedSpells().size() == presetCount,
                "all 之后已学列表应等于预置总数 " + presetCount + "，实际 "
                        + player.getData(attachment).learnedSpells().size());
        helper.succeed();
    }
}
