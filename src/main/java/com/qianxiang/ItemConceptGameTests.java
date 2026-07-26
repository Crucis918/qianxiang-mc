package com.qianxiang;

import com.qianxiang.phase.ForgeComposer;
import com.qianxiang.phase.ItemConceptResolver;
import com.qianxiang.phase.Phase;
import com.qianxiang.phase.PhaseFunction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 全物品概念引擎（{@link ItemConceptResolver}）的游戏内测试。
 * <p>
 * 覆盖任务契约的测试用例：草方块→大地{生命/中和}、钻石剑→BASE_METAL+EDGE、
 * 苹果→HEAL+生命、下界岩→混沌；外加石质基底与「辅料」锻造行为。
 * <p>
 * 结构模板：{@code qianxiang:item_concept}（{@code @PrefixGameTestTemplate(false)}
 * 使模板名即结构名，不带类名前缀）。运行方式：开启
 * {@code neoforge.enabledGameTestNamespaces=qianxiang} 的 server run 已配好，
 * 加 {@code -Dneoforge.gameTestServer=true} 启动即自动跑测。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ItemConceptGameTests {

    private ItemConceptGameTests() {}

    /** 草方块 → phases={生命,中和}、微量 HEAL（大地滋养，辅料不当骨架）、概念「大地」。 */
    @GameTest(template = "item_concept")
    public static void grassBlockIsEarth(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.GRASS_BLOCK));
        helper.assertTrue(c.phases().contains(Phase.LIFE) && c.phases().contains(Phase.NEUTRAL),
                "grass_block 应为 {生命/中和}，实际 " + c.phases());
        helper.assertTrue(c.functions().contains(PhaseFunction.HEAL),
                "草方块应带微量 HEAL（大地滋养），实际 " + c.functions());
        helper.assertTrue(c.functions().stream().noneMatch(f -> f.name().startsWith("BASE_")),
                "草方块是辅料，不应有 BASE_* 骨架，实际 " + c.functions());
        helper.assertTrue(ItemConceptResolver.CONCEPT_EARTH.equals(c.conceptKey()),
                "草方块概念应为「大地」，实际 " + c.conceptKey());
        helper.succeed();
    }

    /** 钻石剑 → BASE_METAL + EDGE，概念「兵刃」。 */
    @GameTest(template = "item_concept")
    public static void diamondSwordIsBlade(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.DIAMOND_SWORD));
        helper.assertTrue(c.functions().contains(PhaseFunction.BASE_METAL),
                "钻石剑应含 BASE_METAL，实际 " + c.functions());
        helper.assertTrue(c.functions().contains(PhaseFunction.EDGE),
                "钻石剑应含 EDGE，实际 " + c.functions());
        helper.assertTrue(ItemConceptResolver.CONCEPT_BLADE.equals(c.conceptKey()),
                "钻石剑概念应为「兵刃」，实际 " + c.conceptKey());
        helper.succeed();
    }

    /** 苹果 → HEAL + 生命相，概念「滋养」。 */
    @GameTest(template = "item_concept")
    public static void appleIsNourish(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.APPLE));
        helper.assertTrue(c.functions().contains(PhaseFunction.HEAL),
                "苹果应含 HEAL，实际 " + c.functions());
        helper.assertTrue(c.phases().contains(Phase.LIFE),
                "苹果应含生命相，实际 " + c.phases());
        helper.assertTrue(ItemConceptResolver.CONCEPT_NOURISH.equals(c.conceptKey()),
                "苹果概念应为「滋养」，实际 " + c.conceptKey());
        helper.succeed();
    }

    /** 下界岩 → 混沌相，概念「狱焰」。 */
    @GameTest(template = "item_concept")
    public static void netherrackIsChaos(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.NETHERRACK));
        helper.assertTrue(c.phases().contains(Phase.CHAOS),
                "下界岩应含混沌相，实际 " + c.phases());
        helper.assertTrue(ItemConceptResolver.CONCEPT_NETHER.equals(c.conceptKey()),
                "下界岩概念应为「狱焰」，实际 " + c.conceptKey());
        helper.succeed();
    }

    /** 圆石 → 石质基底（BASE_METAL）+ DEFENSE（坚岩，萌新第一件防具）+ 沉潜/秩序相，概念「坚岩」。 */
    @GameTest(template = "item_concept")
    public static void cobblestoneIsStoneBase(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.COBBLESTONE));
        helper.assertTrue(c.functions().contains(PhaseFunction.BASE_METAL),
                "圆石应为石质基底 BASE_METAL，实际 " + c.functions());
        helper.assertTrue(c.functions().contains(PhaseFunction.DEFENSE),
                "圆石应带 DEFENSE（坚岩防具），实际 " + c.functions());
        helper.assertTrue(c.phases().contains(Phase.ABYSS) && c.phases().contains(Phase.ORDER),
                "圆石应为 {沉潜/秩序}，实际 " + c.phases());
        helper.assertTrue(ItemConceptResolver.CONCEPT_STONE.equals(c.conceptKey()),
                "圆石概念应为「坚岩」，实际 " + c.conceptKey());
        helper.succeed();
    }

    /**
     * 辅料锻造行为：纯辅料（草方块+鸡蛋，均无功能算子）→ 不可锻造；
     * 铁锭骨架 + 草方块辅料 → 可锻造，且辅料的相性（生命/中和）进入外观。
     * （无 BASE_* 但带功能算子的组合见 {@link #formlessBaseForging}——新政下走无相骨架兜底。）
     */
    @GameTest(template = "item_concept")
    public static void earthAdjunctForging(GameTestHelper helper) {
        // 新政策（去基底限制+萌新定义）：草方块带 HEAL 算子，是可锻造的辅料。
        // 真正「不可锻造」的只有完全无功能算子也无相性的组合——鸡蛋（仅 LIFE 相无算子）单独一个。
        var bad = ForgeComposer.compose(List.of(new ItemStack(Items.EGG)));
        helper.assertTrue(!bad.valid(), "无任何功能算子的单个物品（鸡蛋）不应出产物");

        var good = ForgeComposer.compose(List.of(
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GRASS_BLOCK)));
        helper.assertTrue(good.valid(), "铁锭骨架 + 草方块辅料应可锻造");
        var appearance = good.attributes().appearance();
        helper.assertTrue(appearance != null && appearance.dominantPhases().contains(Phase.LIFE),
                "草方块辅料的生命相应进入产物外观，实际 "
                        + (appearance == null ? "null" : appearance.dominantPhases()));
        helper.succeed();
    }

    // ============ 萌新保底：满地都是的东西也有用 ============

    /** 泥土 → 微量 HEAL（大地滋养），概念「大地」。 */
    @GameTest(template = "item_concept")
    public static void dirtHasNourishHeal(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.DIRT));
        helper.assertTrue(c.functions().contains(PhaseFunction.HEAL),
                "泥土应带微量 HEAL（大地滋养），实际 " + c.functions());
        helper.assertTrue(ItemConceptResolver.CONCEPT_EARTH.equals(c.conceptKey()),
                "泥土概念应为「大地」，实际 " + c.conceptKey());
        helper.succeed();
    }

    /** 沙子 → 时间相 + SLOW（流沙），概念「流沙」。 */
    @GameTest(template = "item_concept")
    public static void sandIsQuicksand(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.SAND));
        helper.assertTrue(c.phases().contains(Phase.TIME),
                "沙子应含时间相，实际 " + c.phases());
        helper.assertTrue(c.functions().contains(PhaseFunction.SLOW),
                "沙子应带 SLOW（流沙），实际 " + c.functions());
        helper.assertTrue(ItemConceptResolver.CONCEPT_SAND.equals(c.conceptKey()),
                "沙子概念应为「流沙」，实际 " + c.conceptKey());
        helper.succeed();
    }

    /** 羽毛 → 微弱 LEVITATION（轻盈），概念「轻盈」。 */
    @GameTest(template = "item_concept")
    public static void featherIsLightweight(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.FEATHER));
        helper.assertTrue(c.functions().contains(PhaseFunction.LEVITATION),
                "羽毛应带 LEVITATION（轻盈），实际 " + c.functions());
        helper.assertTrue(ItemConceptResolver.CONCEPT_LIGHTWEIGHT.equals(c.conceptKey()),
                "羽毛概念应为「轻盈」，实际 " + c.conceptKey());
        helper.succeed();
    }

    /** 腐肉 → 冲突相 + 微弱 POISON（反向利用，不再是食物 HEAL），概念「魔骸」。 */
    @GameTest(template = "item_concept")
    public static void rottenFleshIsPoison(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.ROTTEN_FLESH));
        helper.assertTrue(c.phases().contains(Phase.CONFLICT),
                "腐肉应含冲突相，实际 " + c.phases());
        helper.assertTrue(c.functions().contains(PhaseFunction.POISON),
                "腐肉应带微弱 POISON（反向利用），实际 " + c.functions());
        helper.assertTrue(!c.functions().contains(PhaseFunction.HEAL),
                "腐肉不应再走食物 HEAL 推导，实际 " + c.functions());
        helper.succeed();
    }

    /** 鸡蛋 → 生命相、无功能算子（辅料），概念「新生」。 */
    @GameTest(template = "item_concept")
    public static void eggIsNewborn(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.EGG));
        helper.assertTrue(c.phases().contains(Phase.LIFE),
                "鸡蛋应含生命相，实际 " + c.phases());
        helper.assertTrue(c.functions().isEmpty(),
                "鸡蛋是辅料，不应有功能算子，实际 " + c.functions());
        helper.assertTrue(ItemConceptResolver.CONCEPT_NEWBORN.equals(c.conceptKey()),
                "鸡蛋概念应为「新生」，实际 " + c.conceptKey());
        helper.succeed();
    }

    /** 花（蒲公英）→ 生命相 + GROWTH + 微光 REGENERATION，概念「生机」。 */
    @GameTest(template = "item_concept")
    public static void flowerHasRegeneration(GameTestHelper helper) {
        var c = ItemConceptResolver.resolve(new ItemStack(Items.DANDELION));
        helper.assertTrue(c.functions().contains(PhaseFunction.GROWTH)
                        && c.functions().contains(PhaseFunction.REGENERATION),
                "花应带 GROWTH + 微光 REGENERATION，实际 " + c.functions());
        helper.assertTrue(ItemConceptResolver.CONCEPT_PLANT.equals(c.conceptKey()),
                "花概念应为「生机」，实际 " + c.conceptKey());
        helper.succeed();
    }

    /**
     * 萌新保底锻造：原木骨架 + 泥土/圆石/种子 → 可锻造，
     * 且产物带疗伤等级（泥土 HEAL）与护甲（圆石 DEFENSE）——
     * 满地都是的东西也能拼出有基础效果的装备。
     */
    @GameTest(template = "item_concept")
    public static void newbieForge(GameTestHelper helper) {
        var c = ForgeComposer.compose(List.of(
                new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIRT),
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.WHEAT_SEEDS)));
        helper.assertTrue(c.valid(), "原木+泥土+圆石+种子应可锻造（萌新保底）");
        helper.assertTrue(c.attributes().healLevel() > 0,
                "泥土应贡献疗伤等级，实际 healLevel=" + c.attributes().healLevel());
        helper.assertTrue(c.attributes().armor() > 0,
                "圆石 DEFENSE 应贡献护甲，实际 armor=" + c.attributes().armor());
        helper.succeed();
    }

    /**
     * 无相骨架锻造行为：无 BASE_* 但带功能算子的组合不再判空——
     * 泥土（微量 HEAL）+ 草方块辅料 → 低耐久疗伤武器（虚拟基底 36 耐久）；
     * 单余烬石（IGNITE）→ 弱喷火武器；
     * 余烬石+蜘蛛眼（IGNITE+LIFESTEAL）→ 喷火吸血刀；
     * 纯骨粉（GROWTH）→ 相之水壶。
     */
    @GameTest(template = "item_concept")
    public static void formlessBaseForging(GameTestHelper helper) {
        // 泥土 HEAL + 草方块辅料（无基底）→ 无相骨架兜底，低耐久疗伤武器
        var earthOnly = ForgeComposer.compose(List.of(
                new ItemStack(Items.GRASS_BLOCK), new ItemStack(Items.DIRT)));
        helper.assertTrue(earthOnly.valid(), "泥土（HEAL）无基底也应可锻造（无相骨架兜底）");
        helper.assertTrue(earthOnly.attributes().durability() == 36,
                "无相骨架虚拟基底耐久应为 36，实际 " + earthOnly.attributes().durability());
        helper.assertTrue(earthOnly.attributes().healLevel() > 0,
                "泥土应贡献疗伤等级，实际 healLevel=" + earthOnly.attributes().healLevel());

        // 单个余烬石（IGNITE，无基底）→ 弱喷火武器
        var single = ForgeComposer.compose(List.of(new ItemStack(QianxiangItems.EMBER_CRYSTAL.get())));
        helper.assertTrue(single.valid(), "单余烬石（无基底）应可锻造出产物");
        helper.assertTrue(single.result().is(QianxiangItems.EMBER_BLADE.get()),
                "无基底攻击向组合应出灼烧之刃，实际 " + single.result().getItem());
        helper.assertTrue(single.attributes().durability() == 36,
                "无相骨架虚拟基底耐久应为 36，实际 " + single.attributes().durability());
        helper.assertTrue(single.attributes().igniteLevel() == 1,
                "余烬石应提供灼烧×1，实际 " + single.attributes().igniteLevel());

        // 余烬石+蜘蛛眼（IGNITE+LIFESTEAL，无基底）→ 喷火吸血刀
        var fireLeech = ForgeComposer.compose(List.of(
                new ItemStack(QianxiangItems.EMBER_CRYSTAL.get()), new ItemStack(Items.SPIDER_EYE)));
        helper.assertTrue(fireLeech.valid(), "余烬石+蜘蛛眼（无基底）应可锻造");
        helper.assertTrue(fireLeech.result().is(QianxiangItems.EMBER_BLADE.get()),
                "喷火吸血组合应出灼烧之刃，实际 " + fireLeech.result().getItem());
        helper.assertTrue(fireLeech.attributes().igniteLevel() >= 1
                        && fireLeech.attributes().lifestealLevel() >= 1,
                "应有灼烧+吸血，实际 ignite=" + fireLeech.attributes().igniteLevel()
                        + " lifesteal=" + fireLeech.attributes().lifestealLevel());

        // 纯骨粉（GROWTH，无基底）→ 相之水壶
        var can = ForgeComposer.compose(List.of(new ItemStack(Items.BONE_MEAL)));
        helper.assertTrue(can.valid(), "纯骨粉（GROWTH，无基底）应可锻造");
        helper.assertTrue(can.result().is(QianxiangItems.PHASE_WATERING_CAN.get()),
                "纯 GROWTH 组合应出相之水壶，实际 " + can.result().getItem());
        helper.succeed();
    }

    // ============ 杂项栏物品：书/地图/时钟/桶/蜡烛等也有具体概念 ============

    /** 普通杂货：书→智识「记载」、时钟→时间「计时」、桶→中和「容器」、蜡烛→火/圣「烛」、马鞍→秩序「驭具」。 */
    @GameTest(template = "item_concept")
    public static void miscCommonItemsHaveConcepts(GameTestHelper helper) {
        var book = ItemConceptResolver.resolve(new ItemStack(Items.BOOK));
        helper.assertTrue(book.phases().contains(Phase.KNOWLEDGE)
                        && ItemConceptResolver.CONCEPT_RECORD.equals(book.conceptKey()),
                "书应为智识相「记载」，实际 " + book.phases() + "/" + book.conceptKey());

        var clock = ItemConceptResolver.resolve(new ItemStack(Items.CLOCK));
        helper.assertTrue(clock.phases().contains(Phase.TIME)
                        && ItemConceptResolver.CONCEPT_TIMEKEEPING.equals(clock.conceptKey()),
                "时钟应为时间相「计时」，实际 " + clock.phases() + "/" + clock.conceptKey());

        var bucket = ItemConceptResolver.resolve(new ItemStack(Items.BUCKET));
        helper.assertTrue(bucket.phases().contains(Phase.NEUTRAL)
                        && ItemConceptResolver.CONCEPT_VESSEL.equals(bucket.conceptKey()),
                "桶应为中和相「容器」，实际 " + bucket.phases() + "/" + bucket.conceptKey());

        var candle = ItemConceptResolver.resolve(new ItemStack(Items.CANDLE));
        helper.assertTrue(candle.phases().contains(Phase.FIRE) && candle.phases().contains(Phase.LIGHT)
                        && ItemConceptResolver.CONCEPT_CANDLE.equals(candle.conceptKey()),
                "蜡烛应为火/圣相「烛」，实际 " + candle.phases() + "/" + candle.conceptKey());

        var saddle = ItemConceptResolver.resolve(new ItemStack(Items.SADDLE));
        helper.assertTrue(saddle.phases().contains(Phase.ORDER)
                        && ItemConceptResolver.CONCEPT_TACK.equals(saddle.conceptKey()),
                "马鞍应为秩序相「驭具」，实际 " + saddle.phases() + "/" + saddle.conceptKey());
        helper.succeed();
    }

    /** 高档杂项：海洋之心→沉潜/超脱+MANA+潮涌能量×2「海洋之心」；鞘翅→超脱+LEVITATION+缓降×2「翼」；
     *  龙息→混沌+MANA/IGNITE「龙息」。 */
    @GameTest(template = "item_concept")
    public static void miscHighTierItems(GameTestHelper helper) {
        var heart = ItemConceptResolver.resolve(new ItemStack(Items.HEART_OF_THE_SEA));
        helper.assertTrue(heart.phases().contains(Phase.ABYSS) && heart.phases().contains(Phase.TRANSCEND),
                "海洋之心应为沉潜/超脱相，实际 " + heart.phases());
        helper.assertTrue(heart.functions().contains(PhaseFunction.MANA),
                "海洋之心应带 MANA，实际 " + heart.functions());
        helper.assertTrue(heart.effects().getOrDefault(
                        ResourceLocation.withDefaultNamespace("conduit_power"), 0) >= 2,
                "海洋之心应带潮涌能量×2，实际 " + heart.effects());
        helper.assertTrue(ItemConceptResolver.CONCEPT_SEA_HEART.equals(heart.conceptKey()),
                "海洋之心概念应为「海洋之心」，实际 " + heart.conceptKey());

        var elytra = ItemConceptResolver.resolve(new ItemStack(Items.ELYTRA));
        helper.assertTrue(elytra.functions().contains(PhaseFunction.LEVITATION)
                        && elytra.effects().getOrDefault(
                        ResourceLocation.withDefaultNamespace("slow_falling"), 0) >= 2
                        && ItemConceptResolver.CONCEPT_WING.equals(elytra.conceptKey()),
                "鞘翅应为 LEVITATION+缓降×2「翼」，实际 " + elytra.functions() + "/"
                        + elytra.effects() + "/" + elytra.conceptKey());

        var breath = ItemConceptResolver.resolve(new ItemStack(Items.DRAGON_BREATH));
        helper.assertTrue(breath.phases().contains(Phase.CHAOS)
                        && breath.functions().contains(PhaseFunction.MANA)
                        && breath.functions().contains(PhaseFunction.IGNITE)
                        && ItemConceptResolver.CONCEPT_DRAGON_BREATH.equals(breath.conceptKey()),
                "龙息应为混沌相+MANA/IGNITE「龙息」，实际 " + breath.phases() + "/"
                        + breath.functions() + "/" + breath.conceptKey());
        helper.succeed();
    }

    /** 已有 tag 的杂项物品：概念变细但 tag 数据保持——回响碎片保留 AREA_HARVEST、
     *  潜影壳保留 LEVITATION、鹦鹉螺壳保留 conduit_power、蜂蜜瓶不再走食物「滋养」。 */
    @GameTest(template = "item_concept")
    public static void miscKeepsExplicitTags(GameTestHelper helper) {
        var echo = ItemConceptResolver.resolve(new ItemStack(Items.ECHO_SHARD));
        helper.assertTrue(echo.functions().contains(PhaseFunction.AREA_HARVEST),
                "回响碎片的 AREA_HARVEST tag 应保持，实际 " + echo.functions());
        helper.assertTrue(echo.phases().contains(Phase.CHAOS) && echo.phases().contains(Phase.KNOWLEDGE)
                        && ItemConceptResolver.CONCEPT_ECHO.equals(echo.conceptKey()),
                "回响碎片应为混沌/智识「回响」，实际 " + echo.phases() + "/" + echo.conceptKey());

        var shulker = ItemConceptResolver.resolve(new ItemStack(Items.SHULKER_SHELL));
        helper.assertTrue(shulker.functions().contains(PhaseFunction.LEVITATION)
                        && shulker.functions().contains(PhaseFunction.DEFENSE)
                        && ItemConceptResolver.CONCEPT_SHULKER.equals(shulker.conceptKey()),
                "潜影壳应保留 LEVITATION 并新增 DEFENSE，概念「潜影」，实际 "
                        + shulker.functions() + "/" + shulker.conceptKey());

        var nautilus = ItemConceptResolver.resolve(new ItemStack(Items.NAUTILUS_SHELL));
        helper.assertTrue(nautilus.functions().contains(PhaseFunction.DEFENSE)
                        && nautilus.effects().containsKey(ResourceLocation.withDefaultNamespace("conduit_power"))
                        && ItemConceptResolver.CONCEPT_SPIRAL_SHELL.equals(nautilus.conceptKey()),
                "鹦鹉螺壳应为 DEFENSE+潮涌能量「螺旋护壳」，实际 " + nautilus.functions() + "/"
                        + nautilus.effects() + "/" + nautilus.conceptKey());

        var honey = ItemConceptResolver.resolve(new ItemStack(Items.HONEY_BOTTLE));
        helper.assertTrue(honey.phases().contains(Phase.LIFE)
                        && honey.functions().contains(PhaseFunction.REGENERATION)
                        && !honey.functions().contains(PhaseFunction.HEAL)
                        && ItemConceptResolver.CONCEPT_HONEY.equals(honey.conceptKey()),
                "蜂蜜瓶应为生命相+REGENERATION「甘蜜」（不再走食物 HEAL），实际 " + honey.phases()
                        + "/" + honey.functions() + "/" + honey.conceptKey());
        helper.succeed();
    }

    /** 武器属性写入验证：余烬石(灼烧)+雪球(冻伤)+铁锭(骨架) → 产物必须有 igniteLevel 和 frost。 */
    @GameTest(template = "item_concept")
    public static void weaponHasIgniteAndFrost(GameTestHelper helper) {
        var result = ForgeComposer.compose(List.of(
                new ItemStack(QianxiangItems.EMBER_CRYSTAL.get()),
                new ItemStack(Items.SNOWBALL),
                new ItemStack(Items.IRON_INGOT)));
        helper.assertTrue(result.valid(), "余烬石+雪球+铁锭应可锻造出武器");
        var attr = result.attributes();
        helper.assertTrue(attr != null, "产物应有 ComposedAttributes");
        helper.assertTrue(attr.igniteLevel() > 0,
                "余烬石应给产物点燃等级>0，实际 igniteLevel=" + attr.igniteLevel());
        helper.assertTrue(attr.effects() != null && attr.effects().frost() > 0,
                "雪球应给产物冻结等级>0，实际 effects=" + (attr.effects() == null ? "null" : "frost=" + attr.effects().frost()));
        // 产物必须是千相武器（CombatEffectHandler 只认 QianxiangWeaponItem）
        helper.assertTrue(result.result().getItem() instanceof com.qianxiang.item.QianxiangWeaponItem,
                "产物必须是 QianxiangWeaponItem 才能触发战斗特效，实际 " + result.result().getItem());
        helper.succeed();
    }
}
