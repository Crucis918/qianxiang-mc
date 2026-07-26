package com.qianxiang.phase;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qianxiang.Qianxiang;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据驱动相材料注册表 —— UGC 生态的地基。
 * <p>
 * 数据包作者在 {@code data/<namespace>/phase_materials/*.json} 里给<b>任意物品</b>
 * （原版/千相/其他 mod）定义完整相数据，无需写一行 Java：
 * <pre>{@code
 * {
 *   "item": "minecraft:nether_star",        // 或 "items": ["a:b", "c:d"] 批量
 *   "tier": "legendary",                     // common | rare | epic | legendary（大小写不敏感，缺省 common）
 *   "phases": ["order", "transcend"],        // 相性（可空，缺省按 functions 推导）
 *   "functions": ["mana", "strength"],       // 功能算子（PhaseFunction，小写即可）
 *   "effects": {"minecraft:regeneration": 2} // 自由状态效果 → 等级（可选）
 * }
 * }</pre>
 * <p>
 * 解析优先级（{@link PhaseFunctionResolver} / {@link ItemConceptResolver}）：
 * PhaseData component（Java 注册） &gt; <b>本注册表（datapack）</b> &gt; 功能 tag &gt; 概念推导。
 * <p>
 * 生命周期：服务端 {@code AddReloadListenerEvent} 挂载，随数据包 {@code /reload} 热更新；
 * {@code OnDatapackSyncEvent} 时整表同步到客户端（锻造台客户端预览/tooltip 一致）。
 * 解析失败的文件记 WARN 并跳过，绝不拖垮数据包加载。
 */
public final class PhaseMaterialRegistry extends SimpleJsonResourceReloadListener {

    /** 数据包目录：data/&lt;namespace&gt;/phase_materials/*.json */
    public static final String DIRECTORY = "phase_materials";

    private static final Gson GSON = new Gson();

    /** 一个物品的数据包定义：相数据 + 自由效果表。 */
    public record Entry(PhaseData data, Map<ResourceLocation, Integer> effects) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                PhaseData.CODEC.fieldOf("data").forGetter(Entry::data),
                Codec.unboundedMap(ResourceLocation.CODEC, Codec.INT)
                        .optionalFieldOf("effects", Map.of()).forGetter(Entry::effects)
        ).apply(instance, Entry::new));
    }

    /** 整表 codec：物品 id → Entry（网络同步用）。 */
    public static final Codec<Map<ResourceLocation, Entry>> MAP_CODEC =
            Codec.unboundedMap(ResourceLocation.CODEC, Entry.CODEC);

    /** 当前生效的表。服务端 reload / 客户端收同步包时整体替换（volatile 原子换引用）。 */
    private static volatile Map<ResourceLocation, Entry> ENTRIES = Map.of();

    public PhaseMaterialRegistry() {
        super(GSON, DIRECTORY);
    }

    // ============================ 查询 API ============================

    /** 物品的数据包定义；无则 null。 */
    public static Entry get(Item item) {
        if (ENTRIES.isEmpty()) return null;
        return ENTRIES.get(BuiltInRegistries.ITEM.getKey(item));
    }

    /** 物品的数据包 PhaseData；无则 null。 */
    public static PhaseData phaseData(Item item) {
        Entry e = get(item);
        return e == null ? null : e.data();
    }

    /** 物品的数据包自由效果表；无则空 Map。 */
    public static Map<ResourceLocation, Integer> effects(Item item) {
        Entry e = get(item);
        return e == null ? Map.of() : e.effects();
    }

    /** 当前整表（只读，供同步包与调试）。 */
    public static Map<ResourceLocation, Entry> all() {
        return ENTRIES;
    }

    /** 客户端收到同步包时整表替换。 */
    public static void setSynced(Map<ResourceLocation, Entry> entries) {
        ENTRIES = entries == null ? Map.of() : Map.copyOf(entries);
        Qianxiang.LOGGER.info("[Qianxiang] 数据驱动相材料已同步：{} 个物品定义", ENTRIES.size());
    }

    // ============================ 加载 ============================

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        Map<ResourceLocation, Entry> out = new HashMap<>();
        int failed = 0;
        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            try {
                parseFile(file.getValue().getAsJsonObject(), out);
            } catch (Exception e) {
                failed++;
                Qianxiang.LOGGER.warn("[Qianxiang] phase_materials/{} 解析失败，已跳过：{}",
                        file.getKey(), e.getMessage());
            }
        }
        ENTRIES = Map.copyOf(out);
        Qianxiang.LOGGER.info("[Qianxiang] 数据驱动相材料已加载：{} 个物品定义（{} 个文件失败）",
                out.size(), failed);
    }

    /** 解析单个 JSON 文件，把其中定义的物品写入 out。任何格式错误抛异常由上层记 WARN。 */
    private static void parseFile(JsonObject json, Map<ResourceLocation, Entry> out) {
        List<String> itemIds = new ArrayList<>();
        if (json.has("item")) {
            itemIds.add(json.get("item").getAsString());
        }
        if (json.has("items")) {
            for (JsonElement el : json.getAsJsonArray("items")) {
                itemIds.add(el.getAsString());
            }
        }
        if (itemIds.isEmpty()) {
            throw new JsonSyntaxException("缺少 item / items 字段");
        }

        PhaseTier tier = json.has("tier")
                ? PhaseTier.valueOf(json.get("tier").getAsString().trim().toUpperCase(Locale.ROOT))
                : PhaseTier.COMMON;

        Set<PhaseFunction> functions = EnumSet.noneOf(PhaseFunction.class);
        if (json.has("functions")) {
            for (JsonElement el : json.getAsJsonArray("functions")) {
                functions.add(PhaseFunction.valueOf(el.getAsString().trim().toUpperCase(Locale.ROOT)));
            }
        }

        Set<Phase> phases = EnumSet.noneOf(Phase.class);
        if (json.has("phases")) {
            for (JsonElement el : json.getAsJsonArray("phases")) {
                phases.add(Phase.valueOf(el.getAsString().trim().toUpperCase(Locale.ROOT)));
            }
        }

        Map<ResourceLocation, Integer> effects = new HashMap<>();
        if (json.has("effects")) {
            for (Map.Entry<String, JsonElement> en : json.getAsJsonObject("effects").entrySet()) {
                ResourceLocation eid = ResourceLocation.parse(en.getKey());
                effects.put(eid, Math.clamp(en.getValue().getAsInt(), 1, 10));
            }
        }

        if (functions.isEmpty() && effects.isEmpty()) {
            throw new JsonSyntaxException("functions 与 effects 至少要有一个非空");
        }

        Set<Phase> effectivePhases = phases.isEmpty()
                ? PhaseFunctionResolver.defaultPhases(functions)
                : Collections.unmodifiableSet(phases);
        PhaseData data = new PhaseData(Collections.unmodifiableSet(functions), tier, effectivePhases);
        Entry entry = new Entry(data, Map.copyOf(effects));

        for (String raw : itemIds) {
            ResourceLocation itemId = ResourceLocation.parse(raw.trim());
            if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
                Qianxiang.LOGGER.warn("[Qianxiang] phase_materials：物品 {} 未注册（对应 mod 未安装？），跳过", itemId);
                continue;
            }
            out.put(itemId, entry);
        }
    }
}
