package com.qianxiang.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.qianxiang.phase.AttributeScheme;
import com.qianxiang.phase.EffectGlossary;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseTier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 「没 AI 的退路」——关键词匹配的确定性配方生成器。
 * <p>
 * 当 Ollama 不可达、LLM 输出全无效、或返回非法 JSON 时，{@link PhaseAIRecipeService}
 * 就回退到这里。不联网、结果确定，便于离线开发与测试。
 * <p>
 * 支持产物类型（weapon/magic/armor/tool）与目标档位（common/rare/epic/legendary）约束，
 * 并能在 "confirm" 模式下评价玩家已给材料。
 */
public final class FallbackRecipes {

    private FallbackRecipes() {}

    /**
     * 关键词回退：返回 1~3 个方案，覆盖目标类型与档位约束。
     */
    public static PhaseAIRecipeService.RecipeResult propose3(String playerWant, String targetType, String targetTier) {
        String want = normalize(playerWant);
        String type = PhaseAIRecipeService.safeType(targetType);
        PhaseTier tier = PhaseAIRecipeService.tierFromString(targetTier);

        Set<String> keywordPicks = keywordPicks(want);

        // 自由法术解析：magic 需求把自然语言翻成 spell JSON（契约：element/form/effect/modifiers/power），
        // 并把元素对应的材料补进关键词方案。
        String spellJson = "";
        if ("magic".equals(type)) {
            SpellSpec spec = detectSpell(want);
            if (spec != null) {
                spellJson = spec.toJson();
                for (String m : spec.materials()) {
                    addIfExists(keywordPicks, m);
                }
            }
        }

        // 攻击动作描述 → EF 连击回退：「连斩/突刺/回旋/跳劈/重劈/快攻/双刀/拔刀/德剑」等关键词
        // 映射到已核实的 epicfight:biped/combat/* 动画组合（契约：category/combos/collider）。
        String movesetJson = movesetFor(playerWant, type);

        List<PhaseAIRecipeService.RecipeProposal> proposals = new ArrayList<>();

        // 方案 1：以关键词命中为主，再补齐类型与档位
        LinkedHashSet<String> p1 = new LinkedHashSet<>(keywordPicks);
        ensureTypeCoverage(p1, type);
        ensureTierCoverage(p1, type, tier);
        proposals.add(makeProposal(p1, spellJson.isEmpty()
                ? "qianxiang.forge_table.summary.fallback.keyword"
                : "qianxiang.forge_table.summary.fallback.spell", spellJson, movesetJson));

        // 方案 2：严格按目标档位构造的「标准方案」
        proposals.add(makeProposal(new LinkedHashSet<>(buildExactTier(type, tier)),
                "qianxiang.forge_table.summary.fallback.tier_exact"));

        // 方案 3：同类型下的另一种效果组合
        proposals.add(makeProposal(new LinkedHashSet<>(buildAlternative(type, tier, keywordPicks)),
                "qianxiang.forge_table.summary.fallback.alternative", spellJson, movesetJson));

        proposals.removeIf(p -> p.materialNames().size() < 2);
        if (proposals.isEmpty()) {
            proposals.add(defaultProposal());
        }
        return new PhaseAIRecipeService.RecipeResult(proposals, "", true);
    }

    /**
     * 白名单版关键词回退：先在全库生成方案，再裁剪到玩家勾选的材料白名单内
     * （白名单为空 = 不限制）。裁剪后无一方案满足时保底回退未裁剪方案，避免空结果。
     * 同时附带基于关键词检测的反问选项（见 {@link #suggestQuestions}）。
     */
    public static PhaseAIRecipeService.RecipeResult propose3(String playerWant, String targetType,
                                                            String targetTier, List<String> allowedMaterials) {
        PhaseAIRecipeService.RecipeResult base = propose3(playerWant, targetType, targetTier);
        List<PhaseAIRecipeService.RecipeProposal> restricted =
                PhaseAIRecipeService.restrictToWhitelist(base.proposals(), allowedMaterials);
        if (restricted.isEmpty()) {
            restricted = base.proposals(); // 白名单太苛刻时保底，保证至少有一份可用方案
        }
        return new PhaseAIRecipeService.RecipeResult(restricted, base.confirmMessage(), base.fallback(),
                suggestQuestions(playerWant, targetType));
    }

    /**
     * 白名单版确认模式：评价材料建议同样裁剪到白名单内（评价玩家已放材料的第 1 个方案原样保留，
     * 仅当白名单把它裁掉时才保底还原）。confirm 模式不提供反问。
     */
    public static PhaseAIRecipeService.RecipeResult proposeForConfirm(String playerWant, String targetType,
                                                                       String targetTier, List<String> currentMaterials,
                                                                       List<String> allowedMaterials) {
        PhaseAIRecipeService.RecipeResult base = proposeForConfirm(playerWant, targetType, targetTier, currentMaterials);
        List<PhaseAIRecipeService.RecipeProposal> restricted =
                PhaseAIRecipeService.restrictToWhitelist(base.proposals(), allowedMaterials);
        if (restricted.isEmpty()) {
            restricted = base.proposals();
        }
        return new PhaseAIRecipeService.RecipeResult(restricted, base.confirmMessage(), base.fallback(), List.of());
    }

    // ===================== AI 反问（关键词检测） =====================

