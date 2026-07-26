package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.ai.AIConfig;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * AI 配置同步的服务端逻辑。
 * <ul>
 *   <li>C2S：玩家（单机存档主人或 OP）在「AI 设置」界面保存 →
 *       {@link AIConfig#applyAndSave} 写回 {@code config/qianxiang-ai.json} 并立即生效，
 *       随后把生效配置回发给该客户端作确认。</li>
 *   <li>玩家登录时推送当前配置，让客户端「AI 设置」界面能回填真实生效值。</li>
 * </ul>
 * 配置只存服务端 config 目录；单机 = 整合服务端，本地生效。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class AiConfigSyncHandler {

    private AiConfigSyncHandler() {}

    /** C2S 保存：校验权限 → 写 json → 回发生效配置。 */
    public static void handleSave(AiConfigSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer player)) return;
                // 专用服务器上只允许 OP（2 级）改 AI 配置；单机存档主人总是放行
                if (!player.hasPermissions(2)
                        && !player.getServer().isSingleplayerOwner(player.getGameProfile())) {
                    Qianxiang.LOGGER.warn("[Qianxiang] 玩家 {} 无权限修改 AI 配置，已忽略。",
                            player.getGameProfile().getName());
                    return;
                }
                // 客户端界面对已有 Key 显示的是占位符（见 currentPayload）；原样回传占位符
                // 表示「不改 Key」，此时保留服务端现有值，避免把真 Key 洗成字面量占位符。
                String apiKey = MASKED_API_KEY.equals(payload.apiKey())
                        ? AIConfig.get().apiKey
                        : payload.apiKey();
                AIConfig.applyAndSave(payload.provider(), payload.baseUrl(),
                        apiKey, payload.model(), payload.timeoutSeconds());
                // 回发落盘后的实际生效值（含 sanitize 结果），客户端缓存作确认
                context.reply(currentPayload(player));
            } catch (Throwable t) {
                Qianxiang.LOGGER.warn("[Qianxiang] 保存 AI 配置失败：{}",
                        t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    /** 玩家进服时把当前 AI 配置推给客户端。 */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, currentPayload(player));
            }
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] AI 配置登录同步失败：{}",
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * 当前服务端生效配置 → 同步包。
     * <p><b>apiKey 只发给有权改配置的人</b>（OP 2 级 / 单机存档主人）。
     * 其余玩家收到 {@value #MASKED_API_KEY} 占位符——否则专用服务器上服主的付费
     * API Key 会随登录包明文发给每一个进服玩家（抓包或直接看 AI 设置界面即可读到）。
     * 写路径本就有权限校验，读路径此前完全没有，权限模型只做了一半。
     */
    public static AiConfigSyncPayload currentPayload(ServerPlayer viewer) {
        AIConfig cfg = AIConfig.get();
        return new AiConfigSyncPayload(cfg.provider, cfg.baseUrl,
                canSeeApiKey(viewer) ? cfg.apiKey : maskedFor(cfg.apiKey),
                cfg.model, cfg.timeoutSeconds);
    }

    /** 只有 OP(2) 或单机存档主人能看到/修改真实 apiKey。 */
    public static boolean canSeeApiKey(ServerPlayer player) {
        if (player == null) return false;
        return player.hasPermissions(2)
                || (player.getServer() != null
                        && player.getServer().isSingleplayerOwner(player.getGameProfile()));
    }

    /** 已设置 Key 时回占位符（让界面显示"已配置"），未设置时回空串。 */
    private static String maskedFor(String realKey) {
        return realKey == null || realKey.isEmpty() ? "" : MASKED_API_KEY;
    }

    /** apiKey 占位符：客户端原样回传即表示「不修改」。 */
    public static final String MASKED_API_KEY = "********";
}
