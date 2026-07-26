package com.qianxiang.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

/**
 * 流浪相师 —— 游荡于主世界的千相知识持有者。
 *
 * <p>MVP 作为基础 Villager-like NPC，用于触发外交烙印与对话。
 */
public class QianxiangWanderingSage extends QianxiangNPCBase {
    public QianxiangWanderingSage(EntityType<? extends QianxiangWanderingSage> type, Level level) {
        super(type, level);
    }

    @Override
    protected String getDialogPrefix() {
        return "qianxiang.npc.wandering_sage";
    }

    @Override
    protected String getPriceHintKey() {
        return "qianxiang.npc.wandering_sage.price";
    }
}
