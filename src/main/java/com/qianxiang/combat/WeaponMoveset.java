package com.qianxiang.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qianxiang.Qianxiang;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 武器动作集（Epic Fight moveset）：AI 从 {@link AnimationLibrary} 挑选的动画组合，
 * 存于产物的 {@code qianxiang:custom_moveset} 数据组件（见 QianxiangDataComponents）。
 *
 * <h3>契约字段（生成/应用两侧共用，字段名不得改动）</h3>
 * <ul>
 *   <li>{@code category}：武器动作类别（tachi/longsword/sword/dagger/axe/greatsword/fist/
 *       uchigatana/trident/spear 等），应用侧据此选 EF 动作原型/姿态。</li>
 *   <li>{@code combos}：连击动画 id 列表（{@code epicfight:biped/combat/...}），按衔接顺序排列，
 *       应用侧依次注册为 EF 连段。</li>
 *   <li>{@code colliderPreset}：EF 碰撞箱预设名（如 tachi/longsword/dagger/greatsword），
 *       决定攻击判定范围。</li>
 * </ul>
 *
 * <h3>movesetJson 格式（AI 输出，{@link #fromJson(String)} 解析）</h3>
 * <pre>{@code {"category":"tachi","combos":["tachi_auto1","tachi_auto2","tachi_dash"],"collider":"tachi"}}</pre>
 * <ul>
 *   <li>{@code combos} 元素可写完整 id、{@code epicfight:xxx} 或裸名 {@code tachi_auto1}，
 *       后两者自动补全为 {@code epicfight:biped/combat/xxx}。</li>
 *   <li>容错：JSON 非法或解析后无任何有效动画 id 时返回 null（调用方回退 {@link #tachiDefault()}）；
 *       单条非法 id 跳过，不影响其余。</li>
 * </ul>
 */
public record WeaponMoveset(String category, List<ResourceLocation> combos, String colliderPreset) {

    /** 缺省类别/碰撞箱预设：太刀（与 QianxiangEFCompat 的标准刃兜底一致）。 */
    public static final String DEFAULT_CATEGORY = "tachi";

    public WeaponMoveset {
        if (category == null || category.isBlank()) category = DEFAULT_CATEGORY;
        combos = combos == null ? List.of() : List.copyOf(combos);
        if (colliderPreset == null || colliderPreset.isBlank()) colliderPreset = category;
    }

    public static final Codec<WeaponMoveset> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("category", DEFAULT_CATEGORY).forGetter(WeaponMoveset::category),
            ResourceLocation.CODEC.listOf().optionalFieldOf("combos", List.of()).forGetter(WeaponMoveset::combos),
            Codec.STRING.optionalFieldOf("collider", DEFAULT_CATEGORY).forGetter(WeaponMoveset::colliderPreset)
    ).apply(instance, WeaponMoveset::new));

    /** 网络编解码：3 个字段用 composite（字段顺序与 {@link #CODEC} 一致）。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponMoveset> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, WeaponMoveset::category,
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), WeaponMoveset::combos,
            ByteBufCodecs.STRING_UTF8, WeaponMoveset::colliderPreset,
            WeaponMoveset::new);

    // ============================ 默认动作集 ============================

    /**
     * 默认太刀动作集：太刀三连 + 突进，碰撞箱 tachi。
     * 与 QianxiangEFCompat 的标准金属刃兜底一致，旧产物/解析失败时的安全回退。
     */
    public static WeaponMoveset tachiDefault() {
        return new WeaponMoveset(DEFAULT_CATEGORY, List.of(
                AnimationLibrary.fullId("tachi_auto1"),
                AnimationLibrary.fullId("tachi_auto2"),
                AnimationLibrary.fullId("tachi_auto3"),
                AnimationLibrary.fullId("tachi_dash")),
                DEFAULT_CATEGORY);
    }

    // ============================ AI movesetJson 解析 ============================

    /**
     * 把 AI 输出的 movesetJson 解析为 {@link WeaponMoveset}。
     *
     * @param movesetJson AI 响应附带的动作描述 JSON（可空/可空白）
     * @return 解析结果；JSON 非法或没有任何有效动画 id 时返回 null（调用方回退 {@link #tachiDefault()}）
     */
    public static WeaponMoveset fromJson(String movesetJson) {
        if (movesetJson == null || movesetJson.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(movesetJson.trim());
            if (el == null || !el.isJsonObject()) return null;
            JsonObject obj = el.getAsJsonObject();

            String category = optString(obj, "category");
            if (category.isEmpty()) category = DEFAULT_CATEGORY;
            String collider = optString(obj, "collider");
            if (collider.isEmpty()) collider = category;

            List<ResourceLocation> combos = new ArrayList<>();
            if (obj.has("combos") && obj.get("combos").isJsonArray()) {
                for (JsonElement e : obj.getAsJsonArray("combos")) {
                    if (!e.isJsonPrimitive()) continue;
                    ResourceLocation id = parseAnimId(e.getAsString());
                    if (id != null) combos.add(id);
                }
            }
            if (combos.isEmpty()) return null;
            return new WeaponMoveset(category, combos, collider);
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] movesetJson 解析失败（回退默认太刀动作集）：{}",
                    t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static String optString(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) return "";
        String v = obj.get(key).getAsString();
        return v == null ? "" : v.trim();
    }

    /**
     * 容错解析动画 id：裸名（tachi_auto1）与 epicfight:xxx 都补全为
     * {@code epicfight:biped/combat/xxx}；非法 id 返回 null。
     */
    private static ResourceLocation parseAnimId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.contains(":")) {
            s = AnimationLibrary.EF_NAMESPACE + ":" + AnimationLibrary.EF_PATH_PREFIX + s;
        } else if (s.startsWith(AnimationLibrary.EF_NAMESPACE + ":")
                && !s.substring(AnimationLibrary.EF_NAMESPACE.length() + 1).contains("/")) {
            s = AnimationLibrary.EF_NAMESPACE + ":" + AnimationLibrary.EF_PATH_PREFIX
                    + s.substring(AnimationLibrary.EF_NAMESPACE.length() + 1);
        }
        return ResourceLocation.tryParse(s);
    }
}
