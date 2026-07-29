package com.qianxiang;

import com.qianxiang.cap.ClassCore;
import com.qianxiang.cap.ClassCoreHelper;
import com.qianxiang.cap.ProficiencyHelper;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.phase.AttributeScheme;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseTier;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 主职业系统（ClassCore）：内核/非内核法术与近战倍率、设定校验、转职费用。
 * <p>
 * 倍率（ClassCoreHelper 顶部常量）：内核法术 ×1.15/蓝耗 ×0.9、非内核法术 ×0.45/×1.25、
 * 内核形态近战 ×1.05、非内核形态近战 ×0.6；未设内核全 ×1.0。
 * </p>
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangClassCoreGameTests {

    private QianxiangClassCoreGameTests() {}

    private static CustomSpell fireAoe() {
        return new CustomSpell(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "qianxiang", "classcore_test_fire_aoe"),
                "fire", "aoe", "damage", List.of(), 10, 0, 2);
    }

    private static void learn(CustomSpell spell, net.minecraft.server.level.ServerPlayer player) {
        var attachment = QianxiangAttachments.PLAYER_SPELL_DATA;
        player.setData(attachment, player.getData(attachment).learn(spell).withMana(100));
    }

    private static float castAtPig(GameTestHelper helper, CustomSpell spell,
                                   net.minecraft.server.level.ServerPlayer player) {
        var attachment = QianxiangAttachments.PLAYER_SPELL_DATA;
        player.setData(attachment, player.getData(attachment).withMana(100));
        var absPos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2));
        player.moveTo(absPos.getX() + 0.5, absPos.getY(), absPos.getZ() + 0.5, 0, 0);
        var pig = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(2, 2, 5));
        float before = pig.getHealth();
        com.qianxiang.spell.SpellCastHandler.castCustomSpell(spell, player);
        return before - pig.getHealth();
    }

    /** 内核/非内核元素法术：伤害 ≈ 无内核 ×1.15 / ×0.45；非内核蓝耗 ×1.25（10→13）。 */
    @GameTest(template = "item_concept")
    public static void coreElementSpellDamageScales(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(player);
        CustomSpell spell = fireAoe();
        learn(spell, player);

        float base = castAtPig(helper, spell, player);
        helper.assertTrue(base > 0.0f, "无内核基准掉血应 > 0");

        // 内核含 fire：×1.15（首次设定免费）
        helper.assertTrue(ClassCoreHelper.setClassCore(player,
                new ClassCore("fire", "arcane", "sword")), "首次设定应成功");
        float core = castAtPig(helper, spell, player);
        helper.assertTrue(Math.abs(core / base - 1.15f) < 0.02f,
                "内核元素掉血比应 ≈1.15，实际 " + (core / base));

        // 转职成不含 fire 的内核：×0.45，蓝耗 10→13（先给 10 绿宝石付转职费）
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 10));
        helper.assertTrue(ClassCoreHelper.setClassCore(player,
                new ClassCore("frost", "lightning", "mace")), "付费转职应成功");
        float off = castAtPig(helper, spell, player);
        helper.assertTrue(Math.abs(off / base - 0.45f) < 0.02f,
                "非内核元素掉血比应 ≈0.45，实际 " + (off / base));
        int mana = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA).currentMana();
        helper.assertTrue(mana == 87,
                "非内核蓝耗应 ceil(10×1.25)=13（100-13=87），实际 " + mana);
        helper.succeed();
    }

    /** 内核/非内核形态武器近战：×1.05 vs ×0.6（打假人，满力一击）。 */
    @GameTest(template = "item_concept")
    public static void coreFormMeleeScales(GameTestHelper helper) throws Exception {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(player);
        helper.assertTrue(ClassCoreHelper.setClassCore(player,
                new ClassCore("fire", "arcane", "katana")), "设定内核应成功");

        var detectEquipmentUpdates = net.minecraft.world.entity.LivingEntity.class
                .getDeclaredMethod("detectEquipmentUpdates");
        detectEquipmentUpdates.setAccessible(true);
        var tickerField = net.minecraft.world.entity.LivingEntity.class
                .getDeclaredField("attackStrengthTicker");
        tickerField.setAccessible(true);

        float coreDmg = meleeWithForm(helper, player, "katana", detectEquipmentUpdates, tickerField);
        helper.assertTrue(Math.abs(coreDmg - 5.0f * 1.05f) < 0.01f,
                "内核形态掉血应 == 5×1.05=5.25，实际 " + coreDmg);
        float offDmg = meleeWithForm(helper, player, "sword", detectEquipmentUpdates, tickerField);
        helper.assertTrue(Math.abs(offDmg - 5.0f * 0.6f) < 0.01f,
                "非内核形态掉血应 == 5×0.6=3.0，实际 " + offDmg);
        helper.succeed();
    }

    /** 满力一击：4.0 攻武器（1+4=5 基底）×形态倍率。 */
    private static float meleeWithForm(GameTestHelper helper,
                                       net.minecraft.server.level.ServerPlayer player,
                                       String form,
                                       java.lang.reflect.Method detectEquipmentUpdates,
                                       java.lang.reflect.Field tickerField) throws Exception {
        var attr = AttributeScheme.compose(List.of(
                        AttributeScheme.MaterialInput.of(PhaseTier.COMMON, PhaseFunction.EDGE)))
                .withForm(form).withBaseFamily("metal");
        ItemStack weapon = new ItemStack(QianxiangItems.EMBER_BLADE.get());
        weapon.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(), attr);
        AttributeScheme.applyModifiersToStack(weapon, attr);

        player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        detectEquipmentUpdates.invoke(player);
        player.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        detectEquipmentUpdates.invoke(player);
        tickerField.setInt(player, 200);

        var pig = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(2, 2, 2));
        player.moveTo(pig.getX(), pig.getY(), pig.getZ() + 1.5, 0, 0);
        float before = pig.getHealth();
        player.attack(pig);
        return before - pig.getHealth();
    }

    /** 设定校验：未开启拒 / 重复元素拒 / 非法形态拒 / 合法写入 + 相谱一条。 */
    @GameTest(template = "item_concept")
    public static void setClassCoreValidation(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);

        helper.assertTrue(!ClassCoreHelper.setClassCore(player,
                new ClassCore("fire", "arcane", "sword")), "未开启修行应被拒");
        ProficiencyHelper.unlock(player);

        helper.assertTrue(!new ClassCore("fire", "fire", "sword").valid(),
                "重复元素应不合法");
        helper.assertTrue(!ClassCoreHelper.setClassCore(player,
                new ClassCore("fire", "fire", "sword")), "重复元素应被拒");
        helper.assertTrue(!ClassCoreHelper.setClassCore(player,
                new ClassCore("fire", "arcane", "sword2")), "非法形态应被拒");

        int sagaBefore = player.getData(QianxiangAttachments.SAGA_DATA).entries().size();
        helper.assertTrue(ClassCoreHelper.setClassCore(player,
                new ClassCore("fire", "arcane", "sword")), "合法内核应写入");
        var data = player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        helper.assertTrue(data.classCore().isSet()
                        && data.classCore().elementA().equals("fire")
                        && data.classCore().elementB().equals("arcane")
                        && data.classCore().form().equals("sword"),
                "写入后内核字段应一致，实际 " + data.classCore());
        helper.assertTrue(player.getData(QianxiangAttachments.SAGA_DATA).entries().size()
                        == sagaBefore + 1,
                "设定应记相谱一条");
        helper.succeed();
    }

    /** 转职费用：首次免费（无绿宝石也成）；二次无绿宝石拒；10 绿宝石扣费成功。 */
    @GameTest(template = "item_concept")
    public static void classRespecCostsEmeralds(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        player.getInventory().clearContent();
        ProficiencyHelper.unlock(player);

        helper.assertTrue(ClassCoreHelper.setClassCore(player,
                new ClassCore("fire", "arcane", "sword")), "首次设定应免费");
        helper.assertTrue(!ClassCoreHelper.setClassCore(player,
                new ClassCore("frost", "lightning", "mace")), "无绿宝石转职应被拒");
        helper.assertTrue(player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA)
                        .classCore().form().equals("sword"),
                "被拒后内核应保持不变");

        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 10));
        helper.assertTrue(ClassCoreHelper.setClassCore(player,
                new ClassCore("frost", "lightning", "mace")), "10 绿宝石转职应成功");
        helper.assertTrue(player.getInventory().countItem(Items.EMERALD) == 0,
                "转职应恰扣 10 绿宝石");
        helper.assertTrue(player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA)
                        .classCore().form().equals("mace"),
                "转职后内核应更新");
        helper.succeed();
    }
}
