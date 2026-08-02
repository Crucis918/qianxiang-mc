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
 * 牵响客户端杂项配置（{@code config/qianxiang-client.json}，dev 即 {@code run/config/}）。
 * <p>
 * 字段：
 * <ul>
 *   <li>{@code weapon_3d} —— 武器手持 3D 挤出开关，<b>默认 false</b>（实机反馈手持像
 *       「玻璃板」：32×32 贴图精致但逐像素挤出观感差）。false = 手持也走 2D 片
 *       （原版手持观感）；true = 保留按形态挤出的 3D 几何（想尝鲜可开）。</li>
 * </ul>
 * 纯客户端配置，{@link #get()} 懒加载单例（启动读一次，不每帧读盘）。
 * 风格照 {@link CombatCameraConfig}。
 * </p>
 */
public final class QianxiangClientConfig {

    /** 手持 3D 挤出默认关（玻璃板观感投诉）。 */
    public static final boolean DEFAULT_WEAPON_3D = false;

    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("qianxiang-client.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String DEFAULT_FILE = """
            {
              // 牵响 客户端配置
              // weapon_3d: 武器手持 3D 挤出。false = 手持走 2D 片（原版观感，默认）；
              //            true = 按形态挤出的 3D 几何（逐像素厚度，尝鲜向）
              "weapon_3d": false,
              // 配置格式版本：用于一次性迁移（勿手改）
              "config_version": 1
            }
            """;

    public boolean weapon3d = DEFAULT_WEAPON_3D;

    private static QianxiangClientConfig instance;

    private QianxiangClientConfig() {}

    /** 懒加载单例（首次调用读盘并缓存；启动一次，不每帧读）。 */
    public static synchronized QianxiangClientConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** 强制从磁盘重读（外部改了 json 后调用）。 */
    public static synchronized QianxiangClientConfig reload() {
        instance = load();
        return instance;
    }

    private static QianxiangClientConfig load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(CONFIG_PATH, DEFAULT_FILE, StandardCharsets.UTF_8);
                Qianxiang.LOGGER.info("[Qianxiang] 已生成默认客户端配置 {}", CONFIG_PATH);
                return new QianxiangClientConfig();
            }
            return parse(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Qianxiang.LOGGER.warn("[Qianxiang] 客户端配置读取失败，使用默认值：{}",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return new QianxiangClientConfig();
        }
    }

    /** 解析配置文本（抽成 static 便于直接驱动测试；坏字段回退默认）。 */
    public static QianxiangClientConfig parse(String text) {
        QianxiangClientConfig cfg = new QianxiangClientConfig();
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(true);
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("weapon_3d")) {
                cfg.weapon3d = json.get("weapon_3d").getAsBoolean();
            }
        } catch (Exception e) {
            Qianxiang.LOGGER.warn("[Qianxiang] 客户端配置解析失败，使用默认值：{}",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return cfg;
    }
}
