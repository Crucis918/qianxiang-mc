package com.qianxiang.client;

import com.qianxiang.network.AiConfigSyncPayload;

/**
 * 客户端缓存的服务端 AI 配置（由 {@code AiConfigSyncPayload} S2C 推送/保存回执更新）。
 * <p>
 * 仅用于「AI 设置」界面回填当前生效值；真正的读写都在服务端
 * {@code config/qianxiang-ai.json}。专用服务器上客户端收不到包时（未同步前）
 * 界面回退显示本地默认配置。
 */
public final class ClientAIConfigCache {

    private static volatile AiConfigSyncPayload last;

    private ClientAIConfigCache() {}

    public static void receive(AiConfigSyncPayload payload) {
        last = payload;
    }

    /** 服务端推送的配置；从未收到（如刚进服瞬间）返回 null。 */
    public static AiConfigSyncPayload get() {
        return last;
    }

    /** 保存按钮乐观更新：发包前先更新本地缓存，界面立即反映新值。 */
    public static void update(AiConfigSyncPayload payload) {
        last = payload;
    }
}
