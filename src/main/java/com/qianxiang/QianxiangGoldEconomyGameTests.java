package com.qianxiang;

import com.qianxiang.entity.QianxiangAbyssMerchant;
import com.qianxiang.handler.GoldEconomyHandler;
import com.qianxiang.handler.RiftTrialHandler;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 黄金经济 GameTests。
 * <p>
 * 金粒 = 通行全游戏的中和硬币。覆盖：①敌对怪金粒掉落（概率分支可达 + 频率合理性）；
 * ②裂隙试炼胜利金粒保底（数量边界 + 全灭结算真实掉落）；③深渊商人交易表金粒计价
 * （不再收绿宝石）；④通用「卖料换金」（按档付金 + 每日 8 次上限）；
 * ⑤裂隙遗迹箱子/守望者掉落表出金。
 * <p>
 * 模板复用 {@code qianxiang:item_concept} 空场地。试炼清理见
 * {@link RiftTrialHandler#resetForTest}（静态冷却表按 UUID 计，不清理会跨用例串状态）。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangGoldEconomyGameTests {

    private QianxiangGoldEconomyGameTests() {}

    // ============================ ① 敌对怪金粒掉落 ============================

    /** 金粒掉落掷骰：95% → 0、5% → 1~2 两分支均可达（固定种子驱动），且频率合理。 */
    @GameTest(template = "item_concept")
    public static void hostileGoldDropRollReachable(GameTestHelper helper) {
        // 注意：用单个 RandomSource 连续掷——每次新建 RandomSource.create(连续种子) 的
        // 首次输出与种子强相关（LegacyRandomSource 特性），会伪造成「分支不可达」
        RandomSource rand = RandomSource.create(20260731L);
        int zeros = 0, drops = 0;
        for (int i = 0; i < 2000; i++) {
            int n = GoldEconomyHandler.rollHostileGoldNuggets(rand);
            helper.assertTrue(n >= 0 && n <= 2, "掉落数应在 0~2，实际 " + n);
            if (n == 0) {
                zeros++;
            } else {
                helper.assertTrue(n == 1 || n == 2, "命中分支应掉 1~2，实际 " + n);
                drops++;
            }
        }
        helper.assertTrue(zeros > 0 && drops > 0,
                "不掉/掉 两个概率分支都应可达（zeros=" + zeros + " drops=" + drops + "）");
        // 频率合理性：2000 次 5% 期望 ≈100，宽松区间防回归（大幅漂移=概率常量被动过）
        helper.assertTrue(drops >= 30 && drops <= 250,
                "2000 次中掉金次数应在 30~250（期望≈100），实际 " + drops);
        helper.succeed();
    }

    // ============================ ② 裂隙试炼金粒保底 ============================

    /** 保底数量边界：wins=0 → 2~5；wins≥10 → 4~7（每 5 胜 +1，封顶 +2）。 */
    @GameTest(template = "item_concept")
    public static void trialGoldBonusBounds(GameTestHelper helper) {
        int min0 = Integer.MAX_VALUE, max0 = 0, min10 = Integer.MAX_VALUE, max10 = 0;
        RandomSource rand = RandomSource.create(20260731L);
        for (int i = 0; i < 500; i++) {
            int n0 = RiftTrialHandler.rollGoldBonus(0, rand);
            int n10 = RiftTrialHandler.rollGoldBonus(10, rand);
            helper.assertTrue(n0 >= 2 && n0 <= 5, "wins=0 保底应在 2~5，实际 " + n0);
            helper.assertTrue(n10 >= 4 && n10 <= 7, "wins=10 保底应在 4~7，实际 " + n10);
            min0 = Math.min(min0, n0);
            max0 = Math.max(max0, n0);
            min10 = Math.min(min10, n10);
            max10 = Math.max(max10, n10);
        }
        helper.assertTrue(min0 == 2 && max0 == 5,
                "wins=0 应覆盖完整 2~5 区间，实际 [" + min0 + "," + max0 + "]");
        helper.assertTrue(min10 == 4 && max10 == 7,
                "wins=10 应覆盖完整 4~7 区间，实际 [" + min10 + "," + max10 + "]");
        helper.succeed();
    }

    /** 全灭结算：除词缀材料外，玩家附近应真实掉出 2~7 金粒。 */
    @GameTest(template = "item_concept")
    public static void trialVictoryDropsGold(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        BlockPos abs = helper.absolutePos(new BlockPos(2, 2, 2));
        player.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0.0f, 0.0f);
        ServerLevel level = player.serverLevel();
        RiftTrialHandler.resetForTest(player);

        helper.assertTrue(RiftTrialHandler.startTrial(player, player.blockPosition()),
                "开波应成功");
        for (UUID id : RiftTrialHandler.activeTrialMobs(player)) {
            if (level.getEntity(id) instanceof LivingEntity mob) {
                mob.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
            }
        }
        RiftTrialHandler.tickTrials(level.getServer());

        AABB box = player.getBoundingBox().inflate(12.0);
        List<ItemEntity> goldDrops = level.getEntitiesOfClass(ItemEntity.class, box,
                ie -> ie.getItem().is(Items.GOLD_NUGGET));
        helper.assertTrue(!goldDrops.isEmpty(), "试炼胜利应掉金粒（黄金保底）");
        int total = goldDrops.stream().mapToInt(ie -> ie.getItem().getCount()).sum();
        helper.assertTrue(total >= 2 && total <= 7,
                "金粒保底应在 2~7（wins 微涨后上限），实际 " + total);
        goldDrops.forEach(Entity::discard);

        RiftTrialHandler.resetForTest(player);
        helper.succeed();
    }

    // ============================ ③ 深渊商人金粒计价 ============================

    /** 交易表契约：全部以金粒计价/付金，不再出现绿宝石。 */
    @GameTest(template = "item_concept")
    public static void merchantPricesInGoldNotEmerald(GameTestHelper helper) {
        MerchantOffers offers = new MerchantOffers();
        QianxiangAbyssMerchant.buildOffers(offers);
        helper.assertTrue(!offers.isEmpty(), "深渊商人应有交易条目");
        boolean sellsForGold = false, buysWithGold = false;
        for (var offer : offers) {
            helper.assertTrue(!offer.getBaseCostA().is(Items.EMERALD),
                    "交易不应再收绿宝石：" + offer.getBaseCostA());
            helper.assertTrue(!offer.getResult().is(Items.EMERALD),
                    "交易不应再付绿宝石：" + offer.getResult());
            if (offer.getBaseCostA().is(Items.GOLD_NUGGET)) sellsForGold = true;
            if (offer.getResult().is(Items.GOLD_NUGGET)) buysWithGold = true;
        }
        helper.assertTrue(sellsForGold, "应有以金粒计价的售出型交易");
        helper.assertTrue(buysWithGold, "收购（龙骨）应付金粒");
        helper.succeed();
    }

    // ============================ ④ 通用「卖料换金」 ============================

    /** 档位价目：COMMON 1 / RARE 3 / EPIC 6 / LEGENDARY 12。 */
    @GameTest(template = "item_concept")
    public static void buybackPriceByTier(GameTestHelper helper) {
        helper.assertTrue(QianxiangAbyssMerchant.buybackPrice(PhaseTier.COMMON) == 1, "COMMON 应 1 金粒");
        helper.assertTrue(QianxiangAbyssMerchant.buybackPrice(PhaseTier.RARE) == 3, "RARE 应 3 金粒");
        helper.assertTrue(QianxiangAbyssMerchant.buybackPrice(PhaseTier.EPIC) == 6, "EPIC 应 6 金粒");
        helper.assertTrue(QianxiangAbyssMerchant.buybackPrice(PhaseTier.LEGENDARY) == 12,
                "LEGENDARY 应 12 金粒");
        helper.succeed();
    }

    /** 卖料换金：非材料拒；铁锭（COMMON）→ +1 金粒；森罗之核（LEGENDARY）→ +12；日限 8 次。 */
    @GameTest(template = "item_concept")
    public static void sellMaterialForGold(GameTestHelper helper) {
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        player.getInventory().clearContent();

        // 非材料（鸡蛋：只有相性没有功能算子）→ 拒
        player.getInventory().setItem(0, new ItemStack(Items.EGG));
        helper.assertTrue(!QianxiangAbyssMerchant.trySellMaterial(player),
                "非相材料（鸡蛋）应被拒收");

        // COMMON 档（铁锭）→ +1 金粒，材料 -1
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        helper.assertTrue(QianxiangAbyssMerchant.trySellMaterial(player),
                "铁锭（COMMON 相材料）应可出售");
        helper.assertTrue(player.getInventory().countItem(Items.IRON_INGOT) == 2,
                "出售应收 1 件材料，实际剩 " + player.getInventory().countItem(Items.IRON_INGOT));
        helper.assertTrue(player.getInventory().countItem(Items.GOLD_NUGGET) == 1,
                "COMMON 档应付 1 金粒，实际 " + player.getInventory().countItem(Items.GOLD_NUGGET));

        // LEGENDARY 档（森罗之核）→ +12 金粒
        player.getInventory().setItem(0, new ItemStack(QianxiangItems.WARDEN_CORE.get(), 1));
        helper.assertTrue(QianxiangAbyssMerchant.trySellMaterial(player),
                "森罗之核（LEGENDARY 相材料）应可出售");
        helper.assertTrue(player.getInventory().countItem(Items.GOLD_NUGGET) == 13,
                "LEGENDARY 档应付 12 金粒（累计 13），实际 "
                        + player.getInventory().countItem(Items.GOLD_NUGGET));

        // 日限 8 次：已用 2，再喂铁锭到顶后第 9 次被拒（clearContent 后金粒从零重新累计）
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 64));
        int sold = 0;
        while (QianxiangAbyssMerchant.trySellMaterial(player)) {
            sold++;
            helper.assertTrue(sold <= 10, "收购不应超过日限（死循环保护）");
        }
        helper.assertTrue(sold == QianxiangAbyssMerchant.DAILY_SELL_LIMIT - 2,
                "到日限前应再成交 " + (QianxiangAbyssMerchant.DAILY_SELL_LIMIT - 2)
                        + " 次，实际 " + sold);
        int goldAfterLimit = player.getInventory().countItem(Items.GOLD_NUGGET);
        helper.assertTrue(goldAfterLimit == sold,
                "本段 COMMON 出售应付 " + sold + " 金粒，实际 " + goldAfterLimit);
        helper.assertTrue(player.getInventory().countItem(Items.IRON_INGOT) == 64 - sold,
                "被拒后不应再收材料");
        helper.succeed();
    }

    // ============================ ⑤ 掉落表出金 ============================

    /** 裂隙遗迹箱子表存在且能滚出金粒（3~8）与金锭；守望者表存在且每次滚出 2~4 金锭。 */
    @GameTest(template = "item_concept")
    public static void lootTablesYieldGold(GameTestHelper helper) {
        var server = helper.getLevel().getServer();

        // —— 裂隙遗迹箱子 ——
        var chestId = ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "chests/rift_ruin"));
        var chest = server.reloadableRegistries().getLootTable(chestId);
        helper.assertTrue(chest != LootTable.EMPTY, "裂隙遗迹箱子战利品表应存在");

        LootParams chestParams = new LootParams.Builder(helper.getLevel())
                .withParameter(LootContextParams.ORIGIN,
                        Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1))))
                .create(LootContextParamSets.CHEST);
        boolean gotNuggets = false, gotIngots = false;
        for (long seed = 0; seed < 400 && !(gotNuggets && gotIngots); seed++) {
            for (ItemStack drop : chest.getRandomItems(chestParams, seed)) {
                if (drop.is(Items.GOLD_NUGGET)) {
                    helper.assertTrue(drop.getCount() >= 3 && drop.getCount() <= 8,
                            "箱子金粒应 3~8/组，实际 " + drop.getCount());
                    gotNuggets = true;
                }
                if (drop.is(Items.GOLD_INGOT)) {
                    helper.assertTrue(drop.getCount() >= 1 && drop.getCount() <= 2,
                            "箱子金锭应 1~2/组，实际 " + drop.getCount());
                    gotIngots = true;
                }
            }
        }
        helper.assertTrue(gotNuggets, "400 次滚表中应滚出金粒（权重 15）");
        helper.assertTrue(gotIngots, "400 次滚表中应滚出金锭（权重 5）");

        // —— 森罗守望者 ——
        var wardenId = ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "entities/myriad_warden"));
        var warden = server.reloadableRegistries().getLootTable(wardenId);
        helper.assertTrue(warden != LootTable.EMPTY, "守望者掉落表应存在");

        var pig = helper.spawn(net.minecraft.world.entity.EntityType.PIG, new BlockPos(2, 2, 5));
        LootParams entityParams = new LootParams.Builder(helper.getLevel())
                .withParameter(LootContextParams.THIS_ENTITY, pig)
                .withParameter(LootContextParams.ORIGIN, pig.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE,
                        helper.getLevel().damageSources().generic())
                .create(LootContextParamSets.ENTITY);
        boolean wardenGold = false;
        for (long seed = 0; seed < 20 && !wardenGold; seed++) {
            for (ItemStack drop : warden.getRandomItems(entityParams, seed)) {
                if (drop.is(Items.GOLD_INGOT)) {
                    helper.assertTrue(drop.getCount() >= 2 && drop.getCount() <= 4,
                            "守望者金锭应 2~4，实际 " + drop.getCount());
                    wardenGold = true;
                }
            }
        }
        helper.assertTrue(wardenGold, "守望者掉落表应滚出 2~4 金锭（黄金保底）");
        helper.succeed();
    }
}
