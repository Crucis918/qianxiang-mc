package com.qianxiang.block;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/**
 * 「漂浮材料虚影」BER 的读取视图（锻造台/炼金台 BE 同构实现）。
 * <p>
 * 只暴露客户端渲染需要的数据：材料槽副本、显示用产物、工作状态。
 * 同步链路见各 BE 的 getUpdateTag/handleUpdateTag（材料槽 + displayResult，
 * 产物槽与落盘口径一致不同步）。
 * </p>
 */
public interface FloatingTableView {

    /** 槽位数组（0..materialSlotCount()-1 材料槽，之后是产物槽）。 */
    NonNullList<ItemStack> getItems();

    /** 材料槽数（锻造 25 / 炼金 6）。 */
    int materialSlotCount();

    /** 显示用产物栈（仅渲染，非实体库存）；无产物为空栈。 */
    ItemStack getDisplayResult();

    /** 工作状态（STATE_* 常量在各 BE；1 = PARSING，BER 用来加速公转）。 */
    int getCraftingState();
}
