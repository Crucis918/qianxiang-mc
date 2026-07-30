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

    // ============================ 原创职业：24 职业与招牌被动 ============================

    /** 24 模板全部 valid：元素/形态在白名单、两元素互异、系列合法、id 唯一。 */
    @GameTest(template = "item_concept")
    public static void allTwentyFourTemplatesValid(GameTestHelper helper) {
        helper.assertTrue(ClassCore.TEMPLATES.size() == 24,
                "应有 24 个职业模板，实际 " + ClassCore.TEMPLATES.size());
        for (var entry : ClassCore.TEMPLATES.entrySet()) {
            var t = entry.getValue();
            helper.assertTrue(entry.getKey().equals(t.id()), "模板 key 与 id 应一致：" + entry.getKey());
            helper.assertTrue(ClassCore.SERIES.contains(t.series()),
                    t.id() + " 的系列应在白名单：" + t.series());
            helper.assertTrue(t.core().valid(),
                    t.id() + " 内核应合法：" + t.core());
        }
        // 内核唯一性：两对「同内核不同被动」的职业——swordsman/battle_mage 与
        // rogue/thief（靠存储的模板 id 区分，见 PlayerProficiencyData.classTemplateId），
        // 其余互不相同
        long distinct = ClassCore.TEMPLATES.values().stream().map(ClassCore.Template::core)
                .distinct().count();
        helper.assertTrue(distinct == 22,
                "24 职业应有 22 个不同内核（两对同核靠模板 id 区分），实际 " + distinct);
        helper.succeed();
    }

    /** 牧师：治疗量 +30%（同内核元素基准隔离：holy 系 heal 法术，holy+shadow 自定义 vs 牧师）。 */
    @GameTest(template = "item_concept")
    public static void priestHealBoost(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(player);
        CustomSpell heal = new CustomSpell(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "qianxiang", "classcore_test_heal"),
                "holy", "self", "heal", List.of(), 10, 0, 2);
        learn(heal, player);

        // 基准：holy+shadow 自定义内核（不匹配任何模板 → 无被动，但 holy 是内核元素 ×1.15）
        ClassCoreHelper.setClassCore(player, new ClassCore("holy", "shadow", "mace"));
        player.setHealth(5.0f);
        com.qianxiang.spell.SpellCastHandler.castCustomSpell(heal, player);
        float baseHeal = player.getHealth() - 5.0f;
        helper.assertTrue(baseHeal > 0.0f, "基准治疗应 > 0");

        // 牧师（holy+nature·staff）：同 ×1.15 内核元素，再 ×1.3 治疗
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 10));
        ClassCoreHelper.setClassCore(player, ClassCore.template("priest"));
        helper.assertTrue("priest".equals(ClassCoreHelper.signatureOf(player)), "应匹配 priest");
        learn(heal, player);
        player.setHealth(5.0f);
        com.qianxiang.spell.SpellCastHandler.castCustomSpell(heal, player);
        float priestHeal = player.getHealth() - 5.0f;
        helper.assertTrue(Math.abs(priestHeal / baseHeal - 1.3f) < 0.02f,
                "牧师治疗应为基准 ×1.3，实际比 " + (priestHeal / baseHeal));
        helper.succeed();
    }

    /** 圣骑士：受伤 -10%（10 点怪物伤害实扣 9）。 */
    @GameTest(template = "item_concept")
    public static void paladinIncomingReduction(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(player);
        ClassCoreHelper.setClassCore(player, ClassCore.template("paladin"));
        helper.assertTrue("paladin".equals(ClassCoreHelper.signatureOf(player)), "应匹配 paladin");

        // EF 在 GameTest 环境会吃掉 vanilla hurt（WQ-58 已实证），改为直接构造
        // LivingIncomingDamageEvent 调钩子，断言容器内新伤害被改到 9.0
        var zombie = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE,
                new net.minecraft.core.BlockPos(2, 2, 2));
        var event = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(
                player, new net.neoforged.neoforge.common.damagesource.DamageContainer(
                        zombie.damageSources().mobAttack(zombie), 10.0f));
        com.qianxiang.combat.ProficiencyCombatHooks.onIncomingDamage(event);
        helper.assertTrue(Math.abs(event.getContainer().getNewDamage() - 9.0f) < 0.01f,
                "圣骑士应把 10 点伤害减到 9，实际 " + event.getContainer().getNewDamage());
        helper.succeed();
    }

    /** 刺客：背后攻击 +30%（同一武器背刺/正面掉血比 ≈1.3）。 */
    @GameTest(template = "item_concept")
    public static void assassinBackstab(GameTestHelper helper) throws Exception {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(player);
        ClassCoreHelper.setClassCore(player, ClassCore.template("assassin"));

        var detectEquipmentUpdates = net.minecraft.world.entity.LivingEntity.class
                .getDeclaredMethod("detectEquipmentUpdates");
        detectEquipmentUpdates.setAccessible(true);
        var tickerField = net.minecraft.world.entity.LivingEntity.class
                .getDeclaredField("attackStrengthTicker");
        tickerField.setAccessible(true);

        float front = meleeFromDirection(helper, player, true, detectEquipmentUpdates, tickerField);
        float back = meleeFromDirection(helper, player, false, detectEquipmentUpdates, tickerField);
        helper.assertTrue(back > front, "背刺掉血应大于正面（" + back + " vs " + front + "）");
        helper.assertTrue(Math.abs(back / front - 1.3f) < 0.02f,
                "背刺/正面掉血比应 ≈1.3，实际 " + (back / front));
        helper.succeed();
    }

    /** 正面/背后满力一击（猪 NoAI 面向 +Z，dagger 内核形态武器）。 */
    private static float meleeFromDirection(GameTestHelper helper,
                                            net.minecraft.server.level.ServerPlayer player,
                                            boolean frontal,
                                            java.lang.reflect.Method detectEquipmentUpdates,
                                            java.lang.reflect.Field tickerField) throws Exception {
        var attr = AttributeScheme.compose(List.of(
                        AttributeScheme.MaterialInput.of(PhaseTier.COMMON, PhaseFunction.EDGE)))
                .withForm("dagger").withBaseFamily("metal");
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
        pig.setNoAi(true);
        pig.setYRot(0); // 面向 +Z
        // 正面 = 攻击者在 +Z（目标视线朝向攻击者）；背后 = 在 -Z
        player.moveTo(pig.getX(), pig.getY(), pig.getZ() + (frontal ? 1.5 : -1.5), 0, 0);
        float before = pig.getHealth();
        player.attack(pig);
        return before - pig.getHealth();
    }

    /** 狂战士：血量 <50% 时 +15%（同一武器低血/满血掉血比 ≈1.15）。 */
    @GameTest(template = "item_concept")
    public static void berserkerLowHpBoost(GameTestHelper helper) throws Exception {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(player);
        ClassCoreHelper.setClassCore(player, ClassCore.template("berserker"));

        var detectEquipmentUpdates = net.minecraft.world.entity.LivingEntity.class
                .getDeclaredMethod("detectEquipmentUpdates");
        detectEquipmentUpdates.setAccessible(true);
        var tickerField = net.minecraft.world.entity.LivingEntity.class
                .getDeclaredField("attackStrengthTicker");
        tickerField.setAccessible(true);

        player.setHealth(player.getMaxHealth());
        float full = meleeWithForm(helper, player, "greatsword", detectEquipmentUpdates, tickerField);
        player.setHealth(player.getMaxHealth() * 0.4f);
        float low = meleeWithForm(helper, player, "greatsword", detectEquipmentUpdates, tickerField);
        helper.assertTrue(Math.abs(low / full - 1.15f) < 0.02f,
                "低血/满血掉血比应 ≈1.15，实际 " + (low / full));
        player.setHealth(player.getMaxHealth());
        helper.succeed();
    }

    /** 召唤师：tick 后狼存在且 owner == 玩家。 */
    @GameTest(template = "item_concept")
    public static void summonerWolfCompanion(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(player);
        ClassCoreHelper.setClassCore(player, ClassCore.template("summoner"));
        helper.assertTrue("wolf".equals(ClassCoreHelper.companionOf(player)), "召唤师伙伴应为狼");

        // mock 玩家不在 server.playerList（巡检遍历不到）——用测试入口直接跑一轮
        com.qianxiang.cap.CompanionHandler.tickCompanionForTest(player);
        helper.runAfterDelay(5, () -> {
            var wolves = helper.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.animal.Wolf.class,
                    new net.minecraft.world.phys.AABB(player.blockPosition()).inflate(20.0),
                    w -> player.getUUID().equals(w.getOwnerUUID()));
            helper.assertTrue(!wolves.isEmpty(), "召唤师应有归属玩家的狼伙伴");
            helper.succeed();
        });
    }

    /** 战斗法师：连招上限 12（交替施法 14 次后 combo==12）；其他职业仍 10。 */
    @GameTest(template = "item_concept")
    public static void battleMageComboCapTwelve(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(player);
        ClassCoreHelper.setClassCore(player, ClassCore.template("battle_mage"), "battle_mage");

        CustomSpell a = new CustomSpell(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("qianxiang", "combo_a"),
                "fire", "self", "buff", List.of(), 1, 0, 1);
        CustomSpell b = new CustomSpell(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("qianxiang", "combo_b"),
                "frost", "self", "buff", List.of(), 1, 0, 1);
        learn(a, player);
        learn(b, player);

        for (int i = 0; i < 14; i++) {
            var attachment = QianxiangAttachments.PLAYER_SPELL_DATA;
            player.setData(attachment, player.getData(attachment).withMana(100));
            com.qianxiang.spell.SpellCastHandler.castCustomSpell(i % 2 == 0 ? a : b, player);
        }
        helper.assertTrue(com.qianxiang.spell.ComboTracker.comboOf(player) == 12,
                "战斗法师连招应封顶 12，实际 " + com.qianxiang.spell.ComboTracker.comboOf(player));

        // 换非战斗法师职业：继续交替施法，cap 回落 10
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 10));
        ClassCoreHelper.setClassCore(player, new ClassCore("holy", "shadow", "mace"));
        for (int i = 0; i < 4; i++) {
            var attachment = QianxiangAttachments.PLAYER_SPELL_DATA;
            player.setData(attachment, player.getData(attachment).withMana(100));
            com.qianxiang.spell.SpellCastHandler.castCustomSpell(i % 2 == 0 ? a : b, player);
        }
        helper.assertTrue(com.qianxiang.spell.ComboTracker.comboOf(player) == 10,
                "其他职业连招应封顶 10，实际 " + com.qianxiang.spell.ComboTracker.comboOf(player));
        helper.succeed();
    }
}
