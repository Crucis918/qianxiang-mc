package com.qianxiang.ai;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.phase.EffectMaterialResolver;
import com.qianxiang.phase.ItemConceptResolver;
import com.qianxiang.phase.Phase;
import com.qianxiang.phase.PhaseData;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseFunctionResolver;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AI 可读的「相材料库」快照。
 * <p>
 * 来源：①自定义相材料（带 {@code qianxiang:phase_data} 组件）；
 *     ②原版/数据包零件（带任意 {@code qianxiang:materials/<function>} tag）。
 * 统一整理成 AI 能消化的材料清单（registry name / 显示名 / 档位 / 功能算子 / 相性）。
 * AI 配方大脑把这张表喂给 LLM，约束它「只能从这些材料里挑」。
 * <p>
 * <b>线程安全/时序</b>：{@link #snapshot()} 会现场遍历
 * {@link BuiltInRegistries#ITEM}。调用时机必须在物品注册表冻结之后
 * （命令执行时已是运行期，绝对安全）。结果是一次性 List 快照，
 * 不会受后续 ItemStack 变动影响。
 */
public final class MaterialLibrary {

    private MaterialLibrary() {}

    // ======================= 快照缓存 =======================
    // snapshot() 会两遍遍历整个物品注册表，第二遍还对每个物品 new ItemStack + 跑
    // ItemConceptResolver.resolve + tag 查询。它原本只在命令路径被调用（注释也这么写），
    // 但如今出现在锻造台每帧渲染路径（每卡片每材料图标一次 find()）与 AI 请求路径上，
    // 整合包上万物品时客户端直接假死。
    //
    // 内容何时变化：物品注册表运行期冻结不变；唯一变量是数据驱动相材料表
    // （PhaseMaterialRegistry，/reload 或登录同步时整表替换）与 tag 绑定。
    // 因此以「PhaseMaterialRegistry 当前表的身份」作为缓存版本键：表换了引用即失效。

    private static volatile Object cachedRegistryIdentity;
    private static volatile List<MaterialEntry> cachedSnapshot;
    private static volatile Map<String, MaterialEntry> cachedIndex;

    /** 缓存失效判据：数据驱动相材料表换了引用（/reload、客户端整表同步）。 */
    private static Object currentIdentity() {
        return com.qianxiang.phase.PhaseMaterialRegistry.all();
    }

    /** 主动作废缓存（数据包重载后调用；正常情况下 identity 比较已能自动失效）。 */
    public static void invalidateCache() {
        cachedRegistryIdentity = null;
        cachedSnapshot = null;
        cachedIndex = null;
    }

    /** 缓存版的全量快照——外部只读，切勿修改返回的列表。 */
    private static List<MaterialEntry> cachedEntries() {
        Object identity = currentIdentity();
        List<MaterialEntry> snap = cachedSnapshot;
        if (snap != null && cachedRegistryIdentity == identity) {
            return snap;
        }
        synchronized (MaterialLibrary.class) {
            if (cachedSnapshot != null && cachedRegistryIdentity == identity) {
                return cachedSnapshot;
            }
            List<MaterialEntry> fresh = List.copyOf(snapshotUncached());
            Map<String, MaterialEntry> index = new java.util.HashMap<>(fresh.size() * 2);
            for (MaterialEntry e : fresh) {
                index.put(e.registryName().toLowerCase(java.util.Locale.ROOT), e);
                // 容错索引：AI 可能只给短名 "ember_iron"（不带 namespace）
                int colon = e.registryName().indexOf(':');
                if (colon >= 0) {
                    index.putIfAbsent(
                            e.registryName().substring(colon + 1).toLowerCase(java.util.Locale.ROOT), e);
                }
            }
            cachedSnapshot = fresh;
            cachedIndex = Map.copyOf(index);
            cachedRegistryIdentity = identity;
            return fresh;
        }
    }

    /** 一条材料的 AI 视图。registryName 形如 "qianxiang:ember_iron" 或 "minecraft:iron_ingot"。 */
    public record MaterialEntry(
            String registryName,
            String displayName,
            PhaseTier tier,
            Set<PhaseFunction> functions,
            Set<Phase> phases
    ) {
        /**
         * 玩家聊天/UI 用的本地化显示组件：物品本名 + 灰色括号里的「档位 / 相」。
         * <p>
         * 发给客户端的是可翻译 Component，客户端按自己语言渲染——
         * 中文玩家看 zh_cn.json，英文玩家看 en_us.json。
         * <b>不要</b>在服务端 {@code .getString()} 取死串。
         */
        public Component displayNameComponent() {
            ResourceLocation id = ResourceLocation.tryParse(registryName);
            Item item = BuiltInRegistries.ITEM.get(id);
            Component name = (item != Items.AIR)
                    ? Component.translatable(item.getDescriptionId())
                    : Component.literal(registryName);
            Component tierC = tierComponent(tier);
            Component phasesC = phasesComponent(phases);
            // 组合：名字 + " " + 灰色"(档位{相})"
            var comp = name.copy()
                    .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("(").withStyle(ChatFormatting.GRAY))
                    .append(tierC)
                    .append(phasesC)
                    .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
            // 推导物品追加概念（qianxiang.concept.* 本地化键，客户端按语言渲染）
            String concept = itemConcept(id == null ? null : id.getPath());
            if (concept != null) {
                comp.append(Component.literal("·").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.translatableWithFallback(conceptKey(id), concept)
                                .withStyle(ChatFormatting.DARK_GREEN));
            }
            return comp;
        }
    }

    /** 档位 → 可翻译 Component，键 qianxiang.tier.common/rare/epic/legendary。 */
    private static Component tierComponent(PhaseTier tier) {
        String key = "qianxiang.tier." + (tier == null ? "common" : tier.name().toLowerCase());
        ChatFormatting color = switch (tier) {
            case COMMON -> ChatFormatting.WHITE;
            case RARE -> ChatFormatting.AQUA;
            case EPIC -> ChatFormatting.LIGHT_PURPLE;
            case LEGENDARY -> ChatFormatting.GOLD;
        };
        return Component.translatable(key).withStyle(color);
    }

    /**
     * 相集合 → 可翻译 Component，形如 "{秩序/火}"。
     * 每个相用 qianxiang.phase.&lt;name 小写&gt; 键。
     */
    private static Component phasesComponent(Set<Phase> phases) {
        MutableComponent base = Component.literal("{").withStyle(ChatFormatting.GRAY);
        if (phases == null || phases.isEmpty()) return base.append(Component.literal("}").withStyle(ChatFormatting.GRAY));
        MutableComponent acc = base;
        boolean first = true;
        for (Phase p : phases) {
            if (!first) acc = acc.append(Component.literal("/").withStyle(ChatFormatting.GRAY));
            acc = acc.append(Component.translatable("qianxiang.phase." + p.name().toLowerCase())
                    .withStyle(ChatFormatting.YELLOW));
            first = false;
        }
        return acc.append(Component.literal("}").withStyle(ChatFormatting.GRAY));
    }

    /**
     * 遍历物品注册表，筛出所有相零件：
     * ①带 {@code qianxiang:phase_data} 的自定义相材料；
     * ②带任意 {@code qianxiang:materials/<function>} tag 的原版/数据包物品。
     * <p>
     * <b>本方法结果被缓存</b>（见 {@link #cachedEntries()}）——它会两遍遍历整个物品注册表，
     * 直接调用的代价在整合包环境下是不可接受的。外部一律用 {@link #snapshot()}。
     */
    private static List<MaterialEntry> snapshotUncached() {
        List<MaterialEntry> out = new ArrayList<>();
        Set<ResourceLocation> seen = new HashSet<>();

        // ① 自定义相材料：PhaseData component 优先
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            Item item = entry.getValue();
            var pd = item.components().get(QianxiangDataComponents.PHASE_DATA.get());
            if (pd == null) continue;
            ResourceKey<Item> key = entry.getKey();
            ResourceLocation id = key.location();
            seen.add(id);
            out.add(buildFromPhaseData(id, pd));
        }

        // ①.5 数据包定义材料（phase_materials/*.json）：与 Java 注册材料同等待遇，
        //     带真实 [TIER] 标注进 AI 材料库——UGC 材料不被当成 COMMON 低估。
        for (var e : com.qianxiang.phase.PhaseMaterialRegistry.all().entrySet()) {
            if (seen.contains(e.getKey())) continue;
            seen.add(e.getKey());
            out.add(buildFromPhaseData(e.getKey(), e.getValue().data()));
        }

        // ② 原版/Tag 零件 + 全物品推导：PhaseData/tag 之外的物品交给
        //    ItemConceptResolver 推导概念（食物/装备/矿物/植物/大地…兜底「凡物」）。
        //    万物皆零件——AI 材料库 = 全物品。
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            Item item = entry.getValue();
            if (item == Items.AIR) continue;
            ResourceKey<Item> key = entry.getKey();
            ResourceLocation id = key.location();
            if (seen.contains(id)) continue;

            ItemConceptResolver.ItemConcept concept =
                    ItemConceptResolver.resolve(new ItemStack(item));
            if (concept.isEmpty()) continue;

            seen.add(id);
            out.add(buildFromConcept(id, concept));
        }

        return Collections.unmodifiableList(out);
    }

    /**
     * 概念 → MaterialEntry。displayName 形如
     * {@code iron_ore[COMMON]{秩序}|金属骨架|概念:精矿}——
     * 功能算子概念 + 「概念:xx」标注直接告诉 LLM 这材料在世界观里是什么。
     */
    private static MaterialEntry buildFromConcept(ResourceLocation id,
                                                  ItemConceptResolver.ItemConcept concept) {
        StringBuilder displayName = new StringBuilder(id.getPath())
                .append("[COMMON]")
                .append(phasesCn(concept.phases()))
                .append("|").append(functionConcepts(concept.functions()));
        if (!concept.effects().isEmpty()) {
            displayName.append("|状态:").append(effectConcepts(concept.effects().keySet()));
        }
        displayName.append("|概念:").append(ItemConceptResolver.conceptNameCn(concept.conceptKey()));
        return new MaterialEntry(
                id.toString(),
                displayName.toString(),
                PhaseTier.COMMON,
                concept.functions(),
                concept.phases()
        );
    }

    private static MaterialEntry buildFromPhaseData(ResourceLocation id, PhaseData pd) {
        String registryName = id.toString();
        String shortName = id.getPath();
        String displayName = shortName
                + "[" + (pd.tier() == null ? "?" : pd.tier().name()) + "]"
                + phasesCn(pd.phases());
        return new MaterialEntry(
                registryName,
                displayName,
                pd.tier() == null ? PhaseTier.COMMON : pd.tier(),
                pd.functions() == null ? Set.of() : pd.functions(),
                pd.phases() == null ? Set.of() : pd.phases()
        );
    }

    /**
     * 物品 → 概念本地化键 {@code qianxiang.concept.<path>}。
     * <p>万物皆零件：任何原版物品都有概念。本键与 lang 文件中的
     * {@code qianxiang.concept.*} 条目对应；ItemConceptResolver（另一子代理）
     * 与客户端组件共用同一套键。
     */
    public static String conceptKey(ResourceLocation itemId) {
        if (itemId == null) return "qianxiang.concept.unknown";
        return "qianxiang.concept." + itemId.getPath().replace('/', '.');
    }

    /**
     * 常见原版物品 → 概念文本（与 lang 的 qianxiang.concept.* 条目一致）。
     * <p>用途：①服务端喂 LLM 的 displayName 无法走客户端本地化，直接内联中文；
     *        ②作为 {@code translatableWithFallback} 的兜底文本。
     * 未收录的物品不强行编概念——LLM 看物品名与功能算子即可。
     */
    private static final Map<String, String> ITEM_CONCEPTS = Map.ofEntries(
            Map.entry("coal", "火种余温"),
            Map.entry("charcoal", "木炭余烬"),
            Map.entry("blaze_powder", "烈焰精华"),
            Map.entry("blaze_rod", "烈焰之核"),
            Map.entry("fire_charge", "火焰弹丸"),
            Map.entry("magma_cream", "岩浆膏脂"),
            Map.entry("snowball", "雪之凝丸"),
            Map.entry("ice", "寒冰"),
            Map.entry("packed_ice", "坚冰"),
            Map.entry("blue_ice", "极寒蓝冰"),
            Map.entry("redstone", "能量脉络"),
            Map.entry("lapis_lazuli", "秘纹青金"),
            Map.entry("ender_pearl", "瞬移之珠"),
            Map.entry("emerald", "翠玉生机"),
            Map.entry("glowstone_dust", "圣光之尘"),
            Map.entry("spider_eye", "血毒蛛眼"),
            Map.entry("pufferfish", "剧毒河豚"),
            Map.entry("wither_rose", "凋零之玫"),
            Map.entry("ink_sac", "暗影墨囊"),
            Map.entry("fermented_spider_eye", "虚弱酵眼"),
            Map.entry("golden_carrot", "夜视金胡萝卜"),
            Map.entry("honey_bottle", "滋养蜂蜜"),
            Map.entry("golden_apple", "护佑金苹果"),
            Map.entry("enchanted_golden_apple", "神佑附魔金苹果"),
            Map.entry("totem_of_undying", "不死图腾"),
            Map.entry("ghast_tear", "再生之泪"),
            Map.entry("sugar", "迅捷之糖"),
            Map.entry("rabbit_foot", "轻盈兔脚"),
            Map.entry("turtle_scute", "坚韧龟鳞"),
            Map.entry("shulker_shell", "浮空潜影壳"),
            Map.entry("bone", "白骨骨架"),
            Map.entry("bone_meal", "催熟骨粉"),
            Map.entry("wheat_seeds", "生机种子"),
            Map.entry("echo_shard", "共鸣回响"),
            Map.entry("iron_ingot", "金属骨架"),
            Map.entry("gold_ingot", "贵重导体"),
            Map.entry("stick", "木质骨架"),
            Map.entry("leather", "皮革基底"),
            Map.entry("flint", "锋锐燧石"),
            Map.entry("dirt", "万物之基"),
            Map.entry("stone", "厚重顽石"),
            Map.entry("grass_block", "生机覆土"),
            Map.entry("sand", "流沙"),
            Map.entry("gravel", "砂砾"),
            Map.entry("feather", "轻盈羽"),
            Map.entry("egg", "新生之卵"),
            Map.entry("rotten_flesh", "腐毒之肉")
    );

    /** 物品概念文本（无收录返回 null）。供 displayName / 客户端兜底用。 */
    public static String itemConcept(String itemPath) {
        return itemPath == null ? null : ITEM_CONCEPTS.get(itemPath);
    }

    /**
     * 功能算子 → 概念化描述（喂给 LLM 的 displayName 后缀）。
     * <p>按 {@code name()} 字符串查表而非枚举常量引用：新算子（POISON/GROWTH/AREA_HARVEST 等）
     * 由别的模块加入枚举后，本表同名条目自动生效，此处无需跟着改代码。
     */
    private static final Map<String, String> FUNCTION_CONCEPTS = Map.ofEntries(
            Map.entry("BASE_METAL", "金属骨架"),
            Map.entry("BASE_WOOD", "木质骨架"),
            Map.entry("BASE_BONE", "骨质骨架"),
            Map.entry("BASE_HIDE", "皮革基底"),
            Map.entry("EDGE", "锋刃"),
            Map.entry("IGNITE", "点燃"),
            Map.entry("LIFESTEAL", "吸血"),
            Map.entry("DEFENSE", "防御"),
            Map.entry("HEAL", "疗伤"),
            Map.entry("SLOW", "迟缓"),
            Map.entry("REFLECT", "反伤"),
            Map.entry("MANA", "法力"),
            Map.entry("POISON", "中毒"),
            Map.entry("FROST", "霜冻"),
            Map.entry("LEVITATION", "漂浮"),
            Map.entry("STRENGTH", "力量"),
            Map.entry("NIGHT_VISION", "夜视"),
            Map.entry("SPEED_BOOST", "迅捷"),
            Map.entry("JUMP_BOOST", "跳跃"),
            Map.entry("RESISTANCE", "抗性"),
            Map.entry("FIRE_RESIST", "抗火"),
            Map.entry("WATER_BREATH", "水下呼吸"),
            Map.entry("REGENERATION", "再生"),
            Map.entry("GROWTH", "催熟"),
            Map.entry("AREA_HARVEST", "广域"),
            Map.entry("REVERSE", "反转")
    );

    /** 功能集合 → "概念1/概念2" 形式；未知算子退化为算子名本身。 */
    private static String functionConcepts(Set<PhaseFunction> functions) {
        if (functions == null || functions.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (PhaseFunction f : functions) {
            if (!first) sb.append("/");
            sb.append(FUNCTION_CONCEPTS.getOrDefault(f.name(), f.name()));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 状态效果 path → 中文概念（喂给 LLM 的「状态:xx」标注）。
     * 覆盖预置的原版效果；未收录的效果退化为 path 本身（LLM 多半也看得懂英文 id）。
     */
    private static final Map<String, String> EFFECT_CONCEPTS = Map.ofEntries(
            Map.entry("poison", "中毒"),
            Map.entry("wither", "凋零"),
            Map.entry("weakness", "虚弱"),
            Map.entry("slowness", "缓慢"),
            Map.entry("blindness", "失明"),
            Map.entry("invisibility", "隐身"),
            Map.entry("night_vision", "夜视"),
            Map.entry("absorption", "伤害吸收"),
            Map.entry("health_boost", "生命提升"),
            Map.entry("regeneration", "再生"),
            Map.entry("strength", "力量"),
            Map.entry("speed", "迅捷"),
            Map.entry("jump_boost", "跳跃提升"),
            Map.entry("resistance", "抗性提升"),
            Map.entry("fire_resistance", "抗火"),
            Map.entry("water_breathing", "水下呼吸"),
            Map.entry("slow_falling", "缓降"),
            Map.entry("glowing", "发光"),
            Map.entry("luck", "幸运"),
            Map.entry("unluck", "霉运"),
            Map.entry("levitation", "漂浮"),
            Map.entry("haste", "急迫"),
            Map.entry("mining_fatigue", "挖掘疲劳"),
            Map.entry("nausea", "反胃"),
            Map.entry("hunger", "饥饿"),
            Map.entry("saturation", "饱和"),
            Map.entry("conduit_power", "潮涌能量"),
            Map.entry("dolphins_grace", "海豚的恩惠"),
            Map.entry("hero_of_the_village", "村庄英雄"),
            Map.entry("bad_omen", "不祥之兆"),
            Map.entry("darkness", "黑暗"),
            Map.entry("wind_charged", "蓄风"),
            Map.entry("weaving", "织网"),
            Map.entry("oozing", "渗浆"),
            Map.entry("infested", "寄生"),
            Map.entry("raid_omen", "袭击之兆"),
            Map.entry("trial_omen", "试炼之兆")
    );

    /** 效果 path → 中文概念；未知退化为 path。 */
    public static String effectConcept(String effectPath) {
        if (effectPath == null) return "?";
        return EFFECT_CONCEPTS.getOrDefault(effectPath, effectPath);
    }

    /** 效果 id 集合 → "概念1/概念2" 形式（displayName 的「状态:」段）。 */
    private static String effectConcepts(Set<ResourceLocation> effectIds) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ResourceLocation eid : effectIds) {
            if (!first) sb.append("/");
            sb.append(effectConcept(eid.getPath()));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 全部可用自由效果索引：效果 path → 提供该效果的材料 registryName 列表。
     * <p>供 {@link PhaseAIRecipeService} 在 system prompt 里列出「凋零刀→wither_rose」式清单。
     * 现场遍历物品注册表，调用时机同 {@link #snapshot()}（运行期安全）。
     */
    public static Map<String, List<String>> effectMaterialIndex() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ItemStack stack = new ItemStack(entry.getValue());
            for (ResourceLocation eid : EffectMaterialResolver.get(stack, PhaseTier.COMMON).keySet()) {
                out.computeIfAbsent(eid.getPath(), k -> new ArrayList<>())
                        .add(entry.getKey().location().toString());
            }
        }
        return out;
    }

    /** 指定 registry name 是否存在于相材料库（防 LLM 瞎编材料）。 */
    public static boolean exists(String registryName) {
        return find(registryName).isPresent();
    }

    /** 全量快照（走缓存，只读）。 */
    public static List<MaterialEntry> snapshot() {
        return cachedEntries();
    }

    /**
     * 按名字查 entry；找不到返回 empty。调用方应总是在信任 LLM 输出前用它校验。
     * <p>走预建索引 O(1)，不再每次线性扫描并重建全表快照。
     * 支持带命名空间的全名与不带命名空间的短名（AI 常只给后者）。
     */
    public static Optional<MaterialEntry> find(String registryName) {
        if (registryName == null || registryName.isBlank()) return Optional.empty();
        cachedEntries();   // 确保索引已构建
        Map<String, MaterialEntry> index = cachedIndex;
        if (index == null) return Optional.empty();
        return Optional.ofNullable(
                index.get(registryName.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    private static String phasesCn(Set<Phase> phases) {
        if (phases == null || phases.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Phase p : phases) {
            if (!first) sb.append("/");
            sb.append(p.cn());
            first = false;
        }
        return sb.append('}').toString();
    }

    /** 仅供日志/调试用。 */
    public static String debugSummary() {
        return "MaterialLibrary snapshot size=" + snapshot().size() + " (mod=" + Qianxiang.MOD_ID + ")";
    }
}
