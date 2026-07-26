package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * 客户端静态状态的统一清理点。
 * <p>
 * 本模组有若干 {@code static} 的客户端缓存（AI 结果、法术冷却快照、伤害浮字等）。
 * 它们原本只在界面 {@code onClose} 时清理，而<b>断线、崩服、退回主菜单、切换存档
 * 都不走那条路径</b>，于是：
 * <ul>
 *   <li>在世界 A 问过 AI → 退回主菜单 → 进世界 B 打开锻造台，
 *       界面会立刻回放世界 A 的陈旧推荐，点「应用」还会把它提交给新世界的服务端；</li>
 *   <li>浮字表按 entityId 索引，旧 id 在新世界撞上别的实体会冒「幽灵伤害数字」；</li>
 *   <li>静态监听器持有整个 Screen 对象图，反复进出世界会累积泄漏。</li>
 * </ul>
 * 统一在登出/登入两个时机清空，任何一处新增静态客户端缓存都应在这里登记。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class ClientStateReset {

    private ClientStateReset() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetAll();
    }

    /** 登入时再清一次：崩溃退出等异常路径可能没触发 LoggingOut。 */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        resetAll();
    }

    private static void resetAll() {
        try {
            ClientForgeTableAI.resetForWorldChange();
            ClientSpellData.clear();
            ClientDamageNumbers.clearAll();
            ClientBlueprintCache.clear();
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 客户端状态清理失败（无害）：{}", t.toString());
        }
    }
}
