package com.qianxiang.network;

import com.qianxiang.block.AlchemyTableBlockEntity;
import com.qianxiang.block.ForgeTableBlockEntity;
import com.qianxiang.block.RitualLogic;
import com.qianxiang.menu.AlchemyTableMenu;
import com.qianxiang.menu.ForgeTableMenu;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端处理「开始创作」（{@link RitualStartPayload}）。
 * <p>
 * 按玩家当前 openMenu 类型分派到对应 BE 调 {@link RitualLogic#startRitual}
 * （产物由服务端 compose 校验，客户端无法伪造）；500ms 节流防刷包。
 * </p>
 */
public final class RitualStartHandler {

    private RitualStartHandler() {}

    /** 同一玩家两次触发仪式的最小间隔。 */
    private static final long RITUAL_START_COOLDOWN_MS = 500L;

    public static void handle(RitualStartPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "ritual_start", RITUAL_START_COOLDOWN_MS)) {
                return;
            }
            if (player.containerMenu instanceof ForgeTableMenu forgeMenu
                    && forgeMenu.getContainer() instanceof ForgeTableBlockEntity be) {
                RitualLogic.startRitual(be, player);
            } else if (player.containerMenu instanceof AlchemyTableMenu alchemyMenu
                    && alchemyMenu.getContainer() instanceof AlchemyTableBlockEntity be) {
                RitualLogic.startRitual(be, player);
            }
        });
    }
}
