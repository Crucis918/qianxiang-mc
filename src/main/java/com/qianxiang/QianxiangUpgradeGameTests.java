package com.qianxiang;

import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.phase.ForgeComposer;
import com.qianxiang.phase.UpgradeRules;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 传奇装备升级树（UpgradeRules + menu 升级分支 + 仪式复用）。
 * <p>
 * 防复利断言是核心：L2 主属性必须 == L0×1.12（同一份 L0 基底重算），
 * 而不是 L1×1.06（== L0×1.1236，复利）——基底取值故意放大到 12.8，
 * 两种路径差 0.046，远超 ±0.01 容差，错一个就现形。
 * </p>
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangUpgradeGameTests {

    private QianxiangUpgradeGameTests() {}

    /** 造一件传奇武器：warden_core（LEGENDARY）+ 兽牙（EDGE）+ 铁（BASE_METAL）→ powerScore≥12。 */
    private static ItemStack legendaryWeapon() {
        List<ItemStack> mats = new ArrayList<>(25);
        for (int i = 0; i < 25; i++) mats.add(ItemStack.EMPTY);
        mats.set(12, new ItemStack(QianxiangItems.WARDEN_CORE.get()));
        mats.set(6, new ItemStack(QianxiangItems.BEAST_FANG.get()));
        mats.set(7, new ItemStack(Items.IRON_INGOT));
        var comp = ForgeComposer.compose(mats);
        if (!comp.valid()) throw new IllegalStateException("传奇武器组合失败");
        return comp.result();
    }

    private static com.qianxiang.phase.ComposedAttributes attrOf(ItemStack stack) {
        return stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
    }

    /** 全仪式链路：传奇产物进核心槽 + 喂 RARE×4（8xp）→ DONE → L1 且攻击 == L0×1.06。 */
    @GameTest(template = "item_concept")
    public static void upgradeThroughRitual(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        player.getInventory().clearContent();

        ItemStack legendary = legendaryWeapon();
        helper.assertTrue(UpgradeRules.isUpgradeable(legendary),
                "warden_core 组合产物应达传奇资格，powerScore="
                        + attrOf(legendary).powerScore());
        double l0Attack = attrOf(legendary).attackDamage();

        be.setItem(12, legendary.copy());
        for (int i = 0; i < 4; i++) {
            be.setItem(6 + i, new ItemStack(QianxiangMaterials.EMBER_IRON.get())); // RARE×4 = 8xp
        }
        ForgeTableMenu menu = new ForgeTableMenu(1, player.getInventory(), be);
        menu.slotsChanged(be);

        ItemStack preview = be.getItem(ForgeTableMenu.RESULT_SLOT);
        helper.assertTrue(!preview.isEmpty(), "升级模式下结果槽应有升级预览");
        helper.assertTrue(attrOf(preview).upgradeLevel() == 1,
                "8xp 预览应为 L1，实际 L" + attrOf(preview).upgradeLevel());
        helper.assertTrue(com.qianxiang.block.RitualLogic.startRitual(be, player), "升级仪式应可触发");

        helper.runAfterDelay(90, () -> {
            helper.assertTrue(be.ritualState() == com.qianxiang.block.RitualState.DONE,
                    "90t 后仪式应 DONE，实际 " + be.ritualState());
            ItemStack upgraded = be.getDisplayResult();
            helper.assertTrue(!upgraded.isEmpty(), "DONE 后产物虚影应就位");
            helper.assertTrue(attrOf(upgraded).upgradeLevel() == 1,
                    "产物应为 L1，实际 L" + attrOf(upgraded).upgradeLevel());
            helper.assertTrue(Math.abs(attrOf(upgraded).attackDamage() - l0Attack * 1.06) < 0.01,
                    "L1 攻击应 == L0×1.06（" + l0Attack * 1.06 + "），实际 "
                            + attrOf(upgraded).attackDamage());
            helper.assertTrue(Math.abs(attrOf(upgraded).attackSpeed()
                            - attrOf(legendary).attackSpeed()) < 0.0001,
                    "攻速不参与升级加成，应保持不变");
            level.removeBlock(pos, false);
            helper.succeed();
        });
    }

    /** 连升无复利：L1 装备再喂跨阈 → L2 == L0×1.12（基底重算），不是 L1×1.06。 */
    @GameTest(template = "item_concept")
    public static void upgradeNoCompounding(GameTestHelper helper) {
        // 大基底（LEGENDARY EDGE=12.8 攻）拉开两条路径的差距：×1.12 vs ×1.1236
        var attr = com.qianxiang.phase.AttributeScheme.compose(List.of(
                com.qianxiang.phase.AttributeScheme.MaterialInput.of(
                        com.qianxiang.phase.PhaseTier.LEGENDARY,
                        com.qianxiang.phase.PhaseFunction.EDGE,
                        com.qianxiang.phase.PhaseFunction.BASE_METAL)));
        ItemStack core = new ItemStack(QianxiangItems.EMBER_BLADE.get());
        core.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(),
                attr.withUpgradeProgress(0, 0, attr.attackDamage(), attr.armor(),
                        attr.armorToughness(), attr.spellPowerPercent(), attr.manaBonus()));
        double l0 = attr.attackDamage(); // 12.8

        // 第一次：4×RARE（8xp）→ L1（== L0×1.06）
        var l1 = UpgradeRules.composeUpgrade(core, List.of(
                new ItemStack(QianxiangMaterials.EMBER_IRON.get()),
                new ItemStack(QianxiangMaterials.EMBER_IRON.get()),
                new ItemStack(QianxiangMaterials.EMBER_IRON.get()),
                new ItemStack(QianxiangMaterials.EMBER_IRON.get())));
        helper.assertTrue(l1.valid() && attrOf(l1.result()).upgradeLevel() == 1,
                "首次升级应为 L1");
        helper.assertTrue(Math.abs(attrOf(l1.result()).attackDamage() - l0 * 1.06) < 0.01,
                "L1 应 == L0×1.06");

        // 第二次：L1 装备再喂 1×RARE（+2xp=10xp）→ L2，必须 == L0×1.12 而非 L1×1.06
        var l2 = UpgradeRules.composeUpgrade(l1.result(), List.of(
                new ItemStack(QianxiangMaterials.EMBER_IRON.get())));
        helper.assertTrue(l2.valid() && attrOf(l2.result()).upgradeLevel() == 2,
                "10xp 应为 L2，实际 L" + (l2.valid() ? attrOf(l2.result()).upgradeLevel() : -1));
        double v = attrOf(l2.result()).attackDamage();
        helper.assertTrue(Math.abs(v - l0 * 1.12) < 0.01,
                "防复利：L2 应 == L0×1.12（" + l0 * 1.12 + "），实际 " + v);
        helper.assertTrue(Math.abs(v - l0 * 1.06 * 1.06) > 0.01,
                "若 == L1×1.06（" + l0 * 1.06 * 1.06 + "）即复利 bug，实际 " + v);
        helper.succeed();
    }

    /** 非传奇核心槽：普通材料照常走 compose；低分千相产物进核心槽也不进升级模式。 */
    @GameTest(template = "item_concept")
    public static void nonLegendaryCoreUsesNormalCompose(GameTestHelper helper) {
        // 正向：普通锻造照常（铁+燧石 → 武器）
        List<ItemStack> mats = new ArrayList<>(25);
        for (int i = 0; i < 25; i++) mats.add(ItemStack.EMPTY);
        mats.set(12, new ItemStack(Items.IRON_INGOT));
        mats.set(6, new ItemStack(Items.FLINT));
        var normal = ForgeComposer.compose(mats);
        helper.assertTrue(normal.valid() && attrOf(normal.result()).upgradeLevel() == 0,
                "普通材料组合应照常出产物（非升级模式）");
        helper.assertTrue(!UpgradeRules.isUpgradeable(normal.result()),
                "铁+燧石产物 powerScore 不过阈，不应是传奇装备");

        // 低分产物进核心槽：isUpgradeable=false → 升级分支不触发（普通 compose 处理）
        helper.assertTrue(!UpgradeRules.isUpgradeable(normal.result()),
                "powerScore<12 的千相产物不应被误判为可升级");
        helper.succeed();
    }

    /** 喂料折算与阈值：warden_core==8、COMMON==1、RARE==2；L5 封顶后不再产出。 */
    @GameTest(template = "item_concept")
    public static void feedXpMapping(GameTestHelper helper) {
        helper.assertTrue(UpgradeRules.feedXp(new ItemStack(QianxiangItems.WARDEN_CORE.get())) == 8,
                "森罗之核应折 8 XP");
        helper.assertTrue(UpgradeRules.feedXp(new ItemStack(Items.IRON_INGOT)) == 1,
                "COMMON 料应折 1 XP");
        helper.assertTrue(UpgradeRules.feedXp(new ItemStack(QianxiangMaterials.EMBER_IRON.get())) == 2,
                "RARE 料应折 2 XP");
        helper.assertTrue(UpgradeRules.levelForXp(0) == 0 && UpgradeRules.levelForXp(1) == 1
                        && UpgradeRules.levelForXp(9) == 1 && UpgradeRules.levelForXp(10) == 2
                        && UpgradeRules.levelForXp(25) == 3 && UpgradeRules.levelForXp(45) == 4
                        && UpgradeRules.levelForXp(70) == 5 && UpgradeRules.levelForXp(999) == 5,
                "阈值 L2=10/L3=25/L4=45/L5=70 封顶");

        // L5 装备再喂料：不涨级 → 空结果（料不吞）
        var maxedAttr = com.qianxiang.phase.AttributeScheme.compose(List.of(
                com.qianxiang.phase.AttributeScheme.MaterialInput.of(
                        com.qianxiang.phase.PhaseTier.LEGENDARY,
                        com.qianxiang.phase.PhaseFunction.EDGE,
                        com.qianxiang.phase.PhaseFunction.BASE_METAL)));
        ItemStack maxed = new ItemStack(QianxiangItems.EMBER_BLADE.get());
        maxed.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(),
                maxedAttr.withUpgradeProgress(5, 70, maxedAttr.attackDamage(), 0, 0, 0, 0));
        helper.assertTrue(!UpgradeRules.composeUpgrade(maxed,
                        List.of(new ItemStack(QianxiangItems.WARDEN_CORE.get()))).valid(),
                "L5 封顶后喂料不应产出（仪式不可触发）");
        helper.succeed();
    }
}
