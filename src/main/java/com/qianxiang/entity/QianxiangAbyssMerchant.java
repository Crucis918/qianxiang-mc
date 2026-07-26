package com.qianxiang.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

/**
 * 深渊商人 —— 来自沉潜裂隙的异相交易者。
 *
 * <p>MVP 作为基础 Villager-like NPC，用于触发外交烙印与对话。
 */
public class QianxiangAbyssMerchant extends QianxiangNPCBase {
    public QianxiangAbyssMerchant(EntityType<? extends QianxiangAbyssMerchant> type, Level level) {
        super(type, level);
    }

    @Override
    protected String getDialogPrefix() {
        return "qianxiang.npc.abyss_merchant";
    }

    @Override
    protected String getPriceHintKey() {
        return "qianxiang.npc.abyss_merchant.price";
    }
}
