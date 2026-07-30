package com.qianxiang.network;

import com.qianxiang.cap.ProficiencyHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端处理熟练度相关 C2S 包：分配节点 / 洗点 / 主动技能。
 */
public final class ProficiencyHandlers {

    private ProficiencyHandlers() {}

    /** 洗点节流。 */
    private static final long RESPEC_COOLDOWN_MS = 500L;
    /** 主动技能节流（战吼/涌动自身有长冷却，这里只防刷包）。 */
    private static final long SKILL_COOLDOWN_MS = 500L;

    public static void handleAllocate(AllocateNodePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ProficiencyHelper.allocate(player, payload.nodeId());
            }
        });
    }

    public static void handleRespec(RespecPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "proficiency_respec", RESPEC_COOLDOWN_MS)) {
                return;
            }
            ProficiencyHelper.respec(player);
        });
    }

    public static void handleActivateSkill(ActivateSkillPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "proficiency_skill", SKILL_COOLDOWN_MS)) {
                return;
            }
            switch (payload.skillId()) {
                case "warcry" -> ProficiencyHelper.activateWarCry(player);
                case "surge" -> ProficiencyHelper.activateSurge(player);
                default -> com.qianxiang.Qianxiang.LOGGER.warn(
                        "[Qianxiang] 玩家 {} 请求未知主动技能 {}，已忽略",
                        player.getName().getString(), payload.skillId());
            }
        });
    }

    /** 主职业设定/转职：模板 id 优先（服务端查表），否则自定义三元组；校验在 setClassCore 内。 */
    public static void handleSetClassCore(SetClassCorePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "class_core", RESPEC_COOLDOWN_MS)) {
                return;
            }
            com.qianxiang.cap.ClassCore core = payload.templateId().isEmpty()
                    ? new com.qianxiang.cap.ClassCore(payload.elementA(), payload.elementB(), payload.form())
                    : com.qianxiang.cap.ClassCore.template(payload.templateId());
            if (core == null || !core.valid()) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "qianxiang.classcore.invalid"), true);
                return;
            }
            com.qianxiang.cap.ClassCoreHelper.setClassCore(player, core,
                    payload.templateId() == null ? "" : payload.templateId());
        });
    }
}
