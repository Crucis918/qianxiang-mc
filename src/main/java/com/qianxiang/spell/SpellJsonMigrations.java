package com.qianxiang.spell;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.qianxiang.Qianxiang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * spellJson 的版本迁移注册表。
 * <p>
 * 背景：spellJson 是跨版本流动的数据——它躺在玩家存档的物品组件里、蓝图分享码里、
 * 服务器工坊的 SavedData 里。schema 已经演化过一次（修饰词从 {@code duration/empower}
 * 改名为 {@code extended/amplified}），当时是把折算逻辑直接写死在解析函数里的。
 * 再演化一次就会变成一堆互相纠缠的 if。
 * <p>
 * 这里把「每一次 schema 变更」显式登记为一步迁移：解析时按数据自带的版本号
 * 依次应用到当前版本。规则：
 * <ul>
 *   <li>无 {@code v} 字段 = {@link #V0_LEGACY}（历史数据）；</li>
 *   <li>版本高于 {@link #CURRENT_VERSION} = 由更新的模组产出，<b>不猜</b>，
 *       调用方应提示玩家升级而不是静默降级解析；</li>
 *   <li>每步迁移必须是纯函数且幂等，失败时原样返回（宁可少改不可改坏）。</li>
 * </ul>
 */
public final class SpellJsonMigrations {

    /** 历史数据（无版本字段）：修饰词还用 duration/empower 等旧词。 */
    public static final int V0_LEGACY = 0;

    /** 当前 schema 版本。新增一步迁移时 +1 并在 {@link #MIGRATIONS} 登记。 */
    public static final int CURRENT_VERSION = 1;

    /** 版本字段名。 */
    public static final String VERSION_KEY = "v";

    /** 历史修饰词 → 现行修饰词。 */
    private static final Map<String, String> V0_TO_V1_MODIFIERS = Map.of(
            "duration", "extended",
            "empower", "amplified",
            "strengthen", "amplified",
            "prolong", "extended"
    );

    /** 迁移表：key = 起始版本，value = 把该版本数据升到 key+1 的函数。 */
    private static final Map<Integer, UnaryOperator<JsonObject>> MIGRATIONS = new LinkedHashMap<>();

    static {
        MIGRATIONS.put(V0_LEGACY, SpellJsonMigrations::v0ToV1);
    }

    private SpellJsonMigrations() {}

    /** 读取数据自带的版本号；无字段视为 {@link #V0_LEGACY}。 */
    public static int versionOf(JsonObject obj) {
        try {
            if (obj != null && obj.has(VERSION_KEY) && obj.get(VERSION_KEY).isJsonPrimitive()) {
                return obj.get(VERSION_KEY).getAsInt();
            }
        } catch (Exception ignored) {
            // 字段存在但不是数字：当作历史数据处理
        }
        return V0_LEGACY;
    }

    /**
     * 把 spellJson 对象升级到当前版本。
     *
     * @return 升级后的对象；若数据版本高于本模组所知（未来版本）返回 null，
     *         调用方应据此提示玩家升级模组，而不是按旧规则误解析。
     */
    public static JsonObject migrateToCurrent(JsonObject obj) {
        if (obj == null) {
            return null;
        }
        int version = versionOf(obj);
        if (version > CURRENT_VERSION) {
            return null;   // 来自更新的模组，不猜
        }
        JsonObject current = obj;
        while (version < CURRENT_VERSION) {
            UnaryOperator<JsonObject> step = MIGRATIONS.get(version);
            if (step == null) {
                Qianxiang.LOGGER.warn("[Qianxiang] spellJson 缺少 v{}→v{} 的迁移步骤，按原样解析",
                        version, version + 1);
                break;
            }
            try {
                current = step.apply(current);
            } catch (Throwable t) {
                Qianxiang.LOGGER.warn("[Qianxiang] spellJson v{} 迁移失败，按原样解析：{}",
                        version, t.toString());
                break;
            }
            version++;
        }
        current.addProperty(VERSION_KEY, CURRENT_VERSION);
        return current;
    }

    /** v0 → v1：修饰词旧名折算为现行白名单词。 */
    private static JsonObject v0ToV1(JsonObject obj) {
        if (!obj.has("modifiers") || !obj.get("modifiers").isJsonArray()) {
            return obj;
        }
        JsonArray original = obj.getAsJsonArray("modifiers");
        List<String> renamed = new ArrayList<>(original.size());
        for (JsonElement el : original) {
            String raw;
            try {
                raw = el.getAsString();
            } catch (Exception e) {
                continue;   // 非字符串项直接丢弃
            }
            String key = raw.trim().toLowerCase(java.util.Locale.ROOT);
            renamed.add(V0_TO_V1_MODIFIERS.getOrDefault(key, key));
        }
        JsonArray out = new JsonArray();
        renamed.forEach(out::add);
        obj.add("modifiers", out);
        return obj;
    }
}
