package com.qianxiang.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qianxiang.Qianxiang;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * 通用 AI HTTP 客户端（Ollama / OpenAI 兼容双协议），配置来自 {@link AIConfig}。
 * <p>
 * 只做一件事：按 provider 发一次 chat 请求，拿回模型给出的 assistant 文本。
 * <ul>
 *   <li>provider=ollama → {@code POST {baseUrl}/api/chat}（Ollama 原生格式，stream=false），
 *       解析 {@code message.content}。</li>
 *   <li>provider=openai → {@code POST {baseUrl}/v1/chat/completions}（OpenAI 格式：
 *       model/messages/temperature，stream=false），请求头带
 *       {@code Authorization: Bearer {apiKey}}（apiKey 为空则跳过该头，兼容本地无鉴权服务
 *       如 LM Studio），解析 {@code choices[0].message.content}。</li>
 *   <li>用 JDK 内置 {@link HttpClient}，不引任何第三方依赖。</li>
 *   <li>全程 try-catch，任何异常（网络/超时/JSON 解析/空响应）一律返回 {@link Optional#empty()}，
 *       <b>绝不向上抛</b>——保证服务不可达时调用方安全退 {@link FallbackRecipes}。</li>
 *   <li>不可变、线程安全：每次 chat 都构造新 HttpRequest，HttpClient 单例共享。</li>
 * </ul>
 */
public final class AIClient {

    /** Ollama 上下文窗口。默认 2048~4096 装不下系统 prompt，必须显式下发。 */
    private static final int OLLAMA_NUM_CTX = 8192;

    /** 采样温度：挑材料 id 是照抄类任务，低温更稳。 */
    private static final double TEMPERATURE = 0.2;

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /**
     * 上一次 chat() 失败是否属于<b>连接类失败</b>（连接超时/拒绝/无路由/未知主机），按线程记录。
     * 「AI 返回了内容但解析失败 / 非 2xx」不算连接类失败，该标记保持 false。
     */
    private static final ThreadLocal<Boolean> LAST_CONNECT_ISSUE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 本次失败是否为「连上了但模型太慢」的请求超时（与连接失败分开计数）。 */
    private static final ThreadLocal<Boolean> LAST_REQUEST_TIMEOUT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 本次失败是否为「端点回了非 2xx」（持续 400/500 = 端点故障，也要能熔断）。 */
    private static final ThreadLocal<Boolean> LAST_ENDPOINT_ERROR = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 最近一次非 2xx 的 HTTP 状态码（0 = 无；401/403 = 密钥无效，供界面红字提示）。 */
    private static final ThreadLocal<Integer> LAST_HTTP_STATUS = ThreadLocal.withInitial(() -> 0);

    /** 供 {@link AIGateway} 判定 lastErrorKind：仅同线程紧随一次 chat 后有意义。 */
    static int lastHttpStatus() {
        return LAST_HTTP_STATUS.get();
    }

    /**
     * 内存开关：本端点不支持 {@code response_format} 时置 true（WQ-64）。
     * 部分「OpenAI 兼容」端点（旧版 llama.cpp server、自建代理、部分国产兼容层）
     * 见到该字段直接 400——命中一次后本进程内不再携带，避免每次请求白发两发 HTTP。
     * 进程级记忆即可：端点能力是部署属性，不随存档变化；重启后重新探测一次的代价可接受。
     */
    private static volatile boolean RESPONSE_FORMAT_UNSUPPORTED = false;

    /** 供 {@link AIGateway} 熔断判定用：仅在同线程、紧随一次失败的 {@link #chat} 之后调用才有意义。 */
    static boolean lastFailureWasConnectionIssue() {
        return LAST_CONNECT_ISSUE.get();
    }

    /** 同上，但判「请求超时」。 */
    static boolean lastFailureWasRequestTimeout() {
        return LAST_REQUEST_TIMEOUT.get();
    }

    /** 同上，但判「端点非 2xx」。 */
    static boolean lastFailureWasEndpointError() {
        return LAST_ENDPOINT_ERROR.get();
    }

    private AIClient() {}

    /**
     * 按当前 {@link AIConfig} 让模型基于 systemPrompt 回答 userMessage。
     *
     * @param userMessage  玩家输入（自然语言需求）
     * @param systemPrompt 系统提示（含材料库约束 + 输出格式）
     * @return 模型回答的纯文本；连不上/超时/解析失败一律返回 {@link Optional#empty()}
     */
    public static Optional<String> chat(String userMessage, String systemPrompt) {
        return chat(AIConfig.get(), userMessage, systemPrompt);
    }

    /**
     * 显式指定配置的完整入口（生产路径是上面的两参 {@link #chat(String, String)} +
     * {@link AIConfig#get()}；本重载供 GameTest 注入指向本地桩端点的配置）。
     */
    public static Optional<String> chat(AIConfig cfg, String userMessage, String systemPrompt) {
        LAST_CONNECT_ISSUE.set(Boolean.FALSE);
        LAST_REQUEST_TIMEOUT.set(Boolean.FALSE);
        LAST_ENDPOINT_ERROR.set(Boolean.FALSE);
        LAST_HTTP_STATUS.set(0);
        try {
            if (cfg.isOpenAI()) {
                return chatOpenAI(cfg, userMessage, systemPrompt);
            }
            return chatOllama(cfg, userMessage, systemPrompt);
        } catch (Exception e) {
            // 包含：ConnectException（服务未启动）、TimeoutException、JsonSyntaxException ...
            LAST_CONNECT_ISSUE.set(isConnectionIssue(e));
            LAST_REQUEST_TIMEOUT.set(isRequestTimeout(e));
            Qianxiang.LOGGER.warn("[Qianxiang] AI({}) 调用失败，将退关键词配方：{}",
                    cfg.provider, e.getClass().getSimpleName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 是否是「端点根本连不上」这一类异常：连接超时（{@link java.net.http.HttpConnectTimeoutException}）、
     * 连接拒绝/无路由（{@link java.net.ConnectException}/{@link java.net.NoRouteToHostException}）、
     * 域名解析失败（{@link java.net.UnknownHostException}）。沿 cause 链递归判断
     * （HttpClient 常把底层 socket 异常包一层 IOException）。
     * <b>不含</b>普通请求超时 HttpTimeoutException——那说明连接已建立、只是模型慢。
     */
    /**
     * 是否是「连上了但模型太慢」的请求超时。
     * <p>与 {@link #isConnectionIssue} 分开计数：端点不可达和模型跑不完是两种故障，
     * 但对玩家的观感一样（每次白等满超时）。此前只有前者进熔断，
     * 慢模型场景下熔断永不打开，每次请求都要等满 timeout×2（本体+重试）。
     */
    private static boolean isRequestTimeout(Throwable e) {
        if (e == null) return false;
        if (e instanceof java.net.http.HttpTimeoutException) return true;
        return isRequestTimeout(e.getCause());
    }

    private static boolean isConnectionIssue(Throwable e) {
        if (e == null) return false;
        if (e instanceof java.net.http.HttpConnectTimeoutException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.NoRouteToHostException
                || e instanceof java.net.UnknownHostException) {
            return true;
        }
        Throwable cause = e.getCause();
        return cause != null && cause != e && isConnectionIssue(cause);
    }

    // ===================== Ollama 原生协议 =====================

    private static Optional<String> chatOllama(AIConfig cfg, String userMessage, String systemPrompt)
            throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("stream", false);
        // 结构化输出：本任务的回包必须是 JSON，开了它能消掉一半解析失败面
        body.addProperty("format", "json");
        body.add("messages", buildMessages(userMessage, systemPrompt));
        // 上下文窗口：Ollama 默认只有 2048~4096，不显式下发的话
        // prompt 会被静默截断（先被截掉的恰是排在后面的玩家需求）。
        // temperature 压到 0.2：挑 registryName 是「照抄」类任务，不需要创造性。
        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", OLLAMA_NUM_CTX);
        options.addProperty("temperature", TEMPERATURE);
        body.add("options", options);

        HttpResponse<String> resp = send(cfg, cfg.normalizedBaseUrl() + "/api/chat", GSON.toJson(body), null);
        if (resp == null) return Optional.empty();
        String respBody = resp.body();
        if (respBody == null || respBody.isBlank()) return Optional.empty();

        // Ollama /api/chat(stream=false) 回包形如
        // {"model":"...","message":{"role":"assistant","content":"..."},"done":true,...}
        JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
        if (!json.has("message")) return Optional.empty();
        JsonObject message = json.getAsJsonObject("message");
        if (!message.has("content")) return Optional.empty();
        String content = message.get("content").getAsString();
        return (content == null || content.isBlank()) ? Optional.empty() : Optional.of(content);
    }

    // ===================== OpenAI 兼容协议 =====================

    private static Optional<String> chatOpenAI(AIConfig cfg, String userMessage, String systemPrompt)
            throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("stream", false);
        body.addProperty("temperature", TEMPERATURE);
        // 结构化输出：要求回包是 JSON 对象（OpenAI 兼容端点通用字段）。
        // 但不支持它的「OpenAI 兼容」端点会直接 400——已探测到不支持的端点
        // 本进程内不再携带该字段（见 RESPONSE_FORMAT_UNSUPPORTED）。
        if (!RESPONSE_FORMAT_UNSUPPORTED) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
        }
        body.add("messages", buildMessages(userMessage, systemPrompt));

        // apiKey 为空 → 不带 Authorization 头（兼容本地无鉴权服务，如 LM Studio）
        String auth = (cfg.apiKey == null || cfg.apiKey.isBlank()) ? null : "Bearer " + cfg.apiKey.trim();
        String url = cfg.normalizedBaseUrl() + "/v1/chat/completions";
        HttpResponse<String> resp = sendRaw(cfg, url, GSON.toJson(body), auth);

        // 降级路径（WQ-64）：400 且错误体提及 response_format/unsupported →
        // 去掉该字段重发一次，并记住「本端点不支持结构化输出」。
        if (resp.statusCode() == 400 && !RESPONSE_FORMAT_UNSUPPORTED
                && mentionsUnsupportedResponseFormat(resp.body())) {
            RESPONSE_FORMAT_UNSUPPORTED = true;
            Qianxiang.LOGGER.warn("[Qianxiang] AI({}) 端点不支持 response_format（400），已去掉该字段重发，"
                    + "本进程内后续请求不再携带。错误体：{}",
                    cfg.provider, truncate(resp.body(), 200));
            body.remove("response_format");
            resp = sendRaw(cfg, url, GSON.toJson(body), auth);
        }
        if (resp.statusCode() / 100 != 2) {
            // 端点故障标记：持续 400/500 不是「内容不可用」，应计入熔断（WQ-63）。
            // 注意只在「最终结果」是非 2xx 时置位——降级重发成功的 400 不算端点故障。
            LAST_ENDPOINT_ERROR.set(Boolean.TRUE);
            Qianxiang.LOGGER.warn("[Qianxiang] AI({}) 非 2xx 响应 status={} body={}",
                    cfg.provider, resp.statusCode(), truncate(resp.body(), 200));
            return Optional.empty();
        }
        String respBody = resp.body();
        if (respBody == null || respBody.isBlank()) return Optional.empty();

        // OpenAI /v1/chat/completions 回包形如
        // {"choices":[{"index":0,"message":{"role":"assistant","content":"..."}}],...}
        JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
        if (!json.has("choices") || !json.get("choices").isJsonArray()) return Optional.empty();
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices.isEmpty()) return Optional.empty();
        JsonObject first = choices.get(0).getAsJsonObject();
        if (!first.has("message")) return Optional.empty();
        JsonObject message = first.getAsJsonObject("message");
        if (!message.has("content")) return Optional.empty();
        String content = message.get("content").getAsString();
        return (content == null || content.isBlank()) ? Optional.empty() : Optional.of(content);
    }

    // ===================== 公共构造/发送 =====================

    /** system + user 两条消息，两种协议通用。 */
    private static JsonArray buildMessages(String userMessage, String systemPrompt) {
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt == null ? "" : systemPrompt);
        messages.add(sys);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userMessage == null ? "" : userMessage);
        messages.add(user);
        return messages;
    }

    /**
     * 发 POST，返回原始响应（含非 2xx）——是否降级、是否计端点故障由调用方决定。
     *
     * @param authorization Authorization 头完整值（如 "Bearer sk-..."），null 表示不带该头
     */
    private static HttpResponse<String> sendRaw(AIConfig cfg, String url, String jsonBody, String authorization)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                .header("Content-Type", "application/json");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 发 POST 并校验 2xx。
     *
     * @param authorization Authorization 头完整值（如 "Bearer sk-..."），null 表示不带该头
     * @return 2xx 响应；非 2xx 打日志并返回 null
     */
    private static HttpResponse<String> send(AIConfig cfg, String url, String jsonBody, String authorization)
            throws Exception {
        HttpResponse<String> resp = sendRaw(cfg, url, jsonBody, authorization);
        if (resp.statusCode() / 100 != 2) {
            // 端点故障标记：持续 400/500 不是「内容不可用」，应计入熔断（WQ-63）
            LAST_ENDPOINT_ERROR.set(Boolean.TRUE);
            LAST_HTTP_STATUS.set(resp.statusCode());
            Qianxiang.LOGGER.warn("[Qianxiang] AI({}) 非 2xx 响应 status={} body={}",
                    cfg.provider, resp.statusCode(), truncate(resp.body(), 200));
            return null;
        }
        return resp;
    }

    /**
     * 400 错误体是否在说「不认 response_format / 参数不支持」（WQ-64 的降级判据）。
     * 各类兼容端点的措辞不一（llama.cpp、FastChat、国产兼容层），
     * 按工单口径宽松匹配 response_format 或 unsupported 即可——误判的代价只是
     * 少发一个可选字段，不会丢功能。
     */
    static boolean mentionsUnsupportedResponseFormat(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("response_format") || lower.contains("unsupported");
    }

    /** 测试入口：重置 response_format 支持探测的进程内记忆（用例间隔离）。 */
    public static void resetResponseFormatSupportForTest() {
        RESPONSE_FORMAT_UNSUPPORTED = false;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
