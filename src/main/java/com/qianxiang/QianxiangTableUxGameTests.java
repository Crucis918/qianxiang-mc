package com.qianxiang;

import com.qianxiang.block.AlchemyTableBlockEntity;
import com.qianxiang.block.ForgeTableBlockEntity;
import com.qianxiang.block.RitualLogic;
import com.qianxiang.block.RitualState;
import com.qianxiang.block.TableInteractions;
import com.qianxiang.menu.AlchemyTableMenu;
import com.qianxiang.menu.ForgeTableMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 工作台交互两宗罪的 GameTests（block/menu 侧）：
 * <p>
 * ①掉落物吸收白名单——只吸「材料相」物品（{@link TableInteractions#isAbsorbable}），
 * 误扔的猪肉/种子留在原地（铁锭正、猪肉/种子反）；
 * ②空手右键永远开 GUI、不再触发仪式——有产物时 menu 打开且 ritualState 保持 NONE（正），
 * GUI「开始创作」链路 {@link RitualLogic#startRitual} 仍可触发（反/回归）；
 * 顺带回归：产物就绪时潜行+空手取回全部仍可用（优先级最前，不受影响）。
 * </p>
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangTableUxGameTests {

    private QianxiangTableUxGameTests() {}

    // ============================ ① 吸收白名单 ============================

    /** 锻造台：铁锭（base_metal 功能 tag）被吸；猪肉（非材料）原地不动（正反向）。 */
    @GameTest(template = "item_concept")
    public static void absorbWhitelistForge(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos rel = new BlockPos(2, 1, 2);
        BlockPos pos = helper.absolutePos(rel);
        level.setBlockAndUpdate(pos, QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }

        // 谓词本身：铁锭可吸、猪肉不可吸（正反向）
        helper.assertTrue(TableInteractions.isAbsorbable(new ItemStack(Items.IRON_INGOT)),
                "铁锭应在吸收白名单内");
        helper.assertTrue(!TableInteractions.isAbsorbable(new ItemStack(Items.PORKCHOP)),
                "猪肉不应在吸收白名单内");

        ItemEntity iron = spawnItem(level, pos, new ItemStack(Items.IRON_INGOT));
        ItemEntity pork = spawnItem(level, pos, new ItemStack(Items.PORKCHOP));

        int moved = TableInteractions.absorbAbove(be, ForgeTableMenu.SLOT_FILL_ORDER, level, pos);
        helper.assertTrue(moved == 1, "应只吸收 1 件（铁锭），实际 " + moved);
        helper.assertTrue(be.getItem(ForgeTableMenu.SLOT_FILL_ORDER[0]).is(Items.IRON_INGOT),
                "铁锭应进材料槽首格，实际 " + be.getItem(ForgeTableMenu.SLOT_FILL_ORDER[0]));
        helper.assertTrue(!iron.isAlive(), "铁锭掉落物被吸后应消失");
        helper.assertTrue(pork.isAlive() && pork.getItem().is(Items.PORKCHOP),
                "猪肉掉落物应留在原地不动");

        pork.discard();
        level.removeBlock(pos, false);
        helper.succeed();
    }

    /** 炼金台：铁锭被吸；小麦种子（误扔杂物）原地不动（正反向）。 */
    @GameTest(template = "item_concept")
    public static void absorbWhitelistAlchemy(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos rel = new BlockPos(2, 1, 2);
        BlockPos pos = helper.absolutePos(rel);
        level.setBlockAndUpdate(pos, QianxiangBlocks.ALCHEMY_TABLE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof AlchemyTableBlockEntity be)) {
            helper.fail("炼金台方块实体应存在");
            return;
        }

        helper.assertTrue(!TableInteractions.isAbsorbable(new ItemStack(Items.WHEAT_SEEDS)),
                "小麦种子不应在吸收白名单内");

        ItemEntity iron = spawnItem(level, pos, new ItemStack(Items.IRON_INGOT));
        ItemEntity seeds = spawnItem(level, pos, new ItemStack(Items.WHEAT_SEEDS));

        int moved = TableInteractions.absorbAbove(be, AlchemyTableMenu.SLOT_FILL_ORDER, level, pos);
        helper.assertTrue(moved == 1, "应只吸收 1 件（铁锭），实际 " + moved);
        helper.assertTrue(be.getItem(AlchemyTableMenu.SLOT_FILL_ORDER[0]).is(Items.IRON_INGOT),
                "铁锭应进材料槽首格，实际 " + be.getItem(AlchemyTableMenu.SLOT_FILL_ORDER[0]));
        helper.assertTrue(!iron.isAlive(), "铁锭掉落物被吸后应消失");
        helper.assertTrue(seeds.isAlive() && seeds.getItem().is(Items.WHEAT_SEEDS),
                "种子掉落物应留在原地不动");

        seeds.discard();
        level.removeBlock(pos, false);
        helper.succeed();
    }

    // ============================ ② 空手右键开 GUI，不触发仪式 ============================

    /**
     * 锻造台：有产物时空手右键 → 开 GUI 且 ritualState 保持 NONE（正）；
     * 「开始创作」链路 startRitual 仍可触发（反/回归）；无产物时 startRitual 拒绝（反向兜底）。
     */
    @GameTest(template = "item_concept")
    public static void emptyHandUseOpensGuiNotRitualForge(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos rel = new BlockPos(2, 1, 2);
        BlockPos pos = helper.absolutePos(rel);
        level.setBlockAndUpdate(pos, QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        ServerPlayer player = QianxiangCoreGameTests.mockServerPlayer(helper);
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        // 反向兜底：无产物时 startRitual 拒绝
        helper.assertTrue(!RitualLogic.startRitual(be, player), "无产物时仪式不应可触发");

        // 材料就绪 → 预览产物
        be.setItem(0, new ItemStack(QianxiangMaterials.EMBER_IRON.get()));
        new ForgeTableMenu(1, player.getInventory(), be).slotsChanged(be);
        helper.assertTrue(!be.getItem(ForgeTableMenu.RESULT_SLOT).isEmpty(),
                "材料就绪后产物槽应有预览产物");

        // 空手右键：开 GUI，不触发仪式
        helper.useBlock(rel, player);
        helper.assertTrue(player.containerMenu instanceof ForgeTableMenu,
                "有产物时空手右键应开 GUI，实际容器 " + player.containerMenu);
        helper.assertTrue(be.ritualState() == RitualState.NONE,
                "空手右键不得触发仪式，实际 " + be.ritualState());
        helper.assertTrue(!be.getItem(ForgeTableMenu.RESULT_SLOT).isEmpty()
                        && be.getItem(0).is(QianxiangMaterials.EMBER_IRON.get()),
                "材料与预览产物应保持原样（未被仪式锁定）");

        // 按钮路径仍能触发（回归）
        helper.assertTrue(RitualLogic.startRitual(be, player),
                "「开始创作」链路应仍能触发仪式");
        helper.assertTrue(be.ritualState() == RitualState.FLYING,
                "触发后应为 FLYING，实际 " + be.ritualState());

        level.removeBlock(pos, false);
        helper.succeed();
    }

    /** 炼金台：同上——有产物空手右键开 GUI 不触发仪式；按钮链路仍可触发。 */
    @GameTest(template = "item_concept")
    public static void emptyHandUseOpensGuiNotRitualAlchemy(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos rel = new BlockPos(2, 1, 2);
        BlockPos pos = helper.absolutePos(rel);
        level.setBlockAndUpdate(pos, QianxiangBlocks.ALCHEMY_TABLE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof AlchemyTableBlockEntity be)) {
            helper.fail("炼金台方块实体应存在");
            return;
        }
        ServerPlayer player = QianxiangCoreGameTests.mockServerPlayer(helper);
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        be.setItem(0, new ItemStack(QianxiangItems.EMBER_CRYSTAL.get()));
        new AlchemyTableMenu(1, player.getInventory(), be).slotsChanged(be);
        helper.assertTrue(!be.getItem(AlchemyTableMenu.RESULT_SLOT).isEmpty(),
                "材料就绪后产物槽应有预览卷轴");

        helper.useBlock(rel, player);
        helper.assertTrue(player.containerMenu instanceof AlchemyTableMenu,
                "有产物时空手右键应开 GUI，实际容器 " + player.containerMenu);
        helper.assertTrue(be.ritualState() == RitualState.NONE,
                "空手右键不得触发仪式，实际 " + be.ritualState());

        helper.assertTrue(RitualLogic.startRitual(be, player),
                "「开始创作」链路应仍能触发仪式");
        helper.assertTrue(be.ritualState() == RitualState.FLYING,
                "触发后应为 FLYING，实际 " + be.ritualState());

        level.removeBlock(pos, false);
        helper.succeed();
    }

    // ============================ 顺带回归：潜行取回在产物就绪时仍可用 ============================

    /** 产物就绪（预览非空）时潜行+空手右键：取回全部材料入背包，不开 GUI、不触发仪式。 */
    @GameTest(template = "item_concept")
    public static void sneakRetrieveAllWithResultReady(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos rel = new BlockPos(2, 1, 2);
        BlockPos pos = helper.absolutePos(rel);
        ServerPlayer player = QianxiangCoreGameTests.mockServerPlayer(helper);

        // —— 锻造台 ——
        level.setBlockAndUpdate(pos, QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof ForgeTableBlockEntity forge)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        forge.setItem(0, new ItemStack(QianxiangMaterials.EMBER_IRON.get()));
        forge.setItem(1, new ItemStack(Items.STICK));
        new ForgeTableMenu(1, player.getInventory(), forge).slotsChanged(forge);
        helper.assertTrue(!forge.getItem(ForgeTableMenu.RESULT_SLOT).isEmpty(),
                "产物就绪前置：预览产物应非空");

        player.setShiftKeyDown(true);
        helper.useBlock(rel, player);
        player.setShiftKeyDown(false);
        helper.assertTrue(forge.getItem(0).isEmpty() && forge.getItem(1).isEmpty(),
                "潜行取回后材料槽应全空");
        helper.assertTrue(countInInventory(player, QianxiangMaterials.EMBER_IRON.get()) == 1
                        && countInInventory(player, Items.STICK) == 1,
                "材料应全部入背包，实际 ember_iron="
                        + countInInventory(player, QianxiangMaterials.EMBER_IRON.get())
                        + " stick=" + countInInventory(player, Items.STICK));
        helper.assertTrue(player.containerMenu == player.inventoryMenu,
                "潜行取回不得开 GUI");
        helper.assertTrue(forge.ritualState() == RitualState.NONE,
                "潜行取回不得触发仪式，实际 " + forge.ritualState());
        level.removeBlock(pos, false);

        // —— 炼金台 ——
        level.setBlockAndUpdate(pos, QianxiangBlocks.ALCHEMY_TABLE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof AlchemyTableBlockEntity alchemy)) {
            helper.fail("炼金台方块实体应存在");
            return;
        }
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        alchemy.setItem(0, new ItemStack(QianxiangItems.EMBER_CRYSTAL.get(), 2));
        new AlchemyTableMenu(1, player.getInventory(), alchemy).slotsChanged(alchemy);
        helper.assertTrue(!alchemy.getItem(AlchemyTableMenu.RESULT_SLOT).isEmpty(),
                "产物就绪前置：预览卷轴应非空");

        player.setShiftKeyDown(true);
        helper.useBlock(rel, player);
        player.setShiftKeyDown(false);
        helper.assertTrue(alchemy.getItem(0).isEmpty(), "潜行取回后材料槽应全空");
        helper.assertTrue(countInInventory(player, QianxiangItems.EMBER_CRYSTAL.get()) == 2,
                "材料应全部入背包，实际 " + countInInventory(player, QianxiangItems.EMBER_CRYSTAL.get()));
        helper.assertTrue(player.containerMenu == player.inventoryMenu,
                "潜行取回不得开 GUI");
        helper.assertTrue(alchemy.ritualState() == RitualState.NONE,
                "潜行取回不得触发仪式，实际 " + alchemy.ritualState());
        level.removeBlock(pos, false);

        helper.succeed();
    }

    // ============================ 工具 ============================

    /** 在台面上方（absorbAbove 查询盒内）放一个掉落物。 */
    private static ItemEntity spawnItem(net.minecraft.world.level.Level level, BlockPos pos, ItemStack stack) {
        ItemEntity entity = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, stack);
        level.addFreshEntity(entity);
        return entity;
    }

    private static int countInInventory(ServerPlayer player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }
}
