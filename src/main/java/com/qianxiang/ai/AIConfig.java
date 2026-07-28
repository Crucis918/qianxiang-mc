package com.qianxiang.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.qianxiang.Qianxiang;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 千相 AI 服务配置（{@code config/qianxiang-ai.json}）。
 * <p>
 * 字段：
 * <ul>
 *   <li>{@code provider} —— "ollama"（本地 Ollama，走 /api/chat）或
 *       "openai"（任意兼容 OpenAI 格式的服务，走 {baseUrl}/v1/chat/completions）</li>
 *   <li>{@code baseUrl} —— 服务地址（Ollama 默认 http://localhost:11434）</li>
 *   <li>{@code apiKey} —— OpenAI 兼容服务的 Key；本地无鉴权服务（如 LM Studio）留空</li>
 *   <li>{@code model} —— 模型名</li>
 *   <li>{@code timeoutSeconds} —— 单次请求超时秒数</li>
 * </ul>
 * <p>
 * 配置存<b>服务端</b> config 目录：AI 调用发生在服务端（{@link ForgeTableAIHandler} 后台线程），
 * 单机即整合服务端，本地生效。客户端通过 {@code AiConfigSyncPayload} 查看/修改。
 * <p>
 * 静态 {@link #get()} 懒加载，{@link #reload()} 强制重读；读写全部 try-catch，
 * 文件损坏/缺失一律回退默认值，绝不向上抛。
 */
public final class AIConfig {

    /** 默认 provider：保持旧版 Ollama 体验不变。 */
    public static final String DEFAULT_PROVIDER = "ollama";
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_API_KEY = "";
    public static final String DEFAULT_MODEL = "qwen2.5:7b";
    /**
     * 默认超时。30 秒而非 5 秒：本地 7B 模型在完整系统 prompt 下首 token 就要数秒，
     * 5 秒等于「必然超时 → 每次都白等满时长再退兜底」，AI 功能形同虚设。
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** provider 合法取值。 */
    public static final String PROVIDER_OLLAMA = "ollama";
    public static final String PROVIDER_OPENAI = "openai";

    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("qianxiang-ai.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 首次启动生成的默认文件内容（带 // 注释，读取时用 lenient 模式兼容）。 */
    private static final String DEFAULT_FILE = """
            {
              // 千相 AI 服务配置
              // provider: "ollama" = 本地 Ollama（走 /api/chat）；
              //           "openai" = 任意兼容 OpenAI 格式的服务（走 {base_url}/v1/chat/completions，
              //                      如 OpenAI 官方、LM Studio、DeepSeek、通义千问兼容端点等）
              "provider": "ollama",
              // 服务地址。Ollama 默认 http://localhost:11434；
              // OpenAI 官方 https://api.openai.com；LM Studio 默认 http://localhost:1234
              "base_url": "http://localhost:11434",
              // OpenAI 兼容服务的 API Key。本地无鉴权服务（如 LM Studio）留空即可。
              "api_key": "",
              // 模型名。Ollama 填本地 pull 过的模型；OpenAI 兼容服务填其模型 id。
              "model": "qwen2.5:7b",
              // 单次请求超时（秒）。本地 7B 模型在完整 prompt 下首 token 就要数秒，
              // 5 秒等于必然超时、每次白等——默认 30。
              "timeout_seconds": 30,
              // 配置格式版本：用于一次性迁移（勿手改）
              "config_version": 1
            }
            """;

    public String provider = DEFAULT_PROVIDER;
    public String baseUrl = DEFAULT_BASE_URL;
    public String apiKey = DEFAULT_API_KEY;
    public String model = DEFAULT_MODEL;
    public int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    private static AIConfig instance;

    private AIConfig() {}

    /** 懒加载单例。首次调用时若配置文件不存在则生成带注释的默认文件。 */
    public static synchronized AIConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** 强制从磁盘重读（外部改了 json 后调用）。 */
    public static synchronized AIConfig reload() {
        instance = load();
        return instance;
    }

    /** 应用一组新值并写回 json（服务端收到客户端配置包时调用）。 */
    public static synchronized void applyAndSave(String provider, String baseUrl, String apiKey,
                                                 String model, int timeoutSeconds) {
        AIConfig cfg = get();
        cfg.provider = sanitizeProvider(provider);
        cfg.baseUrl = sanitizeBaseUrl(baseUrl);
        cfg.apiKey = apiKey == null ? "" : apiKey.trim();
        cfg.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        cfg.timeoutSeconds = sanitizeTimeout(timeoutSeconds);
        cfg.save();
    }

    public boolean isOpenAI() {
        return PROVIDER_OPENAI.equals(provider);
    }

    /** 归一化 baseUrl：去尾部斜杠，空值回退默认。 */
    public String normalizedBaseUrl() {
        return sanitizeBaseUrl(baseUrl);
    }

    /** 写回当前值到 json（不带注释的标准 JSON，保留字段值；带 config_version 供迁移判定）。 */
    public synchronized void save() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("provider", provider);
            json.addProperty("base_url", baseUrl);
            json.addProperty("api_key", apiKey);
            json.addProperty("model", model);
            json.addProperty("timeout_seconds", timeoutSeconds);
            json.addProperty("config_version", CONFIG_VERSION);
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(json), StandardCharsets.UTF_8);
            Qianxiang.LOGGER.info("[Qianxiang] AI 配置已保存到 {}", CONFIG_PATH);
        } catch (Exception e) {
            Qianxiang.LOGGER.warn("[Qianxiang] AI 配置写入失败：{}",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 当前配置格式版本。 */
    public static final int CONFIG_VERSION = 1;

    private static AIConfig load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(CONFIG_PATH, DEFAULT_FILE, StandardCharsets.UTF_8);
                Qianxiang.LOGGER.info("[Qianxiang] 已生成默认 AI 配置 {}", CONFIG_PATH);
                return new AIConfig();
            }
            return parse(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8), CONFIG_PATH);
        } catch (Exception e) {
            // 文件损坏/读取失败：用默认值兜底，不覆盖玩家文件
            Qianxiang.LOGGER.warn("[Qianxiang] AI 配置读取失败，使用默认值：{}",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return new AIConfig();
        }
    }

    /**
     * 解析配置文本（含一次性迁移）。抽出来便于 GameTest 直接驱动：
     * 旧文件（无 {@code config_version} 且 {@code timeout_seconds} ≤ 10）是
     * 「模板写死 5 秒」时代的遗留——那时常量改成 30 也救不回来，这里抬到 30
     * 并写回（带 config_version，只迁移一次）；有版本号的文件尊重玩家现值。
     */
    public static AIConfig parse(String text, Path writeBackPath) {
        AIConfig cfg = new AIConfig();
        try {
            // lenient：兼容默认文件里的 // 注释与玩家手改时的尾逗号
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(true);
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("provider")) cfg.provider = sanitizeProvider(json.get("provider").getAsString());
            if (json.has("base_url")) cfg.baseUrl = sanitizeBaseUrl(json.get("base_url").getAsString());
            if (json.has("api_key")) cfg.apiKey = json.get("api_key").getAsString();
            if (json.has("model")) {
                String m = json.get("model").getAsString();
                cfg.model = (m == null || m.isBlank()) ? DEFAULT_MODEL : m.trim();
            }
            if (json.has("timeout_seconds")) {
                cfg.timeoutSeconds = sanitizeTimeout(json.get("timeout_seconds").getAsInt());
            }
            boolean hasVersion = json.has("config_version");
            if (!hasVersion && cfg.timeoutSeconds <= 10) {
                cfg.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
                Qianxiang.LOGGER.info("[Qianxiang] AI 配置一次性迁移：timeout_seconds 抬到 {} 秒"
                        + "（旧默认 5 秒必然超时），已写回 {}", DEFAULT_TIMEOUT_SECONDS, writeBackPath);
                writeBack(cfg, writeBackPath);
            }
        } catch (Exception e) {
            Qianxiang.LOGGER.warn("[Qianxiang] AI 配置解析失败，使用默认值：{}",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return cfg;
    }

    /** 迁移写回（保留解析后的全部字段 + config_version）。 */
    private static void writeBack(AIConfig cfg, Path path) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("provider", cfg.provider);
            json.addProperty("base_url", cfg.baseUrl);
            json.addProperty("api_key", cfg.apiKey);
            json.addProperty("model", cfg.model);
            json.addProperty("timeout_seconds", cfg.timeoutSeconds);
            json.addProperty("config_version", CONFIG_VERSION);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Qianxiang.LOGGER.warn("[Qianxiang] AI 配置迁移写回失败：{}", e.toString());
        }
    }

    public static String sanitizeProvider(String provider) {
        if (provider == null) return DEFAULT_PROVIDER;
        String p = provider.trim().toLowerCase(Locale.ROOT);
        return PROVIDER_OPENAI.equals(p) ? PROVIDER_OPENAI : PROVIDER_OLLAMA;
    }

    private static String sanitizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return DEFAULT_BASE_URL;
        String u = baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.isEmpty() ? DEFAULT_BASE_URL : u;
    }

    private static int sanitizeTimeout(int timeoutSeconds) {
        return Math.clamp(timeoutSeconds, 1, 120);
    }
}
