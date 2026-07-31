package com.qianxiang;

import com.qianxiang.phase.ItemConceptResolver;
import com.qianxiang.phase.Phase;
import com.qianxiang.phase.PhaseFunction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.Set;

/**
 * 属性提取准确性审计矩阵（{@link ItemConceptResolver#resolve} 完整入口）。
 * <p>
 * 实机反馈「很多物品放进锻造台，提取出来的属性不对」——本类把常见原版物品的
 * 预期解析结果固化成正反双向断言：每个物品断言功能算子集合<b>包含</b>预期、
 * 且<b>不含</b>明显错误的算子。修规则时先改这里预期再改代码的情况不允许出现：
 * 预期表按常识写死（铁剑→BASE_METAL+EDGE、钻镐→BASE_METAL+AREA_HARVEST、
 * 金系→BASE_METAL+MANA、皮革→BASE_HIDE、盾牌→DEFENSE…）。
 * <p>
 * 模板复用 {@code qianxiang:item_concept} 空场地（纯逻辑断言）。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangConceptAuditGameTests {

    private QianxiangConceptAuditGameTests() {}

    private static Set<PhaseFunction> fns(PhaseFunction... fs) {
        Set<PhaseFunction> set = EnumSet.noneOf(PhaseFunction.class);
        for (PhaseFunction f : fs) set.add(f);
        return set;
    }

    /** 正向+反向断言：item 解析出的功能算子必须包含 mustHave 全部、不得含 mustNotHave 任一。 */
    private static void expect(GameTestHelper helper, Item item,
                               Set<PhaseFunction> mustHave, Set<PhaseFunction> mustNotHave) {
        var c = ItemConceptResolver.resolve(new ItemStack(item));
        for (PhaseFunction f : mustHave) {
            helper.assertTrue(c.functions().contains(f),
                    item + " 应含 " + f + "，实际 " + c.functions() + "（概念 " + c.conceptKey() + "）");
        }
        for (PhaseFunction f : mustNotHave) {
            helper.assertTrue(!c.functions().contains(f),
                    item + " 不应含 " + f + "，实际 " + c.functions() + "（概念 " + c.conceptKey() + "）");
        }
    }

    /** 刀剑类：铁剑→BASE_METAL+EDGE（不给 DEFENSE）；木剑→BASE_WOOD+EDGE（不给 BASE_METAL）。 */
    @GameTest(template = "item_concept")
    public static void swordsAreBlades(GameTestHelper helper) {
        expect(helper, Items.IRON_SWORD,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.EDGE),
                fns(PhaseFunction.DEFENSE, PhaseFunction.HEAL, PhaseFunction.AREA_HARVEST));
        expect(helper, Items.DIAMOND_SWORD,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.EDGE),
                fns(PhaseFunction.BASE_WOOD));
        expect(helper, Items.WOODEN_SWORD,
                fns(PhaseFunction.BASE_WOOD, PhaseFunction.EDGE),
                fns(PhaseFunction.BASE_METAL));
        helper.succeed();
    }

    /** 挖掘工具：钻镐→BASE_METAL+AREA_HARVEST（不给 EDGE）；斧/锹同；锄额外 GROWTH。 */
    @GameTest(template = "item_concept")
    public static void diggersHarvest(GameTestHelper helper) {
        expect(helper, Items.DIAMOND_PICKAXE,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.AREA_HARVEST),
                fns(PhaseFunction.EDGE, PhaseFunction.HEAL));
        expect(helper, Items.IRON_AXE,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.AREA_HARVEST),
                fns(PhaseFunction.BASE_WOOD));
        expect(helper, Items.IRON_SHOVEL,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.AREA_HARVEST),
                fns(PhaseFunction.BASE_WOOD));
        expect(helper, Items.IRON_HOE,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.AREA_HARVEST, PhaseFunction.GROWTH),
                fns(PhaseFunction.BASE_WOOD));
        helper.succeed();
    }

    /** 远程：弓/弩→BASE_WOOD 兵刃（不给 BASE_METAL）；三叉戟→BASE_METAL+EDGE；箭→EDGE。 */
    @GameTest(template = "item_concept")
    public static void rangedWeaponsResolve(GameTestHelper helper) {
        expect(helper, Items.BOW,
                fns(PhaseFunction.BASE_WOOD),
                fns(PhaseFunction.BASE_METAL, PhaseFunction.HEAL));
        expect(helper, Items.CROSSBOW,
                fns(PhaseFunction.BASE_WOOD),
                fns(PhaseFunction.BASE_METAL));
        expect(helper, Items.TRIDENT,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.EDGE),
                fns(PhaseFunction.BASE_WOOD));
        expect(helper, Items.ARROW,
                fns(PhaseFunction.EDGE),
                fns(PhaseFunction.HEAL, PhaseFunction.DEFENSE));
        helper.succeed();
    }

    /** 盔甲四件：铁甲→BASE_METAL+DEFENSE；皮甲→BASE_HIDE+DEFENSE（不给 BASE_METAL）。 */
    @GameTest(template = "item_concept")
    public static void armorDefends(GameTestHelper helper) {
        for (Item piece : new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE,
                Items.IRON_LEGGINGS, Items.IRON_BOOTS}) {
            expect(helper, piece,
                    fns(PhaseFunction.BASE_METAL, PhaseFunction.DEFENSE),
                    fns(PhaseFunction.EDGE, PhaseFunction.HEAL));
        }
        expect(helper, Items.LEATHER_CHESTPLATE,
                fns(PhaseFunction.BASE_HIDE, PhaseFunction.DEFENSE),
                fns(PhaseFunction.BASE_METAL));
        helper.succeed();
    }

    /** 盾牌→DEFENSE（不给 EDGE/HEAL）。 */
    @GameTest(template = "item_concept")
    public static void shieldDefends(GameTestHelper helper) {
        expect(helper, Items.SHIELD,
                fns(PhaseFunction.DEFENSE),
                fns(PhaseFunction.EDGE, PhaseFunction.HEAL));
        helper.succeed();
    }

    /** 金系身份（黄金是中和之物）：金锭→BASE_METAL+MANA；金粒→MANA 且不当骨架；
     *  金剑/金镐/金胸甲→基底+本职算子+MANA。 */
    @GameTest(template = "item_concept")
    public static void goldHasManaIdentity(GameTestHelper helper) {
        expect(helper, Items.GOLD_INGOT,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.MANA),
                fns(PhaseFunction.EDGE));
        expect(helper, Items.GOLD_NUGGET,
                fns(PhaseFunction.MANA),
                fns(PhaseFunction.BASE_METAL, PhaseFunction.EDGE));
        expect(helper, Items.GOLDEN_SWORD,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.EDGE, PhaseFunction.MANA),
                fns(PhaseFunction.BASE_WOOD));
        expect(helper, Items.GOLDEN_PICKAXE,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.AREA_HARVEST, PhaseFunction.MANA),
                fns(PhaseFunction.BASE_WOOD));
        expect(helper, Items.GOLDEN_CHESTPLATE,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.DEFENSE, PhaseFunction.MANA),
                fns(PhaseFunction.BASE_HIDE));
        helper.succeed();
    }

    /** 基底素材：铁锭/钻石/下界合金→BASE_METAL；原木→BASE_WOOD；圆石→BASE_METAL+DEFENSE；
     *  骨头→BASE_BONE；皮革→BASE_HIDE。 */
    @GameTest(template = "item_concept")
    public static void baseMaterialsResolve(GameTestHelper helper) {
        expect(helper, Items.IRON_INGOT,
                fns(PhaseFunction.BASE_METAL),
                fns(PhaseFunction.EDGE, PhaseFunction.HEAL));
        expect(helper, Items.DIAMOND,
                fns(PhaseFunction.BASE_METAL),
                fns(PhaseFunction.HEAL));
        expect(helper, Items.NETHERITE_INGOT,
                fns(PhaseFunction.BASE_METAL),
                fns(PhaseFunction.BASE_WOOD));
        expect(helper, Items.OAK_LOG,
                fns(PhaseFunction.BASE_WOOD),
                fns(PhaseFunction.BASE_METAL));
        expect(helper, Items.COBBLESTONE,
                fns(PhaseFunction.BASE_METAL, PhaseFunction.DEFENSE),
                fns(PhaseFunction.HEAL));
        expect(helper, Items.BONE,
                fns(PhaseFunction.BASE_BONE),
                fns(PhaseFunction.BASE_METAL));
        expect(helper, Items.LEATHER,
                fns(PhaseFunction.BASE_HIDE),
                fns(PhaseFunction.BASE_METAL, PhaseFunction.HEAL));
        helper.succeed();
    }

    /** 食物→HEAL+生命；有害食物（蜘蛛眼）→POISON 且不给 HEAL；药水→MANA 且不给 HEAL。 */
    @GameTest(template = "item_concept")
    public static void foodAndPotionResolve(GameTestHelper helper) {
        expect(helper, Items.BREAD,
                fns(PhaseFunction.HEAL),
                fns(PhaseFunction.POISON, PhaseFunction.EDGE));
        var apple = ItemConceptResolver.resolve(new ItemStack(Items.APPLE));
        helper.assertTrue(apple.functions().contains(PhaseFunction.HEAL)
                        && apple.phases().contains(Phase.LIFE),
                "苹果应为 HEAL+生命，实际 " + apple.functions() + "/" + apple.phases());

        expect(helper, Items.SPIDER_EYE,
                fns(PhaseFunction.POISON),
                fns(PhaseFunction.HEAL));
        expect(helper, Items.POISONOUS_POTATO,
                fns(PhaseFunction.POISON),
                fns(PhaseFunction.HEAL));
        expect(helper, Items.POTION,
                fns(PhaseFunction.MANA),
                fns(PhaseFunction.HEAL, PhaseFunction.EDGE));
        helper.succeed();
    }

    /** 怪物掉落与杂物：末影珍珠→MANA；烈焰棒/火药→IGNITE；羽毛→LEVITATION；线不乱给算子。 */
    @GameTest(template = "item_concept")
    public static void monsterDropsResolve(GameTestHelper helper) {
        expect(helper, Items.ENDER_PEARL,
                fns(PhaseFunction.MANA),
                fns(PhaseFunction.HEAL));
        expect(helper, Items.BLAZE_ROD,
                fns(PhaseFunction.IGNITE),
                fns(PhaseFunction.HEAL, PhaseFunction.WATER_BREATH));
        expect(helper, Items.GUNPOWDER,
                fns(PhaseFunction.IGNITE),
                fns(PhaseFunction.HEAL));
        expect(helper, Items.FEATHER,
                fns(PhaseFunction.LEVITATION),
                fns(PhaseFunction.HEAL));
        // 线：辅料（无 BASE_*、无 HEAL）
        var string = ItemConceptResolver.resolve(new ItemStack(Items.STRING));
        helper.assertTrue(string.functions().stream().noneMatch(f -> f.name().startsWith("BASE_")),
                "线是辅料，不应有 BASE_* 骨架，实际 " + string.functions());
        helper.assertTrue(!string.functions().contains(PhaseFunction.HEAL),
                "线不应给 HEAL，实际 " + string.functions());
        helper.succeed();
    }
}
