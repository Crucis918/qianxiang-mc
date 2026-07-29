package com.qianxiang;

import com.qianxiang.handler.RiftAffix;
import com.qianxiang.handler.RiftTrialHandler;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseFunctionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 裂隙试炼 GameTests：触发刷词缀怪 / 词缀材料组件钩子 / 全灭结算 / 冷却拒绝。
 * <p>
 * 复用 {@code qianxiang:item_concept} 空场地模板。试炼的维度与手持物检查在事件入口
 * （{@code RiftTrialHandler.onRightClickBlock}），这里直接驱动
 * {@link RiftTrialHandler#startTrial}——冷却与「进行中试炼」判定都在其中，可观测。
 * 每个用例首尾各调一次 {@link RiftTrialHandler#resetForTest}：
 * 静态冷却表按 UUID 计，跨用例不清理会互相污染。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangTrialGameTests {

    private QianxiangTrialGameTests() {}

    private static net.minecraft.server.level.ServerPlayer mockPlayerAt(GameTestHelper helper, BlockPos rel) {
        // 复用 QianxiangCoreGameTests 的吞包假连接工厂——helper.makeMockServerPlayerInLevel
        // 走 placeNewPlayer 登录流程会推 phase_material_sync，未协商通道的假连接直接抛错
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        BlockPos abs = helper.absolutePos(rel);
        player.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0.0f, 0.0f);
        return player;
    }

    /** 触发：开波后周围刷出试炼怪——带词缀前缀名、HP 高于原版，且全部带试炼 tag。 */
    @GameTest(template = "item_concept")
    public static void trialSpawnsAffixedWave(GameTestHelper helper) {
        var player = mockPlayerAt(helper, new BlockPos(2, 2, 2));
        ServerLevel level = player.serverLevel();
        RiftTrialHandler.resetForTest(player);

        helper.assertTrue(RiftTrialHandler.startTrial(player, player.blockPosition()),
                "首次触发应成功开波");
        List<UUID> mobIds = RiftTrialHandler.activeTrialMobs(player);
        helper.assertTrue(mobIds.size() == 3,
                "wins=0 时波次应为 3 只，实际 " + mobIds.size());

        int named = 0;
        for (UUID id : mobIds) {
            Entity e = level.getEntity(id);
            helper.assertTrue(e instanceof LivingEntity && e.isAlive(),
                    "试炼怪应存活，实际 " + e);
            helper.assertTrue(e.getTags().contains(RiftTrialHandler.TAG_TRIAL_MOB),
                    "试炼怪应带 " + RiftTrialHandler.TAG_TRIAL_MOB + " tag");
            if (e.hasCustomName()) {
                named++;
                LivingEntity living = (LivingEntity) e;
                String name = e.getCustomName().getString();
                helper.assertTrue(name.contains("·"),
                        "词缀怪名称应带「·词缀·」前缀，实际 " + name);
                helper.assertTrue(living.getMaxHealth() > 20.0f,
                        "词缀怪 HP 应高于原版（20），实际 " + living.getMaxHealth());
                helper.assertTrue(living.getTags().stream()
                                .anyMatch(t -> t.startsWith(RiftTrialHandler.TAG_AFFIX_PREFIX)),
                        "词缀怪应带词缀 tag");
            }
        }
        helper.assertTrue(named >= 1, "首只保底带词缀——至少应有一只词缀怪，实际 " + named);

        RiftTrialHandler.resetForTest(player);
        helper.succeed();
    }

    /** 词缀材料钩子：带 AFFIX 组件的泥土注入对应算子；无组件的泥土不含 IGNITE。 */
    @GameTest(template = "item_concept")
    public static void affixComponentInjectsFunction(GameTestHelper helper) {
        ItemStack plain = new ItemStack(Items.DIRT);
        helper.assertTrue(!PhaseFunctionResolver.get(plain).contains(PhaseFunction.IGNITE),
                "无组件泥土不应含 IGNITE（反向锚点）");

        ItemStack emberDirt = new ItemStack(Items.DIRT);
        emberDirt.set(QianxiangDataComponents.AFFIX.get(), RiftAffix.EMBER.id());
        helper.assertTrue(PhaseFunctionResolver.get(emberDirt).contains(PhaseFunction.IGNITE),
                "带 ember 词缀的泥土应注入 IGNITE，实际 "
                        + PhaseFunctionResolver.get(emberDirt));

        // 全词缀表：每个词缀 id 都必须注入它声明的算子
        for (RiftAffix affix : RiftAffix.values()) {
            ItemStack stack = affix.materialStack();
            helper.assertTrue(affix.id().equals(stack.get(QianxiangDataComponents.AFFIX.get())),
                    "词缀材料栈应带 AFFIX=" + affix.id());
            helper.assertTrue(PhaseFunctionResolver.get(stack).contains(affix.function()),
                    "词缀 " + affix.id() + " 应注入 " + affix.function() + "，实际 "
                            + PhaseFunctionResolver.get(stack));
        }

        // 注入是「额外」而非覆盖：烬铁原有的 BASE_METAL+IGNITE 不能丢
        ItemStack emberIron = new ItemStack(QianxiangMaterials.EMBER_IRON.get());
        emberIron.set(QianxiangDataComponents.AFFIX.get(), RiftAffix.STORM.id());
        var merged = PhaseFunctionResolver.get(emberIron);
        helper.assertTrue(merged.contains(PhaseFunction.STRENGTH),
                "storm 词缀应注入 STRENGTH，实际 " + merged);
        helper.assertTrue(merged.contains(PhaseFunction.BASE_METAL)
                        && merged.contains(PhaseFunction.IGNITE),
                "烬铁原有算子不应被词缀覆盖，实际 " + merged);
        helper.succeed();
    }

    /** 全灭：wins+1 且保底掉词缀材料（带 AFFIX 组件）。 */
    @GameTest(template = "item_concept")
    public static void clearingWaveAwardsWinAndMaterials(GameTestHelper helper) {
        var player = mockPlayerAt(helper, new BlockPos(2, 2, 2));
        ServerLevel level = player.serverLevel();
        RiftTrialHandler.resetForTest(player);
        int winsBefore = RiftTrialHandler.getWins(player);

        helper.assertTrue(RiftTrialHandler.startTrial(player, player.blockPosition()),
                "开波应成功");
        for (UUID id : RiftTrialHandler.activeTrialMobs(player)) {
            if (level.getEntity(id) instanceof LivingEntity mob) {
                mob.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
            }
        }
        RiftTrialHandler.tickTrials(level.getServer());

        helper.assertTrue(!RiftTrialHandler.hasActiveTrial(player),
                "全灭后试炼应已结算");
        helper.assertTrue(RiftTrialHandler.getWins(player) == winsBefore + 1,
                "全灭应 wins+1，实际 " + RiftTrialHandler.getWins(player)
                        + "（原 " + winsBefore + "）");

        // 保底回报：玩家附近应至少有一个带 AFFIX 组件的词缀材料掉落物
        AABB box = player.getBoundingBox().inflate(12.0);
        List<ItemEntity> affixDrops = level.getEntitiesOfClass(ItemEntity.class, box,
                ie -> ie.getItem().has(QianxiangDataComponents.AFFIX.get()));
        helper.assertTrue(!affixDrops.isEmpty(),
                "全灭后应掉至少 1 个带 AFFIX 组件的词缀材料");
        for (ItemEntity drop : affixDrops) {
            helper.assertTrue(RiftAffix.of(drop.getItem()) != null,
                    "掉落材料的 AFFIX 应能解析为合法词缀，实际 "
                            + drop.getItem().get(QianxiangDataComponents.AFFIX.get()));
            drop.discard();
        }

        RiftTrialHandler.resetForTest(player);
        helper.succeed();
    }

    /** 冷却：进行中再触发被拒；结束后冷却期内再触发仍被拒。 */
    @GameTest(template = "item_concept")
    public static void cooldownBlocksRetrigger(GameTestHelper helper) {
        var player = mockPlayerAt(helper, new BlockPos(2, 2, 2));
        RiftTrialHandler.resetForTest(player);

        helper.assertTrue(RiftTrialHandler.startTrial(player, player.blockPosition()),
                "首次触发应成功");
        helper.assertTrue(!RiftTrialHandler.startTrial(player, player.blockPosition()),
                "试炼进行中再次触发应被拒");

        RiftTrialHandler.endTrial(player, false); // 结束试炼但保留冷却
        helper.assertTrue(!RiftTrialHandler.hasActiveTrial(player), "试炼应已结束");
        helper.assertTrue(!RiftTrialHandler.startTrial(player, player.blockPosition()),
                "冷却期内再次触发应被拒");

        RiftTrialHandler.resetForTest(player); // 清冷却后才应恢复可触发
        helper.assertTrue(RiftTrialHandler.startTrial(player, player.blockPosition()),
                "冷却抹除后应可再次触发");
        RiftTrialHandler.resetForTest(player);
        helper.succeed();
    }
}
