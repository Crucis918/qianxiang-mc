package com.qianxiang.client;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 对话面板的会话级聊天记录（锻造台/炼金台各一条，重开界面保留、换世界清空）。
 * <p>
 * 纯逻辑可测：{@link #add}（用户气泡右对齐 / AI 气泡左对齐，fallback 标记显示
 * 「AI 离线·关键词配方」）、{@link #trim}（保留最近 {@link #MAX} 条）、{@link #clear}。
 * </p>
 */
public final class AiChatLog {

    /** 历史上限（超出丢最旧）。 */
    public static final int MAX = 6;

    /** 一条消息：user=true 用户气泡（右），false AI 气泡（左）；fallback=兜底产出。 */
    public record Msg(boolean user, String text, boolean fallback) {}

    /** 锻造台会话。 */
    public static final AiChatLog FORGE = new AiChatLog();
    /** 炼金台会话。 */
    public static final AiChatLog ALCHEMY = new AiChatLog();

    private final List<Msg> msgs = new ArrayList<>();

    /** 追加一条（自动 trim 到上限）。 */
    public void add(Msg msg) {
        if (msg == null) return;
        msgs.add(msg);
        trim(msgs, MAX);
    }

    public void addUser(String text) {
        add(new Msg(true, text, false));
    }

    public void addAi(String text, boolean fallback) {
        add(new Msg(false, text, fallback));
    }

    public List<Msg> msgs() {
        return List.copyOf(msgs);
    }

    public boolean isEmpty() {
        return msgs.isEmpty();
    }

    public void clear() {
        msgs.clear();
    }

    /** 截断到 max 条（丢最旧；抽成静态便于 GameTest 直接驱动）。 */
    public static <T> void trim(List<T> list, int max) {
        while (list.size() > max) {
            list.remove(0);
        }
    }
}
