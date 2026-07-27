package com.qianxiang;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 代码侧成就授予 —— 供没有原版触发器的行为（蓝图分享、施法等）打点。
 * <p>
 * 对应 JSON 里用 {@code minecraft:impossible} 触发器占位，真正的授予走本类。
 * 授予失败只记 WARN，绝不影响业务主流程。
 */
public final class QianxiangAdvancements {

    /** 蓝图分享（导出/导入/发布/取用任一）。 */
    public static final String BLUEPRINT_SHARE = "story/blueprint_share";
    /** 第一次施放自由法术。 */
    public static final String FIRST_CAST = "story/first_cast";
    /** 第一次从卷轴学会法术。 */
    public static final String FIRST_SCROLL = "story/first_scroll";
    /** 用森罗之核锻出传奇相器（进程终点）。 */
    public static final String FORGE_LEGENDARY = "story/forge_legendary";

    private QianxiangAdvancements() {}

    /** 授予 {@code qianxiang:<path>} 成就（幂等：已完成直接返回）。 */
    public static void grant(ServerPlayer player, String path) {
        try {
            AdvancementHolder holder = player.server.getAdvancements()
                    .get(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, path));
            if (holder == null) {
                return;
            }
            AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
            if (progress.isDone()) {
                return;
            }
            for (String criterion : progress.getRemainingCriteria()) {
                player.getAdvancements().award(holder, criterion);
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 授予成就 {} 失败", path, t);
        }
    }
}
