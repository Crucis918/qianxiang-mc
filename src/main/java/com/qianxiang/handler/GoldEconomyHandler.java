package com.qianxiang.handler;

import com.qianxiang.Qianxiang;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * 黄金经济 —— 敌对怪的金粒掉落。
 * <p>
 * 金粒是通行全游戏的中和硬币（像西方传说里的黄金）。僵尸/骷髅/掠夺者类敌对怪
 * 被<b>玩家</b>击杀时 5% 掉 1~2 金粒——怪物掉金粒是经典奇幻母题，也让打怪
 * 本身成为温和的产金渠道（不抢裂隙试炼/遗迹箱子的风头）。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class GoldEconomyHandler {

    /** 敌对怪掉金粒的概率。 */
    private static final float DROP_CHANCE = 0.05f;

    private GoldEconomyHandler() {}

    /**
     * 金粒掉落掷骰：{@value #DROP_CHANCE} 概率掉 1~2 金粒，否则 0。
     * 单独成函数供 GameTest 断言概率分支可达（setSeed 驱动）。
     */
    public static int rollHostileGoldNuggets(RandomSource rand) {
        if (rand.nextFloat() >= DROP_CHANCE) return 0;
        return 1 + rand.nextInt(2);
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        // 僵尸类（含尸壳/溺尸）/骷髅类（含流浪者/凋零骷髅）/掠夺者类（含卫道士/唤魔者）
        if (!(entity instanceof Zombie) && !(entity instanceof AbstractSkeleton)
                && !(entity instanceof AbstractIllager)) return;
        // 只认玩家击杀——刷怪塔挂机不算「冒险所得」
        if (!(event.getSource().getEntity() instanceof Player)) return;
        int nuggets = rollHostileGoldNuggets(entity.getRandom());
        if (nuggets <= 0) return;
        event.getDrops().add(new ItemEntity(entity.level(),
                entity.getX(), entity.getY(), entity.getZ(),
                new ItemStack(Items.GOLD_NUGGET, nuggets)));
    }
}
