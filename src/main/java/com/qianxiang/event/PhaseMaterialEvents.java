package com.qianxiang.event;

import com.qianxiang.Qianxiang;
import com.qianxiang.network.PhaseMaterialSyncPayload;
import com.qianxiang.phase.PhaseMaterialRegistry;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 数据驱动相材料的生命周期挂载：
 * <ul>
 *   <li>{@link AddReloadListenerEvent} —— 把 {@link PhaseMaterialRegistry} 挂进数据包
 *       reload 管线（服务端启动与 {@code /reload} 都会触发）。</li>
 *   <li>{@link OnDatapackSyncEvent} —— 登录 / reload 后把整表推给客户端。</li>
 * </ul>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class PhaseMaterialEvents {

    private PhaseMaterialEvents() {}

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PhaseMaterialRegistry());
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        PhaseMaterialSyncPayload payload = new PhaseMaterialSyncPayload(PhaseMaterialRegistry.all());
        if (event.getPlayer() != null) {
            sendSafely(event.getPlayer(), payload);
        } else {
            for (var player : event.getPlayerList().getPlayers()) {
                sendSafely(player, payload);
            }
        }
    }

    /**
     * 发送容错：GameTest 的假连接（EmbeddedChannel）没有协商 mod payload 通道，
     * NeoForge 会直接抛错（纯测试环境假象，与 SpellCastHandler.sync 的防御同例）；
     * 真实玩家的连接必已协商，catch 永不触发。
     */
    private static void sendSafely(net.minecraft.server.level.ServerPlayer player,
                                   PhaseMaterialSyncPayload payload) {
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 相材料同步包发送被跳过（未协商通道，测试假象）：{}",
                    t.toString());
        }
    }
}