    /**
     * 需求模糊检测：玩家没说清造型 → 给造型选项；没说清效果 → 给效果选项。
     * 共 2~3 个短词，客户端点选后直接追加到输入框重新问 AI。永不抛异常。
     */
    public static List<String> suggestQuestions(String playerWant, String targetType) {
        try {
            String want = normalize(playerWant);
            String type = PhaseAIRecipeService.safeType(targetType);

            boolean hasShape = matchesAny(want,
                    "巨剑", "大剑", "长剑", "短剑", "匕首", "剑", "刀", "斧", "锤", "枪", "矛", "弓", "杖", "刃",
                    "头盔", "胸甲", "护腿", "靴", "甲", "镐", "锄", "铲",
                    "greatsword", "sword", "dagger", "blade", "axe", "hammer", "spear", "bow", "staff", "wand",
                    "helmet", "chestplate", "leggings", "boots", "pickaxe", "hoe", "shovel");
            boolean hasEffect = matchesAny(want,
                    "火", "冰", "寒", "霜", "雷", "闪电", "毒", "吸血", "治疗", "回血", "防御", "护盾", "迟缓", "减速",
                    "夜视", "速度", "迅捷", "力量", "再生", "漂浮", "凋零", "隐身", "反伤", "催熟", "收割",
                    "负面", "正面", "诅咒",
                    "fire", "flame", "frost", "ice", "lightning", "thunder", "poison", "lifesteal", "heal",
                    "defense", "shield", "slow", "night vision", "speed", "strength", "regen",
                    "levitation", "wither", "invisib", "thorns", "harvest", "debuff", "buff", "curse");

            List<String> out = new ArrayList<>();
            if (!hasShape) {
                switch (type) {
                    case "magic" -> out.addAll(List.of("法杖", "魔典"));
                    case "armor" -> out.addAll(List.of("重甲", "轻甲"));
                    case "tool"  -> out.addAll(List.of("斧头", "镐子"));
                    default      -> out.addAll(List.of("巨剑", "匕首"));
                }
            }
            if (!hasEffect) {
                switch (type) {
                    case "armor" -> out.add("抗火");
                    case "tool"  -> out.add("广域采集");
                    default      -> out.addAll(List.of("火焰", "寒冰"));
                }
            }
            while (out.size() > 3) {
                out.remove(out.size() - 1);
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * 确认模式：评价玩家已给材料能否满足目标类型与档位，并给出修改建议。
     */
    public static PhaseAIRecipeService.RecipeResult proposeForConfirm(String playerWant, String targetType,
                                                                       String targetTier, List<String> currentMaterials) {
        String type = PhaseAIRecipeService.safeType(targetType);
        PhaseTier tier = PhaseAIRecipeService.tierFromString(targetTier);

        Set<String> valid = new LinkedHashSet<>();
        if (currentMaterials != null) {
            for (String name : currentMaterials) {
                String n = PhaseAIRecipeService.normalizeName(name);
                if (MaterialLibrary.exists(n)) valid.add(n);
            }
        }
        List<String> materials = new ArrayList<>(valid);

        if (materials.isEmpty()) {
            return new PhaseAIRecipeService.RecipeResult(
                    List.of(new PhaseAIRecipeService.RecipeProposal(List.of(), 0.0,
                            "qianxiang.forge_table.confirm.summary.empty")),
                    "qianxiang.forge_table.confirm.empty", true);
        }

        double power = estimatePower(materials);
        boolean hasBase = hasBaseForType(materials, type);
        boolean hasEffect = hasEffectForType(materials, type);
        PhaseTier avgTier = PhaseAIRecipeService.averageTier(materials);
        boolean tierMatch = Math.abs(avgTier.ordinal() - tier.ordinal()) <= 1;

        String summaryKey;
        String confirmKey;
        List<String> suggestions = new ArrayList<>();

        if (!hasBase && !hasEffect) {
            summaryKey = "qianxiang.forge_table.confirm.summary.missing_all";
            confirmKey = "qianxiang.forge_table.confirm.missing_all";
            suggestions.addAll(suggestBase(type, tier));
            suggestions.addAll(suggestEffect(type, tier));
        } else if (!hasBase) {
            summaryKey = "qianxiang.forge_table.confirm.summary.missing_base";
            confirmKey = "qianxiang.forge_table.confirm.missing_base";
            suggestions.addAll(suggestBase(type, tier));
        } else if (!hasEffect) {
            summaryKey = "qianxiang.forge_table.confirm.summary.missing_effect";
            confirmKey = "qianxiang.forge_table.confirm.missing_effect";
            suggestions.addAll(suggestEffect(type, tier));
        } else if (!tierMatch) {
            summaryKey = "qianxiang.forge_table.confirm.summary.tier_mismatch";
            confirmKey = "qianxiang.forge_table.confirm.tier_mismatch";
            if (avgTier.ordinal() < tier.ordinal()) {
                suggestions.addAll(suggestUpgrade(type, tier));
            } else {
                suggestions.addAll(suggestDowngrade(type, tier));
            }
        } else {
            summaryKey = "qianxiang.forge_table.confirm.summary.ok";
            confirmKey = "qianxiang.forge_table.confirm.ok";
        }

        List<PhaseAIRecipeService.RecipeProposal> proposals = new ArrayList<>();
        proposals.add(new PhaseAIRecipeService.RecipeProposal(materials, power, summaryKey));
        if (!suggestions.isEmpty()) {
            LinkedHashSet<String> suggestSet = new LinkedHashSet<>(suggestions);
            ensureTypeCoverage(suggestSet, type);
            proposals.add(makeProposal(suggestSet, "qianxiang.forge_table.summary.fallback.suggest"));
        }
        return new PhaseAIRecipeService.RecipeResult(proposals, confirmKey, true);
    }

    /**
     * 旧版单方案入口，保留向后兼容。
     */
    public static PhaseAIRecipeService.RecipeProposal propose(String playerWant) {
        PhaseAIRecipeService.RecipeResult result = propose3(playerWant, null, null);
        return result.proposals().isEmpty() ? defaultProposal() : result.proposals().getFirst();
    }

    /**
     * 为目标档位生成一个最接近的兜底方案，用于 AI 输出全部不匹配档位时追加。
     */
    public static PhaseAIRecipeService.RecipeProposal closestTierProposal(String targetType, PhaseTier target) {
        String type = PhaseAIRecipeService.safeType(targetType);
        List<String> materials = buildExactTier(type, target);
        if (materials.size() < 2) {
            materials = buildExactTier(type, PhaseTier.COMMON);
        }
        return makeProposal(new LinkedHashSet<>(materials),
                "qianxiang.forge_table.summary.fallback.closest_tier");
    }

    // ===================== 方案构造工具 =====================

    private static PhaseAIRecipeService.RecipeProposal makeProposal(Set<String> picks, String summaryKey) {
        return makeProposal(picks, summaryKey, "");
    }

    private static PhaseAIRecipeService.RecipeProposal makeProposal(Set<String> picks, String summaryKey, String spellJson) {
        return makeProposal(picks, summaryKey, spellJson, "");
    }

    private static PhaseAIRecipeService.RecipeProposal makeProposal(Set<String> picks, String summaryKey,
                                                                    String spellJson, String movesetJson) {
        List<String> materialNames = new ArrayList<>(picks);
        int cap = PhaseAIRecipeService.maxProposalMaterials(); // 随材料槽位数缩放（全集组合用）
        if (materialNames.size() > cap) {
            materialNames = new ArrayList<>(materialNames.subList(0, cap));
        }
        if (materialNames.size() < 2) {
            materialNames.addAll(defaultFillers());
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(materialNames);
        materialNames = new ArrayList<>(unique);
        if (materialNames.size() > cap) materialNames = materialNames.subList(0, cap);

        double power = estimatePower(materialNames);
        return new PhaseAIRecipeService.RecipeProposal(materialNames, power, summaryKey,
                spellJson == null ? "" : spellJson,
                movesetJson == null ? "" : movesetJson);
    }

    private static PhaseAIRecipeService.RecipeProposal defaultProposal() {
        return makeProposal(new LinkedHashSet<>(List.of("qianxiang:ember_iron", "qianxiang:beast_fang")),
                "qianxiang.forge_table.summary.fallback.default");
    }

    /** 从玩家输入提取关键词命中的材料集合。 */
    private static Set<String> keywordPicks(String want) {
        Set<String> picks = new LinkedHashSet<>();

        if (matchesAny(want, "火", "灼", "烧", "燃", "fire", "burn", "ignite", "flame")) {
            picks.add("qianxiang:ember_iron");
            picks.add("qianxiang:ember_crystal");
        }
        if (matchesAny(want, "吸血", "血", "blood", "drain", "leech")) {
            picks.add("qianxiang:bloodroot");
        }
        if (matchesAny(want, "锋", "刃", "刀", "剑", "edge", "blade", "sword", "sharp")) {
            picks.add("qianxiang:beast_fang");
            picks.add("qianxiang:dragon_bone");
        }
        if (matchesAny(want, "骨", "bone", "skeleton")) {
            picks.add("qianxiang:dragon_bone");
        }
        if (matchesAny(want, "盾", "防", "护", "甲", "defense", "defence", "shield", "guard", "tank")) {
            picks.add("qianxiang:abyss_iron");
            picks.add("qianxiang:salamander_gland");
        }
        if (matchesAny(want, "法", "魔", "术", "mana", "magic", "spell", "wizard", "mage")) {
            picks.add("qianxiang:rift_essence");
        }
        if (matchesAny(want, "治疗", "回血", "生命", "heal", "recovery", "medic")) {
            picks.add("qianxiang:glimmer_wood_sap");
        }
        if (matchesAny(want, "迟缓", "减速", "控场", "slow", "cc")) {
            picks.add("qianxiang:shadowhide_patch");
        }
        // ---- 扩展关键词：新效果 / 装备穿戴效果 / 工具功能 ----
        // 原版材料只有在材料库里（即对应功能 tag 已覆盖）才入方案，避免产出无效配方。
        if (matchesAny(want, "反伤", "荆棘", "thorns", "reflect")) {
            String reflect = findByFunction("REFLECT");
            if (reflect != null) picks.add(reflect);
        }
        if (matchesAny(want, "夜视", "night vision", "nightvision")) {
            addIfExists(picks, "minecraft:golden_carrot");
        }
        if (matchesAny(want, "中毒", "毒", "poison", "venom")) {
            addIfExists(picks, "minecraft:spider_eye");
            addIfExists(picks, "minecraft:pufferfish");
        }
        if (matchesAny(want, "霜冻", "冰冻", "冻结", "冰", "frost", "freeze", "ice")) {
            boolean any = addIfExists(picks, "minecraft:snowball");
            any |= addIfExists(picks, "minecraft:ice");
            if (!any) picks.add("qianxiang:shadowhide_patch"); // tag 未覆盖时退回迟缓材料
        }
        if (matchesAny(want, "漂浮", "浮空", "levitation", "levitate", "float")) {
            addIfExists(picks, "minecraft:shulker_shell");
        }
        if (matchesAny(want, "力量", "强力", "strength")) {
            addIfExists(picks, "minecraft:blaze_powder");
        }
        if (matchesAny(want, "速度", "迅捷", "加速", "speed", "swift")) {
            addIfExists(picks, "minecraft:sugar");
        }
        if (matchesAny(want, "跳跃", "弹跳", "jump", "leap")) {
            addIfExists(picks, "minecraft:rabbit_foot");
        }
        if (matchesAny(want, "抗性", "坚韧", "resistance", "tough")) {
            // 1.20.5+ 海龟鳞甲改名为 turtle_scute，两个 id 都试一下
            if (!addIfExists(picks, "minecraft:turtle_scute")) {
                addIfExists(picks, "minecraft:scute");
            }
        }
        if (matchesAny(want, "抗火", "防火", "耐火", "fire resist", "fire_resist", "fireproof")) {
            addIfExists(picks, "minecraft:magma_cream");
        }
        if (matchesAny(want, "水下呼吸", "水下", "潜水", "water breath", "waterbreath", "water")) {
            addIfExists(picks, "minecraft:pufferfish");
        }
        if (matchesAny(want, "再生", "regen", "regeneration")) {
            addIfExists(picks, "minecraft:ghast_tear");
        }
        if (matchesAny(want, "耕地", "锄头", "耕作", "犁地", "收割", "hoe", "farm", "till", "harvest")) {
            String areaHarvest = findByFunction("AREA_HARVEST");
            if (areaHarvest != null) picks.add(areaHarvest);
        }
        if (matchesAny(want, "水壶", "浇水", "催熟", "生长", "灌溉", "施肥", "grow", "growth", "watering", "fertiliz")) {
            String growth = findByFunction("GROWTH");
            if (growth != null) picks.add(growth);
        }
        // ---- 宽泛需求：全正面 / 全负面 / 随机 ----
        if (matchesAny(want, "所有效果", "全效果", "全部正面", "所有正面", "正面全", "全部增益", "所有增益", "增益全",
                "buff全要", "buff 全要", "全buff", "全 buff", "所有buff", "all buff", "all positive", "every buff")) {
            picks.addAll(positiveCombo(EffectGlossary.BUFFS.size()));
        }
        if (matchesAny(want, "所有负面", "全负面", "全部负面", "负面全", "debuff全要", "debuff 全要",
                "全debuff", "全 debuff", "所有debuff", "诅咒", "curse", "all negative", "every debuff")) {
            picks.addAll(negativeCombo(EffectGlossary.DEBUFFS.size()));
        }
        if (matchesAny(want, "随机", "随便", "惊喜", "random", "surprise", "whatever")) {
            picks.addAll(randomCombo());
        }

        if (picks.isEmpty()) {
            picks.add("qianxiang:ember_iron");
            picks.add("qianxiang:beast_fang");
        }
        return picks;
    }

    /** 材料存在于材料库才加入方案（原版材料依赖功能 tag 覆盖，未必在库）。返回是否加入。 */
    private static boolean addIfExists(Set<String> picks, String name) {
        if (MaterialLibrary.exists(name)) {
            picks.add(name);
            return true;
        }
        return false;
    }

    // ===================== 宽泛需求：全正面 / 全负面 / 随机 =====================

    /** 正面效果候选材料（randomCombo 随机池用；「全部增益」全集走 {@link EffectGlossary#BUFFS}）。 */
    private static final List<String> POSITIVE_EFFECT_MATERIALS = List.of(
            "minecraft:golden_carrot",           // 夜视
            "minecraft:honey_bottle",            // 再生
            "minecraft:ghast_tear",              // 再生（功能算子）
            "minecraft:golden_apple",            // 伤害吸收
            "minecraft:totem_of_undying",        // 幸运
            "minecraft:turtle_scute",            // 抗性
            "minecraft:sugar",                   // 迅捷
            "minecraft:magma_cream",             // 抗火
            "minecraft:rabbit_foot",             // 跳跃
            "minecraft:pufferfish",              // 水下呼吸
            "minecraft:enchanted_golden_apple",  // 生命提升
            "minecraft:phantom_membrane"         // 缓降
    );

    /** 负面效果候选材料：中毒/凋零/虚弱/缓慢/失明/饥饿系（randomCombo 用）。 */
    private static final List<String> NEGATIVE_EFFECT_MATERIALS = List.of(
            "minecraft:spider_eye",              // 中毒+吸血
            "minecraft:pufferfish",              // 中毒
            "minecraft:wither_rose",             // 凋零
            "minecraft:fermented_spider_eye",    // 虚弱
            "minecraft:ink_sac",                 // 失明
            "qianxiang:shadowhide_patch",        // 迟缓
            "minecraft:snowball",                // 冻伤
            "minecraft:blaze_powder"             // 燃烧
    );

    /**
     * 「所有效果/全部正面/全部增益/buff 全要」→ 按 {@link EffectGlossary#BUFFS} 的顺序
     * 尽量凑 n 个<b>不同增益效果</b>的典型材料（抗火/力量/迅捷/跳跃/缓降/生命恢复/抗性/
     * 水下呼吸/夜视…，每条效果取材料库中存在的第一个典型材料）。
     */
    private static Set<String> positiveCombo(int n) {
        return glossaryCombo(EffectGlossary.BUFFS, n);
    }

    /**
     * 「所有负面/全部负面/debuff 全要/诅咒」→ 按 {@link EffectGlossary#DEBUFFS} 的顺序
     * 尽量凑 n 个<b>不同负面效果</b>的典型材料（灼烧/冻伤/中毒/凋零/缓慢/饥饿/虚弱/寄生
     * 优先——材料槽扩到 10 后 8 种 + 基底正好放下；槽位富余时继续补 反胃/失明/漂浮）。
     */
    private static Set<String> negativeCombo(int n) {
        return glossaryCombo(EffectGlossary.DEBUFFS, n);
    }

    /** 词典组合通用实现：按词条顺序，每条效果取典型材料中存在于材料库的第一个，凑满 n 个为止。 */
    private static Set<String> glossaryCombo(List<EffectGlossary.EffectInfo> glossary, int n) {
        Set<String> out = new LinkedHashSet<>();
        for (EffectGlossary.EffectInfo info : glossary) {
            if (out.size() >= n) break;
            for (String name : info.typicalMaterials()) {
                if (addIfExists(out, name)) break; // 每条效果取库中存在的第一个
            }
        }
        return out;
    }

    /** 「随机/随便/惊喜」→ 随机 2~3 个效果材料组合。 */
    private static Set<String> randomCombo() {
        List<String> pool = new ArrayList<>();
        for (String name : POSITIVE_EFFECT_MATERIALS) {
            if (MaterialLibrary.exists(name)) pool.add(name);
        }
        for (String name : NEGATIVE_EFFECT_MATERIALS) {
            if (MaterialLibrary.exists(name) && !pool.contains(name)) pool.add(name);
        }
        Set<String> out = new LinkedHashSet<>();
        if (pool.isEmpty()) return out;
        int count = Math.min(pool.size(), ThreadLocalRandom.current().nextInt(2, 4)); // 2~3
        while (out.size() < count) {
            out.add(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
        }
        return out;
    }

    // ===================== 自由法术描述解析 =====================

    /**
     * 一份从自然语言提炼的自由法术（契约与法术核心子代理 CustomSpell 一致：
     * element/form/effect/modifiers/power）。materials 是与元素呼应的候选材料。
     */
    record SpellSpec(String element, String form, String effect, List<String> modifiers,
                     double power, List<String> materials) {
        String toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("element", element);
            o.addProperty("form", form);
            o.addProperty("effect", effect);
            JsonArray mods = new JsonArray();
            for (String m : modifiers) mods.add(m);
            o.add("modifiers", mods);
            o.addProperty("power", power);
            return o.toString();
        }
    }

    /**
     * 从玩家描述检测自由法术组合。词表见 {@link com.qianxiang.spell.CustomSpell#ELEMENTS} 等。
     * 一个法术特征都没命中 → 返回 null（该需求不是法术描述）。永不抛异常。
     */
    static SpellSpec detectSpell(String want) {
        try {
            if (want == null || want.isBlank()) return null;

            // ---- 修饰（可多选）----
            List<String> modifiers = new ArrayList<>();
            if (matchesAny(want, "追踪", "制导", "homing", "seeking")) modifiers.add("homing");
            if (matchesAny(want, "穿透", "贯穿", "pierc", "penetrat")) modifiers.add("piercing");
            if (matchesAny(want, "持续", "延续", "duration", "lasting", "extended")) modifiers.add("extended");
            if (matchesAny(want, "强化", "增强", "empower", "amplif", "overcharge")) modifiers.add("amplified");
            if (matchesAny(want, "连锁", "链式", "链", "chain")) modifiers.add("chain");

            // ---- 形式（选一，按优先级）----
            String form = null;
            if (matchesAny(want, "范围", "群体", "aoe", "area")) form = "aoe";
            else if (matchesAny(want, "光束", "射线", "beam", "ray", "laser")) form = "beam";
            else if (matchesAny(want, "自身", "自我", "self")) form = "self";
            else if (matchesAny(want, "触击", "触碰", "近战", "touch", "melee")) form = "touch";

            // ---- 效果（选一）----
            String effect = null;
            if (matchesAny(want, "治疗", "治愈", "回血", "heal")) effect = "heal";
            else if (matchesAny(want, "传送", "闪现", "瞬移", "teleport", "blink")) effect = "utility";
            else if (matchesAny(want, "增益", "祝福", "buff")) effect = "buff";
            else if (matchesAny(want, "减益", "削弱", "诅咒", "debuff")) effect = "debuff";

            // ---- 元素（选一）与呼应材料 ----
            String element = null;
            List<String> materials = new ArrayList<>();
            if (matchesAny(want, "传送", "闪现", "瞬移", "末影", "teleport", "blink", "ender")) {
                element = "ender";
                materials.add("minecraft:ender_pearl");
            } else if (matchesAny(want, "雷", "闪电", "lightning", "thunder", "storm")) {
                element = "lightning";
                // 避雷针（lightning_rod）无功能 tag 不在材料库，用红石（能量）+金（导体）
                materials.add("minecraft:redstone");
                materials.add("minecraft:gold_ingot");
            } else if (matchesAny(want, "寒霜", "冰冻", "冰", "霜", "frost", "ice", "freeze")) {
                element = "frost";
                materials.add("minecraft:snowball");
                materials.add("minecraft:ice");
            } else if (matchesAny(want, "火", "火焰", "烈焰", "fire", "flame", "burn")) {
                element = "fire";
                materials.add("minecraft:blaze_powder");
                materials.add("minecraft:magma_cream");
            } else if (matchesAny(want, "自然", "藤蔓", "生机", "nature", "verdant")) {
                element = "nature";
                materials.add("minecraft:bone_meal");
                materials.add("minecraft:wheat_seeds");
            } else if (matchesAny(want, "暗影", "黑暗", "shadow", "dark")) {
                element = "shadow";
                materials.add("minecraft:ink_sac");
                materials.add("minecraft:echo_shard");
            } else if (matchesAny(want, "神圣", "圣光", "圣", "holy", "divine")) {
                element = "holy";
                materials.add("minecraft:golden_carrot");
                materials.add("minecraft:glowstone_dust");
            } else if (matchesAny(want, "吸血", "鲜血", "血", "blood")) {
                element = "blood";
                materials.add("minecraft:spider_eye");
                materials.add("qianxiang:bloodroot");
            } else if (matchesAny(want, "奥术", "奥秘", "arcane")) {
                element = "arcane";
                materials.add("minecraft:lapis_lazuli");
                materials.add("qianxiang:rift_essence");
            }

            // 治疗默认归自然元素
            if (element == null && "heal".equals(effect)) {
                element = "nature";
                materials.add("minecraft:bone_meal");
                materials.add("minecraft:glowstone_dust");
            }

            boolean anyHit = element != null || form != null || effect != null || !modifiers.isEmpty();
            if (!anyHit) return null;

            if (element == null) element = "arcane";
            if (form == null) form = "projectile";
            if (effect == null) {
                effect = switch (element) {
                    case "nature" -> "heal";
                    case "holy" -> "buff";
                    case "ender" -> "utility";
                    default -> "damage";
                };
            }
            double power = modifiers.contains("amplified") ? 2.0 : 1.0;
            return new SpellSpec(element, form, effect, List.copyOf(modifiers), power, List.copyOf(materials));
        } catch (Throwable t) {
            return null; // 解析失败 = 无法术，绝不拖垮 fallback
        }
    }

    /** 便捷入口：从玩家描述生成 spellJson；不是法术描述返回 ""。 */
    public static String spellJsonFor(String playerWant) {
        SpellSpec spec = detectSpell(normalize(playerWant));
        return spec == null ? "" : spec.toJson();
    }

    /**
     * 从材料库找带指定功能算子的材料，同算子取最低档位（原版零件都是 COMMON，自然优先）。
     * 按算子名（字符串）匹配：新算子（AREA_HARVEST/GROWTH 等）未进枚举时也能工作。
     */
    private static String findByFunction(String functionName) {
        String best = null;
        int bestTier = Integer.MAX_VALUE;
        for (var e : MaterialLibrary.snapshot()) {
            if (!PhaseAIRecipeService.hasFn(e.functions(), functionName)) continue;
            int ord = e.tier() == null ? 0 : e.tier().ordinal();
            if (ord < bestTier) {
                bestTier = ord;
                best = e.registryName();
            }
        }
        return best;
    }

    /** 保证方案覆盖目标类型所需的基础/效果算子。 */
    private static void ensureTypeCoverage(Set<String> picks, String type) {
        boolean hasBase = hasBaseForType(new ArrayList<>(picks), type);
        boolean hasEffect = hasEffectForType(new ArrayList<>(picks), type);

        if (!hasBase) {
            String base = pickBase(type);
            if (base != null) picks.add(base);
        }
        if (!hasEffect) {
            String effect = pickEffect(type);
            if (effect != null) picks.add(effect);
        }
    }

    /** 若方案里没有目标档位材料，则尽量补一个同类型、目标档位的材料。 */
    private static void ensureTierCoverage(Set<String> picks, String type, PhaseTier tier) {
        for (String name : picks) {
            var opt = MaterialLibrary.find(name);
            if (opt.isPresent() && opt.get().tier() == tier) return;
        }
        var opt = PhaseAIRecipeService.findByTypeAndTier(type, tier);
        if (opt.isPresent()) {
            picks.add(opt.get().registryName());
        } else {
            PhaseTier closest = closestAvailableTier(type, tier);
            var alt = PhaseAIRecipeService.findByTypeAndTier(type, closest);
            alt.ifPresent(e -> picks.add(e.registryName()));
        }
    }

    /** 构造一个严格按目标档位的 2 材料方案。 */
    private static List<String> buildExactTier(String type, PhaseTier tier) {
        LinkedHashSet<String> picks = new LinkedHashSet<>();
        String base = pickBaseByTier(type, tier);
        String effect = pickEffectByTier(type, tier);
        if (base != null) picks.add(base);
        if (effect != null) picks.add(effect);
        if (picks.size() < 2) {
            ensureTierCoverage(picks, type, tier);
        }
        if (picks.size() < 2) {
            picks.addAll(defaultFillers());
        }
        return new ArrayList<>(picks);
    }

    /** 构造一个同类型下的替代效果方案。 */
    private static List<String> buildAlternative(String type, PhaseTier tier, Set<String> keywordPicks) {
        LinkedHashSet<String> picks = new LinkedHashSet<>(keywordPicks);
        ensureTypeCoverage(picks, type);
        ensureTierCoverage(picks, type, tier);
        if (picks.size() >= 2) {
            List<String> list = new ArrayList<>(picks);
            String altEffect = pickAltEffect(type, tier, list.get(list.size() - 1));
            if (altEffect != null) {
                list.set(list.size() - 1, altEffect);
                picks = new LinkedHashSet<>(list);
            }
        }
        return new ArrayList<>(picks);
    }

    // ===================== 材料选择器 =====================

    private static String pickBase(String type) {
        return switch (PhaseAIRecipeService.safeType(type)) {
            case "magic" -> "qianxiang:glimmer_wood_sap";
            case "armor" -> "qianxiang:shadowhide_patch";
            case "tool"  -> "qianxiang:ember_iron";
            default      -> "qianxiang:ember_iron";
        };
    }

    private static String pickBaseByTier(String type, PhaseTier tier) {
        return switch (PhaseAIRecipeService.safeType(type)) {
            case "magic" -> switch (tier) {
                case COMMON -> "qianxiang:glimmer_wood_sap";
                case RARE -> "qianxiang:bloodroot";
                case EPIC -> "qianxiang:dragon_bone";
                case LEGENDARY -> "qianxiang:rift_essence";
            };
            case "armor" -> switch (tier) {
                case COMMON -> "qianxiang:shadowhide_patch";
                case RARE -> "qianxiang:abyss_iron";
                case EPIC -> "qianxiang:salamander_gland";
                case LEGENDARY -> "qianxiang:rift_essence";
            };
            case "tool" -> switch (tier) {
                case COMMON -> "qianxiang:ember_iron";
                case RARE -> "qianxiang:abyss_iron";
                case EPIC -> "qianxiang:dragon_bone";
                case LEGENDARY -> "qianxiang:rift_essence";
            };
            default -> switch (tier) { // weapon
                case COMMON -> "qianxiang:ember_iron";
                case RARE -> "qianxiang:ember_iron";
                case EPIC -> "qianxiang:dragon_bone";
                case LEGENDARY -> "qianxiang:rift_essence";
            };
        };
    }

    private static String pickEffect(String type) {
        return switch (PhaseAIRecipeService.safeType(type)) {
            case "magic" -> "qianxiang:rift_essence";
            case "armor" -> "qianxiang:abyss_iron";
            case "tool"  -> "qianxiang:beast_fang";
            default      -> "qianxiang:beast_fang";
        };
    }

    private static String pickEffectByTier(String type, PhaseTier tier) {
        return switch (PhaseAIRecipeService.safeType(type)) {
            case "magic" -> switch (tier) {
                case COMMON -> "qianxiang:ember_crystal";
                case RARE -> "qianxiang:bloodroot";
                case EPIC -> "qianxiang:salamander_gland";
                case LEGENDARY -> "qianxiang:rift_essence";
            };
            case "armor" -> switch (tier) {
                case COMMON -> "qianxiang:shadowhide_patch";
                case RARE -> "qianxiang:abyss_iron";
                case EPIC -> "qianxiang:salamander_gland";
                case LEGENDARY -> "qianxiang:rift_essence";
            };
            case "tool" -> switch (tier) {
                case COMMON -> "qianxiang:beast_fang";
                case RARE -> "qianxiang:ember_iron";
                case EPIC -> "qianxiang:salamander_gland";
                case LEGENDARY -> "qianxiang:rift_essence";
            };
            default -> switch (tier) { // weapon
                case COMMON -> "qianxiang:beast_fang";
                case RARE -> "qianxiang:bloodroot";
                case EPIC -> "qianxiang:salamander_gland";
                case LEGENDARY -> "qianxiang:rift_essence";
            };
        };
    }

    private static String pickAltEffect(String type, PhaseTier tier, String currentEffect) {
        List<String> candidates = new ArrayList<>();
        switch (PhaseAIRecipeService.safeType(type)) {
            case "magic" -> candidates.addAll(List.of(
                    "qianxiang:rift_essence", "qianxiang:ember_crystal", "qianxiang:bloodroot",
                    "qianxiang:glimmer_wood_sap", "qianxiang:shadowhide_patch"));
            case "armor" -> candidates.addAll(List.of(
                    "qianxiang:abyss_iron", "qianxiang:salamander_gland", "qianxiang:shadowhide_patch",
                    "qianxiang:rift_essence"));
            case "tool" -> candidates.addAll(List.of(
                    "qianxiang:beast_fang", "qianxiang:ember_iron", "qianxiang:abyss_iron",
                    "qianxiang:shadowhide_patch"));
            default -> candidates.addAll(List.of(
                    "qianxiang:beast_fang", "qianxiang:ember_iron", "qianxiang:bloodroot",
                    "qianxiang:dragon_bone", "qianxiang:salamander_gland"));
        }
        candidates.remove(currentEffect);
        for (String c : candidates) {
            var opt = MaterialLibrary.find(c);
            if (opt.isPresent() && Math.abs(opt.get().tier().ordinal() - tier.ordinal()) <= 1) {
                return c;
            }
        }
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static List<String> defaultFillers() {
        return List.of("qianxiang:ember_iron", "qianxiang:beast_fang");
    }

    private static List<String> suggestBase(String type, PhaseTier tier) {
        String base = pickBaseByTier(type, tier);
        return base != null ? List.of(base) : defaultFillers();
    }

    private static List<String> suggestEffect(String type, PhaseTier tier) {
        String effect = pickEffectByTier(type, tier);
        return effect != null ? List.of(effect) : defaultFillers();
    }

    private static List<String> suggestUpgrade(String type, PhaseTier tier) {
        List<String> out = new ArrayList<>();
        String base = pickBaseByTier(type, tier);
        String effect = pickEffectByTier(type, tier);
        if (base != null) out.add(base);
        if (effect != null) out.add(effect);
        return out;
    }

    private static List<String> suggestDowngrade(String type, PhaseTier tier) {
        PhaseTier lower = tier.ordinal() > 0 ? PhaseTier.values()[tier.ordinal() - 1] : tier;
        List<String> out = new ArrayList<>();
        String base = pickBaseByTier(type, lower);
        String effect = pickEffectByTier(type, lower);
        if (base != null) out.add(base);
        if (effect != null) out.add(effect);
        return out;
    }

    // ===================== 确认模式评价工具 =====================

    static boolean hasBaseForType(List<String> materials, String type) {
        for (String name : materials) {
            var opt = MaterialLibrary.find(name);
            if (opt.isEmpty()) continue;
            Set<PhaseFunction> fns = opt.get().functions();
            if (fns == null) continue;
            switch (PhaseAIRecipeService.safeType(type)) {
                case "magic" -> { if (fns.contains(PhaseFunction.BASE_WOOD) || fns.contains(PhaseFunction.BASE_METAL)) return true; }
                case "armor" -> { if (fns.contains(PhaseFunction.BASE_HIDE) || fns.contains(PhaseFunction.BASE_METAL)) return true; }
                case "tool"  -> { if (fns.contains(PhaseFunction.BASE_METAL) || fns.contains(PhaseFunction.BASE_WOOD)) return true; }
                default      -> { if (fns.contains(PhaseFunction.BASE_METAL) || fns.contains(PhaseFunction.BASE_BONE)
                        || fns.contains(PhaseFunction.BASE_WOOD)) return true; }
            }
        }
        return false;
    }

    static boolean hasEffectForType(List<String> materials, String type) {
        for (String name : materials) {
            var opt = MaterialLibrary.find(name);
            if (opt.isEmpty()) continue;
            Set<PhaseFunction> fns = opt.get().functions();
            if (fns == null) continue;
            switch (PhaseAIRecipeService.safeType(type)) {
                case "magic" -> { if (fns.contains(PhaseFunction.MANA) || fns.contains(PhaseFunction.IGNITE)
                        || fns.contains(PhaseFunction.LIFESTEAL) || fns.contains(PhaseFunction.SLOW)
                        || fns.contains(PhaseFunction.HEAL) || fns.contains(PhaseFunction.REFLECT)
                        || PhaseAIRecipeService.hasFn(fns, "POISON") || PhaseAIRecipeService.hasFn(fns, "FROST")
                        || PhaseAIRecipeService.hasFn(fns, "REGENERATION")) return true; }
                case "armor" -> { if (fns.contains(PhaseFunction.DEFENSE) || fns.contains(PhaseFunction.REFLECT)
                        || fns.contains(PhaseFunction.MANA)
                        || PhaseAIRecipeService.hasFn(fns, "RESISTANCE") || PhaseAIRecipeService.hasFn(fns, "NIGHT_VISION")
                        || PhaseAIRecipeService.hasFn(fns, "SPEED_BOOST") || PhaseAIRecipeService.hasFn(fns, "JUMP_BOOST")
                        || PhaseAIRecipeService.hasFn(fns, "FIRE_RESIST") || PhaseAIRecipeService.hasFn(fns, "WATER_BREATH")
                        || PhaseAIRecipeService.hasFn(fns, "REGENERATION")) return true; }
                case "tool"  -> { if (fns.contains(PhaseFunction.EDGE) || fns.contains(PhaseFunction.IGNITE)
                        || fns.contains(PhaseFunction.SLOW) || fns.contains(PhaseFunction.DEFENSE)
                        || PhaseAIRecipeService.hasFn(fns, "AREA_HARVEST") || PhaseAIRecipeService.hasFn(fns, "GROWTH")) return true; }
                default      -> { if (fns.contains(PhaseFunction.EDGE) || fns.contains(PhaseFunction.IGNITE)
                        || fns.contains(PhaseFunction.LIFESTEAL)
                        || PhaseAIRecipeService.hasFn(fns, "POISON") || PhaseAIRecipeService.hasFn(fns, "FROST")
                        || PhaseAIRecipeService.hasFn(fns, "STRENGTH") || PhaseAIRecipeService.hasFn(fns, "LEVITATION")) return true; }
            }
        }
        return false;
    }

    private static PhaseTier closestAvailableTier(String type, PhaseTier target) {
        PhaseTier best = target;
        int bestDist = Integer.MAX_VALUE;
        for (var e : MaterialLibrary.snapshot()) {
            if (!PhaseAIRecipeService.matchesType(e, type)) continue;
            int dist = Math.abs(e.tier().ordinal() - target.ordinal());
            if (dist < bestDist) {
                bestDist = dist;
                best = e.tier();
            }
        }
        return best;
    }

    // ===================== 攻击动作关键词 → EF 连击回退 =====================

    /**
     * 攻击动作描述 → movesetJson（契约：{"category","combos","collider"}）。
     * 关键词命中时给出已核实的 epicfight:biped/combat/* 动画组合（1~4 个，EF 21.15.6 jar 核实），
     * 匹配不到返回空串（=无动作定制）。永不抛异常。
     * <p>语义映射：连斩→COMBO 序列、突刺/突进/冲刺→DASH、回旋/横扫→横扫连段、
     * 跳劈/空斩→AIRSLASH、重劈→HEAVY、快攻→FAST、双刀→DUAL、拔刀/居合→SHEATH、德剑→LIECHTENAUER。
     */
    public static String movesetFor(String playerWant) {
        return movesetFor(playerWant, "weapon");
    }

    /**
     * 带产物类型的版本：关键词映射与类型无关（动作描述对任意武器形态都有意义），
     * category 随命中的动作风格选定；无任何关键词命中时返回空串。
     */
    public static String movesetFor(String playerWant, String targetType) {
        try {
            String want = normalize(playerWant);
            if (want.isBlank()) return "";

            // 判定时注意先后：特征鲜明的关键词（拔刀/双刀/德剑）优先，避免被泛词抢走
            if (matchesAny(want, "拔刀", "居合", "iaido", "sheath")) {
                return movesetJson("uchigatana", "uchigatana_sheath_auto", "uchigatana_sheath_dash");
            }
            if (matchesAny(want, "双刀", "双持", "dual")) {
                return movesetJson("dagger", "dagger_dual_auto1", "dagger_dual_auto2", "dagger_dual_auto3");
            }
            if (matchesAny(want, "德剑", "德式剑", "liechtenauer")) {
                return movesetJson("longsword", "longsword_liechtenauer_auto1",
                        "longsword_liechtenauer_auto2", "longsword_liechtenauer_auto3");
            }
            if (matchesAny(want, "突刺", "thrust")) {
                return movesetJson("longsword", "longsword_dash");
            }
            if (matchesAny(want, "突进", "冲刺", "dash")) {
                return movesetJson("dagger", "dagger_dash");
            }
            if (matchesAny(want, "连斩", "连击", "三连", "combo")) {
                return movesetJson("tachi", "tachi_auto1", "tachi_auto2", "tachi_auto3");
            }
            if (matchesAny(want, "回旋", "横扫", "spin", "sweep")) {
                return movesetJson("sword", "sword_auto1", "sword_auto2", "sword_auto3");
            }
            if (matchesAny(want, "跳劈", "空斩", "下劈", "airslash", "leap attack")) {
                return movesetJson("longsword", "longsword_airslash");
            }
            if (matchesAny(want, "重劈", "大力", "重击", "heavy", "smash")) {
                return movesetJson("greatsword", "greatsword_auto1", "greatsword_auto2");
            }
            if (matchesAny(want, "快攻", "连刺", "疾风", "fast attack", "rapid")) {
                return movesetJson("dagger", "dagger_auto1", "dagger_auto2", "dagger_auto3");
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 组装 movesetJson：collider 默认与 category 相同；动画 id 统一补 epicfight:biped/combat/ 前缀。 */
    private static String movesetJson(String category, String... comboPaths) {
        JsonObject obj = new JsonObject();
        obj.addProperty("category", category);
        JsonArray arr = new JsonArray();
        for (String path : comboPaths) {
            arr.add("epicfight:biped/combat/" + path);
        }
        obj.add("combos", arr);
        obj.addProperty("collider", category);
        return obj.toString();
    }

    // ===================== 通用工具 =====================

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesAny(String text, String... keys) {
        for (String k : keys) {
            if (k != null && !k.isEmpty() && text.contains(k)) return true;
        }
        return false;
    }

    private static double estimatePower(List<String> materialNames) {
        List<AttributeScheme.MaterialInput> inputs = new ArrayList<>();
        for (String name : materialNames) {
            var entryOpt = MaterialLibrary.find(name);
            if (entryOpt.isEmpty()) continue;
            var e = entryOpt.get();
            Set<PhaseFunction> fns = e.functions();
            if (fns == null || fns.isEmpty()) continue;
            inputs.add(AttributeScheme.MaterialInput.of(e.tier(), fns.toArray(new PhaseFunction[0])));
        }
        if (inputs.isEmpty()) return 0.0;
        return AttributeScheme.compose(inputs).powerScore();
    }

    // ===================== 输入框关键词即时提示（客户端用，只读追加） =====================

    /**
     * 一条关键词命中：效果标签（本地化 key，{@code qianxiang.phasefn.*}）+ 命中材料 registry 名。
     * 供锻造台输入框做即时提示：「匹配：烬铁/余烬水晶（灼烧）」。
     */
    public record KeywordHint(String labelKey, List<String> materialNames) {}

    /**
     * 关键词即时提示：与 {@link #keywordPicks} 同源的关键词表，但按效果分组返回
     * （附本地化标签 key），且未命中任何关键词时返回空（不给默认材料）。
     * <p>纯只读、无副作用；客户端每帧比较输入后调用也安全。</p>
     */
    public static List<KeywordHint> keywordHints(String input) {
        String want = normalize(input);
        if (want.isBlank()) return List.of();
        List<KeywordHint> out = new ArrayList<>();

        if (matchesAny(want, "火", "灼", "烧", "燃", "fire", "burn", "ignite", "flame")) {
            out.add(new KeywordHint("qianxiang.phasefn.ignite",
                    List.of("qianxiang:ember_iron", "qianxiang:ember_crystal")));
        }
        if (matchesAny(want, "吸血", "血", "blood", "drain", "leech")) {
            out.add(new KeywordHint("qianxiang.phasefn.lifesteal", List.of("qianxiang:bloodroot")));
        }
        if (matchesAny(want, "锋", "刃", "刀", "剑", "edge", "blade", "sword", "sharp")) {
            out.add(new KeywordHint("qianxiang.phasefn.edge",
                    List.of("qianxiang:beast_fang", "qianxiang:dragon_bone")));
        }
        if (matchesAny(want, "骨", "bone", "skeleton")) {
            out.add(new KeywordHint("qianxiang.phasefn.base_bone", List.of("qianxiang:dragon_bone")));
        }
        if (matchesAny(want, "盾", "防", "护", "甲", "defense", "defence", "shield", "guard", "tank")) {
            out.add(new KeywordHint("qianxiang.phasefn.defense",
                    List.of("qianxiang:abyss_iron", "qianxiang:salamander_gland")));
        }
        if (matchesAny(want, "法", "魔", "术", "mana", "magic", "spell", "wizard", "mage")) {
            out.add(new KeywordHint("qianxiang.phasefn.mana", List.of("qianxiang:rift_essence")));
        }
        if (matchesAny(want, "治疗", "回血", "生命", "heal", "recovery", "medic")) {
            out.add(new KeywordHint("qianxiang.phasefn.heal", List.of("qianxiang:glimmer_wood_sap")));
        }
        if (matchesAny(want, "迟缓", "减速", "控场", "slow", "cc")) {
            out.add(new KeywordHint("qianxiang.phasefn.slow", List.of("qianxiang:shadowhide_patch")));
        }
        // ---- 扩展关键词：新效果 / 装备穿戴效果 / 工具功能（与 keywordPicks 同步维护） ----
        if (matchesAny(want, "反伤", "荆棘", "thorns", "reflect")) {
            String reflect = findByFunction("REFLECT");
            if (reflect != null) out.add(new KeywordHint("qianxiang.phasefn.reflect", List.of(reflect)));
        }
        if (matchesAny(want, "夜视", "night vision", "nightvision")) {
            out.add(new KeywordHint("qianxiang.phasefn.night_vision", List.of("minecraft:golden_carrot")));
        }
        if (matchesAny(want, "中毒", "毒", "poison", "venom")) {
            out.add(new KeywordHint("qianxiang.phasefn.poison",
                    List.of("minecraft:spider_eye", "minecraft:pufferfish")));
        }
        if (matchesAny(want, "霜冻", "冰冻", "冻结", "冰", "frost", "freeze", "ice")) {
            out.add(new KeywordHint("qianxiang.phasefn.frost",
                    List.of("minecraft:snowball", "minecraft:ice")));
        }
        if (matchesAny(want, "漂浮", "浮空", "levitation", "levitate", "float")) {
            out.add(new KeywordHint("qianxiang.phasefn.levitation", List.of("minecraft:shulker_shell")));
        }
        if (matchesAny(want, "力量", "强力", "strength")) {
            out.add(new KeywordHint("qianxiang.phasefn.strength", List.of("minecraft:blaze_powder")));
        }
        if (matchesAny(want, "速度", "迅捷", "加速", "speed", "swift")) {
            out.add(new KeywordHint("qianxiang.phasefn.speed_boost", List.of("minecraft:sugar")));
        }
        if (matchesAny(want, "跳跃", "弹跳", "jump", "leap")) {
            out.add(new KeywordHint("qianxiang.phasefn.jump_boost", List.of("minecraft:rabbit_foot")));
        }
        if (matchesAny(want, "抗性", "坚韧", "resistance", "tough")) {
            // 1.20.5+ 海龟鳞甲改名为 turtle_scute，两个 id 都给，客户端解析时自动跳过不存在的
            out.add(new KeywordHint("qianxiang.phasefn.resistance",
                    List.of("minecraft:turtle_scute", "minecraft:scute")));
        }
        if (matchesAny(want, "抗火", "防火", "耐火", "fire resist", "fire_resist", "fireproof")) {
            out.add(new KeywordHint("qianxiang.phasefn.fire_resist", List.of("minecraft:magma_cream")));
        }
        if (matchesAny(want, "水下呼吸", "水下", "潜水", "water breath", "waterbreath", "water")) {
            out.add(new KeywordHint("qianxiang.phasefn.water_breath", List.of("minecraft:pufferfish")));
        }
        if (matchesAny(want, "再生", "regen", "regeneration")) {
            out.add(new KeywordHint("qianxiang.phasefn.regeneration", List.of("minecraft:ghast_tear")));
        }
        if (matchesAny(want, "耕地", "锄头", "耕作", "犁地", "收割", "hoe", "farm", "till", "harvest")) {
            String areaHarvest = findByFunction("AREA_HARVEST");
            if (areaHarvest != null) {
                out.add(new KeywordHint("qianxiang.phasefn.area_harvest", List.of(areaHarvest)));
            }
        }
        if (matchesAny(want, "水壶", "浇水", "催熟", "生长", "灌溉", "施肥", "grow", "growth", "watering", "fertiliz")) {
            String growth = findByFunction("GROWTH");
            if (growth != null) out.add(new KeywordHint("qianxiang.phasefn.growth", List.of(growth)));
        }
        return out;
    }
}
