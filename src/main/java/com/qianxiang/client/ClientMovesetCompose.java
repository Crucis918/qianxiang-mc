package com.qianxiang.client;

/**
 * 「AI 编排」结果的客户端暂存：编辑器每帧 consume 一次回填序列。
 * 无监听器模式——结果只服务当前打开的编辑器，丢了重按即可。
 */
public final class ClientMovesetCompose {

    private ClientMovesetCompose() {}

    private static volatile String lastJson = null;

    public static void receive(String movesetJson) {
        lastJson = movesetJson;
    }

    /** 取走并清空最新结果（无结果/空串返回 null）。 */
    public static String consume() {
        String json = lastJson;
        lastJson = null;
        return json == null || json.isEmpty() ? null : json;
    }

    /** 切换世界/断线清空（ClientStateReset 登记）。 */
    public static void clear() {
        lastJson = null;
    }
}
