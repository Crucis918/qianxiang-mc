package com.qianxiang.entity;

import com.qianxiang.Qianxiang;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;

/**
 * 千相 NPC 的实体事件钩子。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class QianxiangNPCEvents {

    private QianxiangNPCEvents() {}

    /**
     * 千相 NPC 不被僵尸转化成僵尸村民。
     * <p>
     * 原版链路：{@code Zombie.killedEntity} → {@code villager.convertTo(ZOMBIE_VILLAGER)}。
     * 由于千相 NPC 继承 {@link net.minecraft.world.entity.npc.Villager}，它会被这条链命中；
     * 而僵尸村民治愈后生成的是<b>原版</b>村民——流浪相师/深渊商人会就此永久消失，
     * 玩家攒的好感度与专属交易表一并丢失，且无法找回。
     * <p>
     * 覆写 {@code die} 拦不住（转化不在 die 里），必须取消这个可取消事件。
     * 被取消后 NPC 就是正常死亡，符合「相师是人不是村民」的设定。
     */
    @SubscribeEvent
    public static void onConversion(LivingConversionEvent.Pre event) {
        if (event.getEntity() instanceof QianxiangNPCBase) {
            event.setCanceled(true);
        }
    }
}
