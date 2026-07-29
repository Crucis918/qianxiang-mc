package com.qianxiang;

import com.qianxiang.combat.CombatEffectHandler;
import com.qianxiang.entity.QianxiangEntities;
import com.qianxiang.entity.SpellProjectileEntity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 华丽特效升级 GameTests：击杀演出的服务端判定函数单测。
 * <p>
 * 客户端粒子（刀光/法阵/喷泉/浮字）不进 GameTest；这里只测
 * {@link CombatEffectHandler} 里拆出来的两个纯判定函数——
 * 「近 2s 内被玩家伤害过」窗口与「死于玩家法术」来源判定。
 * 复用 {@code qianxiang:item_concept} 空场地模板。
 * </p>
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangVfxGameTests {

    private QianxiangVfxGameTests() {}

    /** 击杀窗口：≤40 tick（2s，含边界）算玩家击杀；41 tick 与「从未被玩家伤害」不算。 */
    @GameTest(template = "item_concept")
    public static void recentPlayerKillWindow(GameTestHelper helper) {
        helper.assertTrue(CombatEffectHandler.isRecentPlayerKill(100L, 120L),
                "20 tick 前被玩家伤害应判为玩家击杀");
        helper.assertTrue(CombatEffectHandler.isRecentPlayerKill(100L, 140L),
                "恰好 40 tick（2s 边界）应仍判为玩家击杀");
        helper.assertTrue(!CombatEffectHandler.isRecentPlayerKill(100L, 141L),
                "41 tick 超出 2s 窗口不应判为玩家击杀");
        helper.assertTrue(!CombatEffectHandler.isRecentPlayerKill(-1L, 50L),
                "从未被玩家伤害（-1）不应判为玩家击杀");
        helper.succeed();
    }

    /** 法术击杀判定：玩家间接魔法/弹体直击 → true；玩家近战普攻与无攻击者魔法 → false。 */
    @GameTest(template = "item_concept")
    public static void diedToPlayerSpellJudgement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);

        DamageSource indirect = level.damageSources().indirectMagic(player, player);
        helper.assertTrue(CombatEffectHandler.diedToPlayerSpell(indirect),
                "玩家间接魔法（beam/aoe/touch 结算路径）应判为死于玩家法术");

        SpellProjectileEntity bolt = new SpellProjectileEntity(
                QianxiangEntities.SPELL_PROJECTILE.get(), level);
        DamageSource boltHit = level.damageSources().indirectMagic(bolt, player);
        helper.assertTrue(CombatEffectHandler.diedToPlayerSpell(boltHit),
                "法术弹体直击应判为死于玩家法术");

        DamageSource melee = level.damageSources().playerAttack(player);
        helper.assertTrue(!CombatEffectHandler.diedToPlayerSpell(melee),
                "玩家近战普攻不应判为死于玩家法术");

        DamageSource envMagic = level.damageSources().magic();
        helper.assertTrue(!CombatEffectHandler.diedToPlayerSpell(envMagic),
                "无攻击者的环境魔法不应判为死于玩家法术");
        helper.succeed();
    }
}
