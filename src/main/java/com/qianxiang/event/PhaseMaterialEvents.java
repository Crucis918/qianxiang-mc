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
            PacketDistributor.sendToPlayer(event.getPlayer(), payload);
        } else {
            for (var player : event.getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }
}
