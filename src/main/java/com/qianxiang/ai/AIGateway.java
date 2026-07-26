package com.qianxiang.ai;

import com.google.gson.JsonObject;
import com.qianxiang.Qianxiang;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 网关 —— {@link AIClient} 之上的健壮层，AI 生态的「底座」。
 * <p>
 * 职责（对调用方透明，{@link PhaseAIRecipeService} 只把 AIClient.chat 换成本类）：
 * <ul>
 *   <li><b>缓存</b>：相同 (provider, model, baseUrl, prompt, message) 十分钟内直接回缓存——
 *       玩家反复点同一个需求不重复烧 token，LRU 上限 {@value CACHE_MAX} 条。</li>
 *   <li><b>重试</b>：首次失败自动重试一次（网络抖动/模型冷启动是最常见的失败原因）。</li>
 *   <li><b>熔断</b>：连续 {@value BREAKER_THRESHOLD} 次<b>连接类失败</b>（连接超时/拒绝，
 *       不含「AI 有响应但解析失败」）后打开熔断，{@value BREAKER_OPEN_MS} 毫秒内不发起 HTTP、
 *       直接返回 empty 让调用方走关键词兜底（省掉每次等满 timeout 的 5 秒）；
 *       窗口过后放行一个探测请求，成功即关闭熔断，仍连不上则重新计时。</li>
 *   <li><b>日志</b>：每次交互追加一行 JSON 到 {@code logs/qianxiang-ai.jsonl}
 *       （时间/provider/模型/是否缓存/是否重试/是否成功/耗时/输入摘要），
 *       整合包作者与玩家可回溯 AI 行为；超过 {@value LOG_ROTATE_BYTES} 字节自动轮转为 .old。</li>
 *   <li><b>计数</b>：请求/缓存命中/重试/失败计数与最近错误，供 {@code /qianxiang ai status}。</li>
 * </ul>
 * 与 AIClient 同约定：<b>永不抛</b>，失败返回 {@link Optional#empty()}。
 * 线程模型：调用发生在 AI 后台线程（ForgeTableAIHandler executor / 命令异步线程），
 * 缓存用同步 LRU，日志写入 synchronized——低频调用无竞争压力。
 */
public final class AIGateway {

    private static final int CACHE_MAX = 128;
    private static final long CACHE_TTL_MS = 10 * 60_000L;
    private static final long LOG_ROTATE_BYTES = 5L * 1024 * 1024;
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("qianxiang-ai.jsonl");

    private record CacheEntry(String response, long expiresAt) {}

    /** LRU：access-order LinkedHashMap，超上限逐最久未用。 */
    private static final Map<String, CacheEntry> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    // ==================== 熔断器（circuit breaker） ====================
    /** 连续多少次连接类失败后打开熔断。 */
    private static final int BREAKER_THRESHOLD = 3;
    /** 熔断打开后多久放行一个探测请求（毫秒）。 */
    private static final long BREAKER_OPEN_MS = 60_000L;
    /** 连续连接类失败计数；任何成功或非连接类失败都会清零。 */
    private static final AtomicInteger CONNECT_FAILS = new AtomicInteger();

    /** 连续「请求超时」计数（连上了但模型没在 timeout 内返回），与连接失败分开。 */
    private static final AtomicInteger TIMEOUT_FAILS = new AtomicInteger();
    /** 熔断打开时刻（System.currentTimeMillis），0 = 关闭。CAS 保证只打一条「开启」日志。 */
    private static final AtomicLong BREAKER_OPENED_AT = new AtomicLong();
    /** 窗口过后同一时刻只放行一个探测请求。 */
    private static final AtomicBoolean PROBE_IN_FLIGHT = new AtomicBoolean();
    /** 熔断打开期间被直接拦截（未发 HTTP）的请求数，供 status 展示。 */
    private static final AtomicLong BREAKER_SKIPS = new AtomicLong();

    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong RETRIES = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static volatile long lastLatencyMs = -1;
    private static volatile String lastFailure = "";

    private static final Object LOG_LOCK = new Object();

    private AIGateway() {}

    /**
     * 带缓存/重试/日志的 chat。契约同 {@link AIClient#chat}：永不抛，失败返回 empty。
     */
    public static Optional<String> chat(String userMessage, String systemPrompt) {
        boolean probe = false;
        try {
            REQUESTS.incrementAndGet();
            AIConfig cfg = AIConfig.get();
            String key = cacheKey(cfg, userMessage, systemPrompt);
            long now = System.currentTimeMillis();

            CacheEntry hit = CACHE.get(key);
            if (hit != null && hit.expiresAt() > now) {
                CACHE_HITS.incrementAndGet();
                log(cfg, userMessage, true, false, true, 0);
                return Optional.of(hit.response());
            }

            // —— 熔断检查：打开期间不发起 HTTP，直接 empty 让调用方走关键词兜底 ——
            long openedAt = BREAKER_OPENED_AT.get();
            if (openedAt != 0L) {
                if (openedAt + BREAKER_OPEN_MS > now
                        || !PROBE_IN_FLIGHT.compareAndSet(false, true)) {
                    // 窗口未到，或已有别的线程在探测：本请求直接拦截
                    BREAKER_SKIPS.incrementAndGet();
                    return Optional.empty();
                }
                probe = true; // 窗口已过，本请求作为唯一探测放行
            }

            long t0 = System.currentTimeMillis();
            Optional<String> resp = AIClient.chat(userMessage, systemPrompt);
            boolean retried = false;
            if (resp.isEmpty()) {
                recordOutcome(false, probe);
                // 熔断刚打开（含探测失败重新计时）就别再烧一次重试的超时了
                if (BREAKER_OPENED_AT.get() == 0L) {
                    retried = true;
                    RETRIES.incrementAndGet();
                    resp = AIClient.chat(userMessage, systemPrompt);
                    recordOutcome(resp.isPresent(), probe);
                }
            } else {
                recordOutcome(true, probe);
            }
            long latency = System.currentTimeMillis() - t0;
            lastLatencyMs = latency;

            if (resp.isPresent()) {
                CACHE.put(key, new CacheEntry(resp.get(), now + CACHE_TTL_MS));
            } else {
                FAILURES.incrementAndGet();
                lastFailure = "provider=" + cfg.provider + " model=" + cfg.model
                        + " @" + java.time.LocalDateTime.now().withNano(0);
            }
            log(cfg, userMessage, false, retried, resp.isPresent(), latency);
            return resp;
        } catch (Throwable t) {
            // 网关自身任何意外都不许影响调用方
            Qianxiang.LOGGER.warn("[Qianxiang] AIGateway 异常，降级直连：{}", t.toString());
            try {
                return AIClient.chat(userMessage, systemPrompt);
            } catch (Throwable t2) {
                return Optional.empty();
            }
        } finally {
            if (probe) {
                PROBE_IN_FLIGHT.set(false);
            }
        }
    }

    /**
     * 把一次真实 HTTP 尝试的结果记入熔断器（每次尝试各记一次，重试也算）。
     * 只有<b>连接类失败</b>（见 {@link AIClient#lastFailureWasConnectionIssue()}）累计计数；
     * 成功或「对端有响应但内容不可用」都会清零计数——后者说明端点是通的，不该熔断。
     *
     * @param ok    该次尝试是否拿到了内容
     * @param probe 该次尝试是否是熔断窗口后的探测请求
     */
    private static void recordOutcome(boolean ok, boolean probe) {
        boolean connectIssue = !ok && AIClient.lastFailureWasConnectionIssue();
        boolean requestTimeout = !ok && AIClient.lastFailureWasRequestTimeout();

        if (ok || (!connectIssue && !requestTimeout)) {
            // 端点是通的且响应及时（哪怕内容不可用）→ 两个计数器都清零
            CONNECT_FAILS.set(0);
            TIMEOUT_FAILS.set(0);
            if (BREAKER_OPENED_AT.get() != 0L) {
                closeBreaker();
            }
            return;
        }

        if (probe && BREAKER_OPENED_AT.get() != 0L) {
            // 熔断仍处于打开态的探测失败：重新计时
            BREAKER_OPENED_AT.set(System.currentTimeMillis());
            Qianxiang.LOGGER.info("[Qianxiang] AI 熔断探测失败（{}），继续熔断 {} 秒",
                    connectIssue ? "端点仍不可达" : "模型仍未在超时内返回", BREAKER_OPEN_MS / 1000);
            return;
        }

        // 两类故障分开计数：端点不可达 vs 模型太慢。对玩家的观感一样（每次白等满超时），
        // 但原因与建议不同，日志文案也要能区分。
        if (connectIssue) {
            int n = CONNECT_FAILS.incrementAndGet();
            TIMEOUT_FAILS.set(0);
            if (n >= BREAKER_THRESHOLD
                    && BREAKER_OPENED_AT.compareAndSet(0L, System.currentTimeMillis())) {
                Qianxiang.LOGGER.info(
                        "[Qianxiang] AI 连续 {} 次连接失败，熔断开启：{} 秒内不再发起 AI 请求，直接走关键词兜底",
                        n, BREAKER_OPEN_MS / 1000);
            }
        } else {
            int n = TIMEOUT_FAILS.incrementAndGet();
            CONNECT_FAILS.set(0);
            if (n >= BREAKER_THRESHOLD
                    && BREAKER_OPENED_AT.compareAndSet(0L, System.currentTimeMillis())) {
                Qianxiang.LOGGER.info(
                        "[Qianxiang] AI 连续 {} 次请求超时（模型太慢或 prompt 过长），熔断开启：{} 秒内直接走兜底。"
                                + "建议换更小的模型，或在 config/qianxiang-ai.json 调大 timeout_seconds",
                        n, BREAKER_OPEN_MS / 1000);
            }
        }
    }

    /** 关闭熔断并清零计数；只有真正从「开」转「关」时才打日志。 */
    private static void closeBreaker() {
        if (BREAKER_OPENED_AT.getAndSet(0L) != 0L) {
            CONNECT_FAILS.set(0);
            TIMEOUT_FAILS.set(0);
            Qianxiang.LOGGER.info("[Qianxiang] AI 端点恢复可达，熔断关闭");
        }
    }

    /** 熔断状态一行文案（/qianxiang ai status 用）。 */
    public static String breakerStatus() {
        long openedAt = BREAKER_OPENED_AT.get();
        if (openedAt == 0L) {
            return "熔断：关闭";
        }
        long remainMs = openedAt + BREAKER_OPEN_MS - System.currentTimeMillis();
        if (remainMs <= 0) {
            return "熔断：开启（窗口已到，等待下一次请求探测）";
        }
        return "熔断：开启（剩余 " + (remainMs + 999) / 1000 + "s，期间已拦截 "
                + BREAKER_SKIPS.get() + " 次请求）";
    }

    /** 状态摘要（/qianxiang ai status 用）。不含 apiKey。 */
    public static String statusSummary() {
        AIConfig cfg = AIConfig.get();
        return String.format(
                "provider=%s model=%s baseUrl=%s timeout=%ds%n"
                        + "请求 %d 次 | 缓存命中 %d | 重试 %d | 失败 %d | 缓存条目 %d%n"
                        + "最近一次真实请求耗时 %s | 最近失败 %s",
                cfg.provider, cfg.model, cfg.normalizedBaseUrl(), cfg.timeoutSeconds,
                REQUESTS.get(), CACHE_HITS.get(), RETRIES.get(), FAILURES.get(), CACHE.size(),
                lastLatencyMs < 0 ? "无" : lastLatencyMs + "ms",
                lastFailure.isEmpty() ? "无" : lastFailure);
    }

    /** 清空响应缓存（换模型/调 prompt 后测试用）。返回清掉的条数。 */
    public static int clearCache() {
        int n = CACHE.size();
        CACHE.clear();
        return n;
    }

    // ============================ 内部 ============================

    private static String cacheKey(AIConfig cfg, String userMessage, String systemPrompt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((cfg.provider + "|" + cfg.model + "|" + cfg.normalizedBaseUrl() + "|")
                    .getBytes(StandardCharsets.UTF_8));
            md.update(systemPrompt.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(userMessage.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            // SHA-256 必在；防御兜底用拼串
            return cfg.provider + "|" + cfg.model + "|" + userMessage + "|" + systemPrompt.hashCode();
        }
    }

    /** 追加一行 jsonl；任何 IO 失败只记 debug，绝不影响主流程。 */
    private static void log(AIConfig cfg, String userMessage, boolean cached, boolean retried,
                            boolean ok, long latencyMs) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("ts", java.time.Instant.now().toString());
            o.addProperty("provider", cfg.provider);
            o.addProperty("model", cfg.model);
            o.addProperty("cached", cached);
            o.addProperty("retried", retried);
            o.addProperty("ok", ok);
            o.addProperty("latency_ms", latencyMs);
            String want = userMessage == null ? "" : userMessage;
            o.addProperty("want", want.length() > 200 ? want.substring(0, 200) : want);
            String line = o + System.lineSeparator();
            synchronized (LOG_LOCK) {
                Files.createDirectories(LOG_PATH.getParent());
                if (Files.exists(LOG_PATH) && Files.size(LOG_PATH) > LOG_ROTATE_BYTES) {
                    Files.move(LOG_PATH, LOG_PATH.resolveSibling("qianxiang-ai.jsonl.old"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(LOG_PATH, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            Qianxiang.LOGGER.debug("[Qianxiang] AI 日志写入失败：{}", e.toString());
        }
    }
}
