package com.qianxiang;

import com.qianxiang.combat.WeaponFormProfile;
import com.qianxiang.compat.QianxiangEFCompat;
import com.qianxiang.phase.AttributeScheme;
import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseTier;
import com.qianxiang.phase.UpgradeRules;
import com.qianxiang.phase.WeaponForm;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 千机伞·形态转换（QianxiangWeaponItem.use + WeaponFormProfile.SWITCHABLE_FORMS
 * + QianxiangEFCompat.FORM_CACHE 按 form 键）。
 * <p>规矩同其他批次：只断言可观测最终状态，单向否定断言必配正向断言。</p>
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangFormSwitchGameTests {

    private QianxiangFormSwitchGameTests() {}

    private static ComposedAttributes attrOf(ItemStack stack) {
        return stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
    }

    /** 造一把指定升级等级/初始形态的传奇武器（LEGENDARY EDGE+BASE_METAL → 12.8 攻基底）。 */
    private static ItemStack legendaryWeaponAt(int level, String formId) {
        var attr = AttributeScheme.compose(List.of(
                AttributeScheme.MaterialInput.of(PhaseTier.LEGENDARY,
                        PhaseFunction.EDGE, PhaseFunction.BASE_METAL)));
        ItemStack stack = new ItemStack(QianxiangItems.EMBER_BLADE.get());
        stack.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(),
                attr.withForm(formId).withUpgradeProgress(level, 70,
                        attr.attackDamage(), attr.armor(), attr.armorToughness(),
                        attr.spellPowerPercent(), attr.manaBonus()));
        return stack;
    }

    /**
     * 核心：L5 武器右键 → 形态按 9 形态表顺移；属性/攻速/耐久上限/已损耐久/升级等级全保留。
     * 连按 9 次循环一整圈回到初始形态（循环语义的最强可观测证据）。
     */
    @GameTest(template = "item_concept")
    public static void l5UseSwitchesFormKeepsStats(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ItemStack stack = legendaryWeaponAt(UpgradeRules.MAX_LEVEL, WeaponForm.SWORD.id());
        stack.setDamageValue(50); // 已用掉一截耐久：切换不得修复也不得加倍损耗
        double atk = attrOf(stack).attackDamage();
        double speed = attrOf(stack).attackSpeed();
        int maxDamage = stack.getMaxDamage();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        String expected = WeaponForm.SWORD.id();
        for (int i = 0; i < WeaponFormProfile.SWITCHABLE_FORMS.size(); i++) {
            expected = WeaponFormProfile.nextSwitchableForm(expected).id();
            var result = QianxiangItems.EMBER_BLADE.get()
                    .use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(result.getResult().consumesAction(),
                    "L5 切换应消费动作，实际 " + result.getResult());
            ItemStack held = player.getMainHandItem();
            ComposedAttributes after = attrOf(held);
            helper.assertTrue(expected.equals(after.form()),
                    "第 " + (i + 1) + " 次切换后形态应为 " + expected + "，实际 " + after.form());
            helper.assertTrue(after.attackDamage() == atk && after.attackSpeed() == speed,
                    "切换不得动攻击/攻速（第 " + (i + 1) + " 次）");
            helper.assertTrue(held.getMaxDamage() == maxDamage && held.getDamageValue() == 50,
                    "切换不得动耐久上限/已损耐久（第 " + (i + 1) + " 次）");
            helper.assertTrue(after.upgradeLevel() == UpgradeRules.MAX_LEVEL && after.upgradeXp() == 70,
                    "切换不得动升级等级/经验（第 " + (i + 1) + " 次）");
        }
        helper.assertTrue(WeaponForm.SWORD.id().equals(attrOf(player.getMainHandItem()).form()),
                "9 次切换（一整圈）后应回到初始形态 sword，实际 "
                        + attrOf(player.getMainHandItem()).form());
        helper.succeed();
    }

    /**
     * 负向+正向双断言：L4 武器右键 → 形态不变（否定切换生效），
     * 且等级/攻击保持、结果为 FAIL（正向证明 use 确实被处理而非未走到）。
     */
    @GameTest(template = "item_concept")
    public static void l4UseKeepsFormAndStats(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ItemStack stack = legendaryWeaponAt(UpgradeRules.MAX_LEVEL - 1, WeaponForm.KATANA.id());
        double atk = attrOf(stack).attackDamage();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        var result = QianxiangItems.EMBER_BLADE.get()
                .use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(result.getResult() == InteractionResult.FAIL,
                "L4 右键应走 fail 分支（提示未解锁），实际 " + result.getResult());
        ComposedAttributes after = attrOf(player.getMainHandItem());
        helper.assertTrue(WeaponForm.KATANA.id().equals(after.form()),
                "L4 不得切换形态，实际 " + after.form());
        helper.assertTrue(after.upgradeLevel() == UpgradeRules.MAX_LEVEL - 1
                        && after.attackDamage() == atk,
                "L4 右键后等级/攻击应保持不变");
        helper.succeed();
    }

    /**
     * EF 缓存键含 form：greatsword 与 hammer 同为 greatsword EF 底座（前提断言），
     * 但判定盒不同——capability 必须是两个实例（否定共享），
     * 同形态重复查询必须命中同一缓存实例（正向复用）。
     */
    @GameTest(template = "item_concept")
    public static void efFormCacheKeyedByForm(GameTestHelper helper) {
        var greatProfile = WeaponFormProfile.of(WeaponForm.GREATSWORD.id());
        var hammerProfile = WeaponFormProfile.of(WeaponForm.HAMMER.id());
        helper.assertTrue(greatProfile != null && hammerProfile != null
                        && greatProfile.efCategory().equals(hammerProfile.efCategory()),
                "测试前提：巨剑/战锤应共享 EF 底座 greatsword");

        ItemStack great = legendaryWeaponAt(UpgradeRules.MAX_LEVEL, WeaponForm.GREATSWORD.id());
        ItemStack hammer = legendaryWeaponAt(UpgradeRules.MAX_LEVEL, WeaponForm.HAMMER.id());
        var capGreat = QianxiangEFCompat.provide(great, null);
        var capHammer = QianxiangEFCompat.provide(hammer, null);
        helper.assertTrue(capGreat != null && capHammer != null,
                "两种形态的产物都应获得 EF capability");
        helper.assertTrue(capGreat != capHammer,
                "缓存键含 form：同底座不同形态不得共享 capability 实例");

        var capGreatAgain = QianxiangEFCompat.provide(great, null);
        helper.assertTrue(capGreatAgain == capGreat,
                "同形态再次查询应命中缓存复用同一实例");
        helper.succeed();
    }
}
