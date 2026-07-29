package com.qianxiang.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.qianxiang.Qianxiang;
import net.neoforged.fml.loading.FMLPaths;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 战斗镜头配置（{@code config/qianxiang-camera.json}，dev 环境即 {@code run/config/} 下）。
 * <p>
 * 字段：
 * <ul>
 *   <li>{@code auto_pvp_first_person} —— PVP 自动切第一人称总开关，默认开；
 *       关掉后整套检测照旧短路（不写镜头、不发提示）。</li>
 *   <li>{@code transition_ticks} —— 状态切换信号发出后延迟多少 tick 再真正写镜头
 *       （0 = 立即；让受击/出招那一两帧先在原镜头下演完，过渡不那么生硬）。</li>
 * </ul>
 * <p>
 * 纯客户端配置（镜头只在客户端有意义）。静态 {@link #get()} 懒加载，
 * {@link #reload()} 强制重读；读写全部 try-catch，文件损坏/缺失一律回退默认值。
 * 风格照 {@link com.qianxiang.ai.AIConfig}。
 */
public final class CombatCameraConfig {

    public static final boolean DEFAULT_AUTO_PVP_FIRST_PERSON = true;
    /** 默认立即切换。 */
    public static final int DEFAULT_TRANSITION_TICKS = 0;
    /** 延迟上限 2 秒：再大就背离「战斗节奏即时反馈」的初衷了。 */
    public static final int MAX_TRANSITION_TICKS = 40;

    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("qianxiang-camera.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 首次启动生成的默认文件内容（带 // 注释，读取时用 lenient 模式兼容）。 */
    private static final String DEFAULT_FILE = """
            {
              // 牵响 战斗镜头配置（PVP 自动第一人称 / 平时与 PVE 第三人称）
              // auto_pvp_first_person: 总开关。true = 与玩家互相伤害/攻击时自动切第一人称，
              //                        脱离 PVP 后自动回第三人称；false = 完全不干涉镜头。
              "auto_pvp_first_person": true,
              // transition_ticks: 状态切换后延迟多少 tick 再切镜头（0 = 立即，最大 40）
              "transition_ticks": 0,
              // 配置格式版本：用于一次性迁移（勿手改）
              "config_version": 1
            }
            """;

    public boolean autoPvpFirstPerson = DEFAULT_AUTO_PVP_FIRST_PERSON;
    public int transitionTicks = DEFAULT_TRANSITION_TICKS;

    private static CombatCameraConfig instance;

    private CombatCameraConfig() {}

    /** 懒加载单例。首次调用时若配置文件不存在则生成带注释的默认文件。 */
    public static synchronized CombatCameraConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** 强制从磁盘重读（外部改了 json 后调用；进入世界时 handler 会调一次）。 */
    public static synchronized CombatCameraConfig reload() {
        instance = load();
        return instance;
    }

    /** 写回当前值到 json（不带注释的标准 JSON）。 */
    public synchronized void save() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("auto_pvp_first_person", autoPvpFirstPerson);
            json.addProperty("transition_ticks", transitionTicks);
            json.addProperty("config_version", 1);
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(json), StandardCharsets.UTF_8);
            Qianxiang.LOGGER.info("[Qianxiang] 战斗镜头配置已保存到 {}", CONFIG_PATH);
        } catch (Exception e) {
            Qianxiang.LOGGER.warn("[Qianxiang] 战斗镜头配置写入失败：{}",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static CombatCameraConfig load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(CONFIG_PATH, DEFAULT_FILE, StandardCharsets.UTF_8);
                Qianxiang.LOGGER.info("[Qianxiang] 已生成默认战斗镜头配置 {}", CONFIG_PATH);
                return new CombatCameraConfig();
            }
            return parse(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 文件损坏/读取失败：用默认值兜底，不覆盖玩家文件
            Qianxiang.LOGGER.warn("[Qianxiang] 战斗镜头配置读取失败，使用默认值：{}",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return new CombatCameraConfig();
        }
    }

    /** 解析配置文本。抽成 static 便于直接驱动测试；坏字段逐个回退默认。 */
    public static CombatCameraConfig parse(String text) {
        CombatCameraConfig cfg = new CombatCameraConfig();
        try {
            // lenient：兼容默认文件里的 // 注释与玩家手改时的尾逗号
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(true);
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("auto_pvp_first_person")) {
                cfg.autoPvpFirstPerson = json.get("auto_pvp_first_person").getAsBoolean();
            }
            if (json.has("transition_ticks")) {
                cfg.transitionTicks = sanitizeTransitionTicks(json.get("transition_ticks").getAsInt());
            }
        } catch (Exception e) {
            Qianxiang.LOGGER.warn("[Qianxiang] 战斗镜头配置解析失败，使用默认值：{}",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return cfg;
    }

    private static int sanitizeTransitionTicks(int ticks) {
        return Math.clamp(ticks, 0, MAX_TRANSITION_TICKS);
    }
}
