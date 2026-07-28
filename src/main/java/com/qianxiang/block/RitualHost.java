package com.qianxiang.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 承载合成仪式的方块实体接口（锻造台/炼金台同构），供 {@link RitualLogic} 无类型操作。
 * <p>
 * 读侧访问器继承自 {@link FloatingTableView}（BER 也要用）；
 * 写侧钩子由各 BE 实现，保证同步（clientSyncDirty/sendBlockUpdated）走各自既有管线。
 * </p>
 */
public interface RitualHost extends FloatingTableView {

    // ---- 状态写侧 ----
    void setRitualState(RitualState state);
    void setRitualProgress(int progress);
    void setRitualOwner(java.util.UUID owner);
    void setPendingResult(ItemStack stack);

    // ---- 组成钩子（BE 实现，内部走 setItem/字段保证同步） ----
    /** 当前 compose 预览产物（产物槽内容；空 = 不可触发仪式）。 */
    ItemStack composedResult();
    /** 材料槽非空栈全部移入 ritualInputs 并清槽（不销毁，挖台照常掉落）。 */
    void collectInputsToRitual();
    /** 清空 compose 预览（产物槽）。 */
    void clearComposedResult();
    /** DONE 时写入 displayResult（产物虚影数据源，只写字段不写产物槽）。 */
    void setDisplayResultFromRitual(ItemStack stack);
    /** 拾取后归位：state=NONE、owner=null、progress=0。 */
    void clearRitualState();
    /** 标客户端同步（sendBlockUpdated，可走同 tick 合并）。 */
    void markRitualDirty();

    /** 仪式触发后钩子（如锻造台的森罗之核传奇判定，默认空实现）。 */
    default void onRitualStarted(ServerPlayer player, ItemStack result) {}

    /** DONE 转态时记相谱（与 menu 的 onTake 同一口径）；owner 可能已下线（null）。 */
    void recordRitualCompleted(ServerPlayer owner, ItemStack result);

    // ---- 环境 ----
    Level ritualLevel();
    BlockPos ritualPos();
}
