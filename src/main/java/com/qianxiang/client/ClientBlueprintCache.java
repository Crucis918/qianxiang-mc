package com.qianxiang.client;

import com.qianxiang.blueprint.BlueprintData;
import com.qianxiang.network.BlueprintSyncPayload;

import java.util.List;
import java.util.function.Consumer;

/**
 * 客户端缓存玩家蓝图列表。
 * <p>
 * 收到 {@link BlueprintSyncPayload} 后更新缓存，并通过回调通知当前打开的
 * {@link ForgeTableScreen} 刷新蓝图面板。
 */
public final class ClientBlueprintCache {

    private static volatile List<BlueprintData> blueprints = List.of();
    private static Consumer<List<BlueprintData>> onSync = null;

    private ClientBlueprintCache() {}

    public static void receive(BlueprintSyncPayload payload) {
        blueprints = List.copyOf(payload.blueprints());
        if (onSync != null) {
            onSync.accept(blueprints);
        }
    }

    public static void setListener(Consumer<List<BlueprintData>> listener) {
        onSync = listener;
        if (blueprints != null && !blueprints.isEmpty() && listener != null) {
            listener.accept(blueprints);
        }
    }

    public static void clearListener() {
        onSync = null;
    }

    public static List<BlueprintData> get() {
        return blueprints;
    }
}
