package com.qianxiang.ai;

import java.util.Optional;

/**
 * @deprecated 旧名兼容委托。实际的 AI HTTP 调用已迁移到通用客户端 {@link AIClient}
 *             （按 {@link AIConfig} 支持 ollama / openai 两种 provider）。
 *             本类仅保留 {@link #chat(String, String)} 静态入口，转发给 {@link AIClient#chat}，
 *             避免改动既有调用方（如 {@link PhaseAIRecipeService}）。
 *             旧常量仅作历史默认值参考，实际生效值以 {@code config/qianxiang-ai.json} 为准。
 */
@Deprecated
public final class OllamaClient {

    /** 旧硬编码默认值；现仅为 {@link AIConfig#DEFAULT_BASE_URL} 的别名参考。 */
    public static final String OLLAMA_URL = AIConfig.DEFAULT_BASE_URL;
    /** 旧硬编码默认模型；现仅为 {@link AIConfig#DEFAULT_MODEL} 的别名参考。 */
    public static final String MODEL = AIConfig.DEFAULT_MODEL;
    /** 旧硬编码超时；现仅为 {@link AIConfig#DEFAULT_TIMEOUT_SECONDS} 的别名参考。 */
    public static final long TIMEOUT_SECONDS = AIConfig.DEFAULT_TIMEOUT_SECONDS;

    private OllamaClient() {}

    /**
     * 转发到 {@link AIClient#chat(String, String)}：按 config/qianxiang-ai.json 的
     * provider 走 Ollama 或 OpenAI 兼容协议。
     *
     * @return 模型回答的纯文本；连不上/超时/解析失败一律返回 {@link Optional#empty()}
     */
    public static Optional<String> chat(String userMessage, String systemPrompt) {
        return AIClient.chat(userMessage, systemPrompt);
    }
}
