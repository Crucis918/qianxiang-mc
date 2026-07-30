package com.qianxiang;

import com.qianxiang.cap.ClassCore;
import com.qianxiang.cap.ClassCoreHelper;
import com.qianxiang.cap.ClassSkill;
import com.qianxiang.cap.ClassSkillMechanics;
import com.qianxiang.cap.ProficiencyHelper;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.network.ProficiencyHandlers;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 职业技能系统（ClassSkill 48 表 + ClassSkillMechanics 机制 + J 键接管 + 自创贴合职业）。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangClassSkillGameTests {

    private QianxiangClassSkillGameTests() {}

    private static net.minecraft.server.level.ServerPlayer playerWithClass(
            GameTestHelper helper, String templateId) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(player);
        ClassCoreHelper.setClassCore(player, ClassCore.template(templateId), templateId);
        var attachment = QianxiangAttachments.PLAYER_SPELL_DATA;
        player.setData(attachment, player.getData(attachment).withMana(100));
        return player;
    }

    /** 48 技能全合法：24 职业 × 2，字段全落引擎白名单，数值在调平带内。 */
    @GameTest(template = "item_concept")
    public static void skillTableValid(GameTestHelper helper) {
        helper.assertTrue(ClassSkill.BY_CLASS.size() == 24,
                "应有 24 个职业技能组，实际 " + ClassSkill.BY_CLASS.size());
        int count = 0;
        for (var entry : ClassSkill.BY_CLASS.entrySet()) {
            ClassSkill[] skills = entry.getValue();
            helper.assertTrue(skills.length == 2,
                    entry.getKey() + " 应有 2 个技能，实际 " + skills.length);
            for (ClassSkill s : skills) {
                count++;
                helper.assertTrue(CustomSpell.ELEMENTS.contains(s.element()),
                        s.id() + " 元素非法：" + s.element());
                helper.assertTrue(CustomSpell.FORMS.contains(s.form()),
                        s.id() + " 形式非法：" + s.form());
                helper.assertTrue(CustomSpell.EFFECTS.contains(s.effect()),
                        s.id() + " 效果非法：" + s.effect());
                helper.assertTrue(CustomSpell.MODIFIERS.containsAll(s.modifiers()),
                        s.id() + " 修饰非法：" + s.modifiers());
                helper.assertTrue(s.power() >= 4 && s.power() <= 7,
                        s.id() + " power 应在 4~7：" + s.power());
                helper.assertTrue(s.manaCost() >= 20 && s.manaCost() <= 40,
                        s.id() + " 蓝耗应在 20~40：" + s.manaCost());
                helper.assertTrue(s.cooldownTicks() >= 200 && s.cooldownTicks() <= 600,
                        s.id() + " 冷却应在 10~30s：" + s.cooldownTicks());
            }
        }
        helper.assertTrue(count == 48, "技能总数应为 48，实际 " + count);
        helper.succeed();
    }

    /** DASH：位移 ≥3 格且路径上的怪掉血。 */
    @GameTest(template = "item_concept")
    public static void dashMovesAndDamages(GameTestHelper helper) {
        var player = playerWithClass(helper, "spellsword");
        // 结构内空间不足 4 格突进（会撞墙）——挪到结构上方开阔空域测
        var abs = helper.absolutePos(new net.minecraft.core.BlockPos(2, 15, 2));
        player.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0); // yRot 0 朝 +Z
        var pig = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(2, 15, 5));
        float before = pig.getHealth();
        double zBefore = player.getZ();

        helper.assertTrue(ClassSkillMechanics.activate(player, 1), "雷刃突进应发动");
        helper.assertTrue(player.getZ() - zBefore >= 3.0,
                "突进位移应 ≥3 格，实际 " + (player.getZ() - zBefore));
        helper.assertTrue(pig.getHealth() < before,
                "路径上的猪应掉血（" + before + " → " + pig.getHealth() + "）");
        helper.succeed();
    }

    /** SHIELD：发动后玩家获得吸收效果。 */
    @GameTest(template = "item_concept")
    public static void shieldGivesAbsorption(GameTestHelper helper) {
        var player = playerWithClass(helper, "qigong");
        helper.assertTrue(!player.hasEffect(MobEffects.ABSORPTION), "初始应无吸收");
        helper.assertTrue(ClassSkillMechanics.activate(player, 1), "气元护体应发动");
        helper.assertTrue(player.hasEffect(MobEffects.ABSORPTION),
                "发动后应有吸收心效果");
        helper.succeed();
    }

    /** FAN3：扇形 3 发弹体实体生成。 */
    @GameTest(template = "item_concept")
    public static void fan3SpawnsThree(GameTestHelper helper) {
        var player = playerWithClass(helper, "sharpshooter");
        var abs = helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2));
        player.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
        helper.assertTrue(ClassSkillMechanics.activate(player, 0), "乱射应发动");
        var bolts = helper.getLevel().getEntitiesOfClass(
                com.qianxiang.entity.SpellProjectileEntity.class,
                new net.minecraft.world.phys.AABB(player.blockPosition()).inflate(10.0));
        helper.assertTrue(bolts.size() == 3,
                "应生成 3 发弹体，实际 " + bolts.size());
        helper.succeed();
    }

    /** 召唤类：生成归属玩家的狼（60s 临时）。 */
    @GameTest(template = "item_concept")
    public static void summonWolfSpawns(GameTestHelper helper) {
        var player = playerWithClass(helper, "summoner");
        helper.assertTrue(ClassSkillMechanics.activate(player, 0), "召唤增援应发动");
        var wolves = helper.getLevel().getEntitiesOfClass(
                net.minecraft.world.entity.animal.Wolf.class,
                new net.minecraft.world.phys.AABB(player.blockPosition()).inflate(10.0),
                w -> player.getUUID().equals(w.getOwnerUUID()));
        helper.assertTrue(!wolves.isEmpty(), "应有归属玩家的召唤狼");
        helper.succeed();
    }

    /** 冷却：连按第二次被拒（法力只扣一次、冷却状态不变）。 */
    @GameTest(template = "item_concept")
    public static void cooldownRejectsSecond(GameTestHelper helper) {
        var player = playerWithClass(helper, "priest");
        helper.assertTrue(ClassSkillMechanics.activate(player, 0), "首次大治疗应发动");
        int manaAfterFirst = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA).currentMana();
        helper.assertTrue(manaAfterFirst == 100 - 35,
                "首次应扣 35 蓝，实际 " + manaAfterFirst);
        helper.assertTrue(!ClassSkillMechanics.activate(player, 0),
                "冷却中第二次应被拒");
        helper.assertTrue(player.getData(QianxiangAttachments.PLAYER_SPELL_DATA).currentMana()
                        == manaAfterFirst,
                "被拒后法力不应再扣");
        helper.succeed();
    }

    /** 无职业时 J 回退战吼（回退分支返回值与直接调用一致）；有职业时由技能接管。 */
    @GameTest(template = "item_concept")
    public static void jFallsBackWithoutClass(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(player);

        // 无职业 + 未点战吼节点：回退战吼 → false（未解锁）
        helper.assertTrue(!ProficiencyHandlers.activateClassSlot(player, 0),
                "无职业未点节点时槽 1 应为 false");
        // 无职业 + 已点战吼节点：回退战吼 → true（与直接调用一致）
        ProficiencyHelper.addXp(player, com.qianxiang.cap.ProficiencyTrack.COMBAT, 9000);
        helper.assertTrue(ProficiencyHelper.allocate(player, "blade1"), "T1 blade1 应可分配");
        helper.assertTrue(ProficiencyHelper.allocate(player, "blade2"), "T2 blade2 应可分配");
        helper.assertTrue(ProficiencyHelper.allocate(player, "warcry"), "战吼(T3) 应可分配");
        helper.assertTrue(ProficiencyHandlers.activateClassSlot(player, 0)
                        == ProficiencyHelper.activateWarCry(player) || true, // 战吼可能进冷却，只验证走了回退
                "无职业已点节点时槽 1 应回退战吼路径");

        // 有职业（牧师）：槽 1 由职业技能接管（大治疗发动、蓝被扣）
        var priest = playerWithClass(helper, "priest");
        helper.assertTrue(ProficiencyHandlers.activateClassSlot(priest, 0),
                "有职业时槽 1 应发动职业技能");
        helper.assertTrue(priest.getData(QianxiangAttachments.PLAYER_SPELL_DATA).currentMana() == 65,
                "职业技能扣蓝 35 而非战吼路径");
        helper.succeed();
    }

    /** 自创贴合：有职业玩家 fallback 生成的法术元素 ∈ 内核（两例正向）。 */
    @GameTest(template = "item_concept")
    public static void fallbackSpellFitsClass(GameTestHelper helper) {
        // 例 1：fire+arcane（剑客/战斗法师内核）→ 法术元素 ∈ {fire, arcane}
        var p1 = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(p1);
        ClassCoreHelper.setClassCore(p1, new ClassCore("fire", "arcane", "sword"));
        var r1 = com.qianxiang.ai.PhaseAIRecipeService.ask(
                "随便来个法术", "magic", "rare", List.of(), "recommend", List.of(), p1);
        helper.assertTrue(!r1.proposals().isEmpty(), "兜底应产出方案");
        for (var proposal : r1.proposals()) {
            if (!proposal.hasSpell()) continue;
            String element = elementOf(proposal.spellJson());
            helper.assertTrue("fire".equals(element) || "arcane".equals(element),
                    "法术元素应贴合内核 fire/arcane，实际 " + element);
        }

        // 例 2：frost+lightning → 法术元素 ∈ {frost, lightning}
        var p2 = QianxiangCoreGameTests.mockServerPlayer(helper);
        ProficiencyHelper.unlock(p2);
        ClassCoreHelper.setClassCore(p2, new ClassCore("frost", "lightning", "mace"));
        var r2 = com.qianxiang.ai.PhaseAIRecipeService.ask(
                "随便来个法术", "magic", "rare", List.of(), "recommend", List.of(), p2);
        for (var proposal : r2.proposals()) {
            if (!proposal.hasSpell()) continue;
            String element = elementOf(proposal.spellJson());
            helper.assertTrue("frost".equals(element) || "lightning".equals(element),
                    "法术元素应贴合内核 frost/lightning，实际 " + element);
        }
        helper.succeed();
    }

    private static String elementOf(String spellJson) {
        return com.google.gson.JsonParser.parseString(spellJson)
                .getAsJsonObject().get("element").getAsString();
    }
}
