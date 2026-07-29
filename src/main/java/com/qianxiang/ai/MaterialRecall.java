package com.qianxiang.ai;

import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseTier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 材料检索式召回 —— 把「全量材料库」压成「与本次需求相关的 Top-N」。
 * <p>
 * 为什么必须有它：{@link MaterialLibrary#snapshot()} 因概念推导兜底（万物皆有概念）
 * 实际收录<b>全部已注册物品</b>（原版就 1391 件，约 114KB 文本）。此前 prompt 把它
 * 逐条倾倒进去，光材料库一段就 ≈4 万 token：
 * <ul>
 *   <li>Ollama 默认 {@code num_ctx} 只有 2048~4096 —— 新版直接 500，旧版静默截断，
 *       而被截掉的恰恰是排在后面的<b>玩家需求</b>本身；</li>
 *   <li>装了整合包（上万物品）后情况只会更糟；</li>
 *   <li>即便模型能吃下，7B 级模型在这种噪声里也挑不准。</li>
 * </ul>
 * <p>
 * 召回策略（无需向量库，纯规则，与 {@link FallbackRecipes} 的关键词表同源）：
 * <ol>
 *   <li><b>核心效果材料</b>：需求关键词命中的功能算子对应材料，优先级最高；</li>
 *   <li><b>基底材料</b>：与目标产物类型匹配的 BASE_* 材料；</li>
 *   <li><b>辅料</b>：目标档位附近的其余材料，补足名额。</li>
 * </ol>
 * 每组内按「档位贴近目标 → 千相自有材料优先 → 名称短（更可能是核心材料）」排序。
 */
public final class MaterialRecall {

    /** 召回总条数上限：三组合计，约 4KB prompt 文本。 */
    public static final int MAX_TOTAL = 48;

    /** 各组配额。 */
    private static final int QUOTA_EFFECT = 24;
    private static final int QUOTA_BASE = 12;

    /** 数据包注册材料（UGC）置顶保底的配额上限（数量有界，防把召回预算吃光）。 */
    private static final int QUOTA_PINNED = 8;

    /** 一组召回结果。 */
    public record Group(String title, List<MaterialLibrary.MaterialEntry> entries) {}

    private MaterialRecall() {}

    /**
     * 按本次需求召回相关材料。
     *
     * @param lib        全量材料库快照
     * @param request    玩家的自然语言需求（可空）
     * @param targetType weapon / magic / armor / tool
     * @param targetTier 目标档位
     * @param allowed    玩家勾选的材料白名单（非空时只在其中召回）
     */
    public static List<Group> recall(List<MaterialLibrary.MaterialEntry> lib,
                                     String request,
                                     String targetType,
                                     PhaseTier targetTier,
                                     Set<String> allowed) {
        return recall(lib, request, targetType, targetTier, allowed, List.of());
    }

    /**
     * 按本次需求召回相关材料（带当前材料槽内容）。
     * <p>
     * 在三组检索式召回之外处理三处边缘（WQ-67）：
     * <ol>
     *   <li><b>confirm 当前材料并入候选</b>：prompt 一边说「只能从下列挑」一边要 AI
     *       评价可能不在列表里的材料——当前材料强制置顶并入；</li>
     *   <li><b>数据包注册材料（UGC）置顶保底</b>：{@code PhaseMaterialRegistry.all()}
     *       的材料无条件置顶（有界）。此前排序「qianxiang: 优先 + 名称短优先」会系统性
     *       压低官方样例 {@code minecraft:heart_of_the_sea} 这类长 id 非 qianxiang 命名空间
     *       的 UGC 材料，「/reload 后 AI 立即可见」在 prompt 层被弱化成「相关才可见」；</li>
     *   <li><b>白名单空池保底</b>：玩家勾选的材料全是无算子概念物品时三组全空，
     *       而 prompt 仍写着「只能从下列材料中挑选」——此时取池内前 N 条兜底。</li>
     * </ol>
     *
     * @param currentMaterials confirm 模式下玩家已放入的材料（registry name，可空）
     */
    public static List<Group> recall(List<MaterialLibrary.MaterialEntry> lib,
                                     String request,
                                     String targetType,
                                     PhaseTier targetTier,
                                     Set<String> allowed,
                                     List<String> currentMaterials) {
        List<MaterialLibrary.MaterialEntry> pool = new ArrayList<>();
        for (MaterialLibrary.MaterialEntry e : lib) {
            if (allowed != null && !allowed.isEmpty() && !allowed.contains(e.registryName())) {
                continue;
            }
            pool.add(e);
        }

        Set<PhaseFunction> wanted = wantedFunctions(request, targetType);
        Comparator<MaterialLibrary.MaterialEntry> byRelevance = relevanceComparator(targetTier);

        List<Group> groups = new ArrayList<>(5);
        Set<String> taken = new LinkedHashSet<>();

        // ⓪ confirm：当前材料强制并入候选（评价对象必须出现在「只能从这里挑」的列表里）
        if (currentMaterials != null && !currentMaterials.isEmpty()) {
            List<MaterialLibrary.MaterialEntry> current = new ArrayList<>();
            for (String name : currentMaterials) {
                if (name == null || name.isBlank()) continue;
                var opt = MaterialLibrary.find(name);
                if (opt.isPresent() && taken.add(opt.get().registryName())) {
                    current.add(opt.get());
                }
            }
            if (!current.isEmpty()) {
                groups.add(new Group("玩家当前已放入材料（本次评价对象）", current));
            }
        }

        // ① 数据包注册材料（UGC）无条件置顶保底：「/reload 后 AI 立即可见」的可见性保证
        Set<String> registered = new LinkedHashSet<>();
        for (net.minecraft.resources.ResourceLocation id : com.qianxiang.phase.PhaseMaterialRegistry.all().keySet()) {
            registered.add(id.toString());
        }
        if (!registered.isEmpty()) {
            List<MaterialLibrary.MaterialEntry> pinned = pool.stream()
                    .filter(e -> registered.contains(e.registryName()))
                    .filter(e -> !taken.contains(e.registryName()))
                    .sorted(byRelevance)
                    .limit(Math.min(QUOTA_PINNED, Math.max(0, MAX_TOTAL - taken.size())))
                    .toList();
            if (!pinned.isEmpty()) {
                pinned.forEach(e -> taken.add(e.registryName()));
                groups.add(new Group("数据包注册材料（phase_materials 定义，优先可见）", pinned));
            }
        }

        // ② 核心效果材料：命中需求关键词的功能算子
        List<MaterialLibrary.MaterialEntry> effect = pool.stream()
                .filter(e -> !taken.contains(e.registryName()))
                .filter(e -> !e.functions().isEmpty() && intersects(e.functions(), wanted))
                .sorted(byRelevance)
                .limit(Math.min(QUOTA_EFFECT, Math.max(0, MAX_TOTAL - taken.size())))
                .toList();
        effect.forEach(e -> taken.add(e.registryName()));

        // ③ 基底材料：与产物类型匹配的 BASE_*
        Set<PhaseFunction> bases = basesFor(targetType);
        List<MaterialLibrary.MaterialEntry> base = pool.stream()
                .filter(e -> !taken.contains(e.registryName()))
                .filter(e -> intersects(e.functions(), bases))
                .sorted(byRelevance)
                .limit(Math.min(QUOTA_BASE, Math.max(0, MAX_TOTAL - taken.size())))
                .toList();
        base.forEach(e -> taken.add(e.registryName()));

        // ④ 辅料：补足名额，优先带任意功能算子的
        int remaining = Math.max(0, MAX_TOTAL - taken.size());
        List<MaterialLibrary.MaterialEntry> extra = pool.stream()
                .filter(e -> !taken.contains(e.registryName()))
                .filter(e -> !e.functions().isEmpty())
                .sorted(byRelevance)
                .limit(remaining)
                .toList();

        if (!effect.isEmpty()) groups.add(new Group("核心效果材料（优先从这里挑）", effect));
        if (!base.isEmpty()) groups.add(new Group("基底材料（决定耐久/骨架）", base));
        if (!extra.isEmpty()) groups.add(new Group("可选辅料", extra));

        // ⑤ 白名单空池保底：池非空但三组全空（勾选的全是无算子概念物品）时，
        //    取池内前 N 条兜底——prompt 写着「只能从下列挑」，下列就不能是空的。
        if (groups.isEmpty() && !pool.isEmpty()) {
            List<MaterialLibrary.MaterialEntry> any = pool.stream()
                    .sorted(byRelevance)
                    .limit(MAX_TOTAL)
                    .toList();
            groups.add(new Group("可选材料（无算子命中，按档位贴近度保底）", any));
        }
        return groups;
    }

    /**
     * 需求文本 → 想要的功能算子集合。命中不到时退回「该产物类型的常用算子」。
     * <p>
     * WQ-67④：先做否定剥除（与 {@link FallbackRecipes} 同一份规则）——裸 contains
     * 会让「不要火的剑」命中「火」，把玩家明确拒绝的 IGNITE 材料顶进
     * 「核心效果材料（优先从这里挑）」第一组。
     */
    private static Set<PhaseFunction> wantedFunctions(String request, String targetType) {
        String text = FallbackRecipes.stripNegatedSegments(request);
        Set<PhaseFunction> out = functionsMentioned(text);

        if (out.isEmpty()) {
            // 需求没提具体效果：按产物类型给一组常用算子，保证召回不为空
            switch (PhaseAIRecipeService.safeType(targetType)) {
                case "armor" -> java.util.Collections.addAll(out,
                        PhaseFunction.DEFENSE, PhaseFunction.RESISTANCE, PhaseFunction.REFLECT);
                case "magic" -> java.util.Collections.addAll(out,
                        PhaseFunction.MANA, PhaseFunction.IGNITE, PhaseFunction.FROST);
                case "tool" -> java.util.Collections.addAll(out,
                        PhaseFunction.AREA_HARVEST, PhaseFunction.GROWTH, PhaseFunction.SPEED_BOOST);
                default -> java.util.Collections.addAll(out,
                        PhaseFunction.EDGE, PhaseFunction.IGNITE, PhaseFunction.STRENGTH);
            }
        }
        // 被否定的功能算子从召回排除：剥完否定后空命中会落类型默认组（「不要火的剑」只剩「剑」，
        // 默认组仍含 IGNITE），同句其他语境也可能把它带回来——按被否定的效果词显式剔除。
        for (String kw : FallbackRecipes.negatedEffectKeywords(request)) {
            out.removeAll(functionsMentioned(kw));
        }
        return out;
    }

    /** 文本里被提到的功能算子（裸 contains 关键词表；调用方负责先做否定剥除）。 */
    private static Set<PhaseFunction> functionsMentioned(String text) {
        Set<PhaseFunction> out = java.util.EnumSet.noneOf(PhaseFunction.class);

        addIfMentioned(out, text, PhaseFunction.IGNITE, "火", "燃", "烧", "炎", "fire", "burn", "flame");
        addIfMentioned(out, text, PhaseFunction.FROST, "冰", "霜", "寒", "冻", "frost", "ice", "freeze");
        addIfMentioned(out, text, PhaseFunction.POISON, "毒", "poison", "venom");
        addIfMentioned(out, text, PhaseFunction.LIFESTEAL, "吸血", "汲取", "lifesteal", "drain", "vampir");
        addIfMentioned(out, text, PhaseFunction.EDGE, "锋", "利", "斩", "切", "sharp", "edge", "blade");
        addIfMentioned(out, text, PhaseFunction.STRENGTH, "力量", "强力", "重击", "strength", "power");
        addIfMentioned(out, text, PhaseFunction.DEFENSE, "防", "护", "甲", "盾", "defen", "armor", "shield");
        addIfMentioned(out, text, PhaseFunction.REFLECT, "反伤", "荆棘", "反弹", "thorn", "reflect");
        addIfMentioned(out, text, PhaseFunction.RESISTANCE, "抗性", "减伤", "坚韧", "resist", "tough");
        addIfMentioned(out, text, PhaseFunction.FIRE_RESIST, "抗火", "防火", "耐火", "fire_resist", "fireproof");
        addIfMentioned(out, text, PhaseFunction.WATER_BREATH, "水下", "呼吸", "潜水", "water_breath", "underwater");
        addIfMentioned(out, text, PhaseFunction.NIGHT_VISION, "夜视", "黑暗", "看清", "night", "vision");
        addIfMentioned(out, text, PhaseFunction.SPEED_BOOST, "速度", "迅捷", "快", "speed", "swift");
        addIfMentioned(out, text, PhaseFunction.JUMP_BOOST, "跳", "弹跳", "jump");
        addIfMentioned(out, text, PhaseFunction.REGENERATION, "再生", "回血", "恢复", "regen", "heal");
        addIfMentioned(out, text, PhaseFunction.HEAL, "治疗", "疗", "heal", "cure");
        addIfMentioned(out, text, PhaseFunction.MANA, "法力", "魔法", "法术", "mana", "magic", "spell");
        addIfMentioned(out, text, PhaseFunction.SLOW, "减速", "迟缓", "slow");
        addIfMentioned(out, text, PhaseFunction.LEVITATION, "漂浮", "浮空", "飞", "levit", "float");
        addIfMentioned(out, text, PhaseFunction.GROWTH, "催熟", "生长", "种", "grow", "farm");
        addIfMentioned(out, text, PhaseFunction.AREA_HARVEST, "范围", "广域", "群", "area", "harvest");
        return out;
    }

    /** 产物类型 → 合适的基底算子。 */
    private static Set<PhaseFunction> basesFor(String targetType) {
        return switch (PhaseAIRecipeService.safeType(targetType)) {
            case "armor" -> Set.of(PhaseFunction.BASE_HIDE, PhaseFunction.BASE_METAL);
            case "magic" -> Set.of(PhaseFunction.BASE_WOOD, PhaseFunction.BASE_METAL);
            case "tool" -> Set.of(PhaseFunction.BASE_METAL, PhaseFunction.BASE_WOOD);
            default -> Set.of(PhaseFunction.BASE_METAL, PhaseFunction.BASE_BONE, PhaseFunction.BASE_WOOD);
        };
    }

    /** 档位贴近目标 → 千相自有材料优先 → 名称短优先。 */
    private static Comparator<MaterialLibrary.MaterialEntry> relevanceComparator(PhaseTier target) {
        int targetOrdinal = target == null ? 0 : target.ordinal();
        return Comparator
                .<MaterialLibrary.MaterialEntry>comparingInt(
                        e -> Math.abs(e.tier().ordinal() - targetOrdinal))
                .thenComparingInt(e -> e.registryName().startsWith("qianxiang:") ? 0 : 1)
                .thenComparingInt(e -> e.registryName().length())
                .thenComparing(MaterialLibrary.MaterialEntry::registryName);
    }

    private static boolean intersects(Set<PhaseFunction> a, Set<PhaseFunction> b) {
        for (PhaseFunction f : a) {
            if (b.contains(f)) return true;
        }
        return false;
    }

    private static void addIfMentioned(Set<PhaseFunction> out, String text,
                                       PhaseFunction fn, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                out.add(fn);
                return;
            }
        }
    }
}
