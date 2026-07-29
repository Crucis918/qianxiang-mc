package com.qianxiang;

import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.spell.ComboTracker;
import com.qianxiang.spell.CustomSpell;
import com.qianxiang.spell.SpellCastHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 荣耀连招系统 GameTests：
 * <ul>
 *   <li>连续施放不同 id 法术 → combo 递增，combo≥3 起伤害加成生效（假人实测掉血高于单发）；</li>
 *   <li>同 id 重复 → combo 重置为 1；</li>
 *   <li>超 5 秒（100 tick）未施法 → combo 清零，再施法从 1 重计。</li>
 * </ul>
 * 复用 {@code qianxiang:item_concept} 空场地模板与
 * {@link QianxiangCoreGameTests#mockServerPlayer} 假连接玩家。
 * 运行：{@code ./gradlew runGameTestServer}。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangComboGameTests {

    private QianxiangComboGameTests() {}

    /** 实测用伤害法术：奥术/aoe/伤害 power 5 → 基准 15（×3.0 标定），奥术元素不带 DoT，掉血干净可测。 */
    private static final CustomSpell MEASURED = new CustomSpell(
            ResourceLocation.fromNamespaceAndPath("qianxiang", "combo_test_arcane_aoe"),
            "arcane", "aoe", "damage", List.of(), 1, 0, 5);

    /** 垫刀法术：寒霜/自身/增益，只给施法者挂抗性，不碰假人；cooldown 0 允许连按。 */
    private static CustomSpell builder(String path) {
        return new CustomSpell(ResourceLocation.fromNamespaceAndPath("qianxiang", path),
                "frost", "self", "buff", List.of(), 1, 0, 1);
    }

    private static void learn(net.minecraft.server.level.ServerPlayer player, CustomSpell... spells) {
        var data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        for (CustomSpell spell : spells) {
            data = data.learn(spell);
        }
        player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, data);
    }

    private static boolean cast(net.minecraft.server.level.ServerPlayer player, CustomSpell spell) {
        return SpellCastHandler.castLearnedSpell(player, spell.id().toString());
    }

    /** 连击递增 + 伤害乘区：首发无加成掉 15，10 连 ×1.32 掉 19.8，且 combo 封顶 10。 */
    @GameTest(template = "item_concept")
    public static void comboIncreasesAndBoostsDamage(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        var absPos = helper.absolutePos(new BlockPos(2, 2, 2));
        player.moveTo(absPos.getX() + 0.5, absPos.getY(), absPos.getZ() + 0.5, 0, 0);

        CustomSpell[] builders = new CustomSpell[9];
        for (int i = 0; i < builders.length; i++) {
            builders[i] = builder("combo_test_b" + i);
        }
        learn(player, MEASURED);
        learn(player, builders);

        // 首发：combo=1（<3 无加成），power 5 → 掉血恰为 15（与武器轮标定同口径）
        var dummy1 = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 2, 5));
        float before1 = dummy1.getHealth();
        helper.assertTrue(cast(player, MEASURED), "首发应施放成功");
        float dmg1 = before1 - dummy1.getHealth();
        helper.assertTrue(dmg1 > 0.0f, "假人应确实承伤");
        helper.assertTrue(Math.abs(dmg1 - 15.0f) < 0.05f,
                "combo=1 应无连击加成（掉血 15），实际 " + dmg1);
        helper.assertTrue(ComboTracker.comboOf(player) == 1,
                "首发后 combo 应为 1，实际 " + ComboTracker.comboOf(player));

        // 连按 8 个不同 id 垫刀：combo 2..9
        for (int i = 0; i < 8; i++) {
            helper.assertTrue(cast(player, builders[i]), "垫刀 " + i + " 应施放成功");
        }
        helper.assertTrue(ComboTracker.comboOf(player) == 9,
                "8 连垫刀后 combo 应为 9，实际 " + ComboTracker.comboOf(player));

        // 第 10 连换回实测法术：×1.32，15 → 19.8
        var dummy2 = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 2, 5));
        float before2 = dummy2.getHealth();
        helper.assertTrue(cast(player, MEASURED), "第 10 连应施放成功");
        float dmg2 = before2 - dummy2.getHealth();
        helper.assertTrue(ComboTracker.comboOf(player) == 10,
                "第 10 连后 combo 应为 10，实际 " + ComboTracker.comboOf(player));
        helper.assertTrue(dmg2 > dmg1,
                "连击加成后掉血应高于单发：" + dmg2 + " vs " + dmg1);
        helper.assertTrue(Math.abs(dmg2 - 19.8f) < 0.05f,
                "combo=10 应 ×1.32（掉血 19.8），实际 " + dmg2);

        // 封顶：再换一个不同 id，combo 仍停在 10
        helper.assertTrue(cast(player, builders[8]), "第 11 连应施放成功");
        helper.assertTrue(ComboTracker.comboOf(player) == 10,
                "combo 应封顶 10，实际 " + ComboTracker.comboOf(player));
        helper.succeed();
    }

    /** 同 id 重复 → combo 重置为 1（之后再换不同 id 从 1 起续连，不是清零作废）。 */
    @GameTest(template = "item_concept")
    public static void sameSpellRepeatResetsCombo(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        CustomSpell b0 = builder("combo_test_r0");
        CustomSpell b1 = builder("combo_test_r1");
        learn(player, b0, b1);

        helper.assertTrue(cast(player, b0), "第一发应施放成功");
        helper.assertTrue(cast(player, b1), "第二发应施放成功");
        helper.assertTrue(ComboTracker.comboOf(player) == 2,
                "换不同 id 应递增到 2，实际 " + ComboTracker.comboOf(player));

        helper.assertTrue(cast(player, b1), "同 id 重按应施放成功");
        helper.assertTrue(ComboTracker.comboOf(player) == 1,
                "同 id 重复应重置为 1，实际 " + ComboTracker.comboOf(player));

        helper.assertTrue(cast(player, b0), "重置后再换 id 应施放成功");
        helper.assertTrue(ComboTracker.comboOf(player) == 2,
                "重置后换不同 id 应从 1 续连到 2，实际 " + ComboTracker.comboOf(player));
        helper.succeed();
    }

    /** 超 5 秒（100 tick）未施法 → combo 清零；之后再连不同 id 从 1 重计。 */
    @GameTest(template = "item_concept", timeoutTicks = 160)
    public static void comboExpiresAfterFiveSeconds(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        CustomSpell b0 = builder("combo_test_t0");
        CustomSpell b1 = builder("combo_test_t1");
        CustomSpell b2 = builder("combo_test_t2");
        learn(player, b0, b1, b2);

        helper.assertTrue(cast(player, b0), "第一发应施放成功");
        helper.assertTrue(cast(player, b1), "第二发应施放成功");
        helper.assertTrue(ComboTracker.comboOf(player) == 2,
                "窗口内换不同 id 应递增到 2，实际 " + ComboTracker.comboOf(player));

        // 推进 110 tick（> 5s 窗口；默认超时只有 100 tick，故本测试显式放宽）再断言：
        // 懒过期读数应为 0，再施法从 1 重计
        helper.runAtTickTime(110L, () -> {
            helper.assertTrue(ComboTracker.comboOf(player) == 0,
                    "超 5 秒未施法 combo 应清零，实际 " + ComboTracker.comboOf(player));
            helper.assertTrue(cast(player, b2), "超时后施法应成功");
            helper.assertTrue(ComboTracker.comboOf(player) == 1,
                    "超时后再连不同 id 应从 1 重计，实际 " + ComboTracker.comboOf(player));
            helper.succeed();
        });
    }
}
