package com.qianxiang.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qianxiang.Qianxiang;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.AiRequestPayload;
import com.qianxiang.phase.AttributeScheme;
import com.qianxiang.phase.EffectGlossary;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseTier;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 「自然语言 → 材料清单」核心服务——千相的配方大脑。
 * <p>
 * 流程：
 * <ol>
 *   <li>取 {@link MaterialLibrary#snapshot()}：把项目所有相材料整理给 AI 看。</li>
 *   <li>构造 system prompt：约束 LLM「只能从这些材料里挑」（上限见 {@link #maxProposalMaterials}），
 *       并要求「只输出 JSON」。</li>
 *   <li>调 {@link OllamaClient#chat}；失败/空 → 退 {@link FallbackRecipes}。</li>
 *   <li>解析 JSON（Minecraft 自带 Gson，不引依赖）。
 *       逐个材料名用 {@link MaterialLibrary#exists} 校验真实性，剔除 LLM 瞎编的。</li>
 *   <li>全部无效 → 退 FallbackRecipes。</li>
 *   <li>用入选材料的 functions/tier 构造 {@link AttributeScheme.MaterialInput}，
 *       调 {@link AttributeScheme#compose} 得到 {@code powerScore}，作为「概念期就谈强度」的依据。</li>
 * </ol>
 * <p>
 * <b>契约</b>：{@link #ask} 永不抛异常。任何分支都至少返回一份 FallbackRecipes。
 * 这样命令层可以放心调用，runClient 不会因为 AI 抽风而崩。
 */
public final class PhaseAIRecipeService {

    private PhaseAIRecipeService() {}

    // ===================== 动作定制（EF 连击）契约 =====================

    /**
     * moveset.category 合法值（按武器形态选，与动作集子代理 WeaponMoveset 契约一致）：
     * 巨剑→greatsword、匕首→dagger、太刀→tachi、长枪→spear、斧→axe、
     * 剑→sword/longsword、打刀/居合→uchigatana。
     */
    static final Set<String> MOVESET_CATEGORIES = Set.of(
            "greatsword", "dagger", "tachi", "spear", "axe", "sword", "longsword", "uchigatana");

    /** moveset.combos 动画 id 必须落在 EF 战斗动画命名空间/路径下（AI 只能组合已有动画）。 */
    static final String MOVESET_ANIM_NAMESPACE = "epicfight";
    static final String MOVESET_ANIM_PATH_PREFIX = "biped/combat/";

    /**
     * 单个方案允许的最大材料数：材料槽位数 - 1（给玩家留一个自由槽），至少 4。
     * 材料槽由 {@link ForgeTableMenu#MATERIAL_SLOTS} 统一定义，槽位扩展后这里自动放大，
     * 「全部负面/全部增益」这类全集组合（见 {@link EffectGlossary}）才放得下。
     */
    static int maxProposalMaterials() {
        return Math.max(4, ForgeTableMenu.MATERIAL_SLOTS - 1);
    }

    /**
     * AI 配方提案：材料清单（registry name）+ 估强度（powerScore）+ 一句话说明。
     * <p>这是本服务对外返回的「合同」类型；{@link FallbackRecipes} 也返回同一类型，
     * 命令层与上层模块一律引用 {@code PhaseAIRecipeService.RecipeProposal}。
     */
    public record RecipeProposal(List<String> materialNames, double estimatedPower, String summary, String spellJson,
                                 String movesetJson) {

        /**
         * 兼容旧三参构造：spellJson 默认空串（=无自由法术）。
         * spellJson 为自由法术描述 JSON 字符串（契约：element/form/effect/modifiers/power），
         * 由法术核心子代理的 CustomSpell 消费；本服务只生成/透传字符串，不依赖该类。
         */
        public RecipeProposal(List<String> materialNames, double estimatedPower, String summary) {
            this(materialNames, estimatedPower, summary, "");
        }

        /**
         * 兼容旧四参构造：movesetJson 默认空串（=无动作定制）。
         * movesetJson 为 EF 连击描述 JSON 字符串（契约：category/combos/collider），
         * 由动作集子代理的 WeaponMoveset 消费；本服务只生成/透传字符串，不依赖该类。
         */
        public RecipeProposal(List<String> materialNames, double estimatedPower, String summary, String spellJson) {
            this(materialNames, estimatedPower, summary, spellJson, "");
        }

        /** 是否携带自由法术描述。 */
        public boolean hasSpell() {
            return spellJson != null && !spellJson.isBlank();
        }

        /** 是否携带 EF 连击动作定制描述。 */
        public boolean hasMoveset() {
            return movesetJson != null && !movesetJson.isBlank();
        }

        /** 便于判断本次结果是否来自关键词回退（summary 以基础配方前缀或 key 开头）。 */
        public boolean isFallback() {
            return summary != null && (summary.startsWith("（基础配方）")
                    || summary.startsWith("qianxiang.forge_table.summary.fallback"));
        }

        /** 网络序列化：用于 {@link com.qianxiang.network.AiResponsePayload}。spellJson/movesetJson 放最后。 */
        public static final StreamCodec<FriendlyByteBuf, RecipeProposal> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), RecipeProposal::materialNames,
                        ByteBufCodecs.DOUBLE, RecipeProposal::estimatedPower,
                        ByteBufCodecs.STRING_UTF8, RecipeProposal::summary,
                        ByteBufCodecs.STRING_UTF8, RecipeProposal::spellJson,
                        ByteBufCodecs.STRING_UTF8, RecipeProposal::movesetJson,
                        RecipeProposal::new);
    }

    /**
     * 完整推荐结果：多个档位方案 + 确认消息 + 反问选项。
     * <p>"recommend" 模式返回若干方案，"confirm" 模式返回 1 个确认方案 + 修改建议。
     * {@code suggestQuestions}（可空）：需求模糊时给玩家的 2~3 个反问选项词，
     * 客户端点选后追加到输入框重新问 AI。
     */
    public record RecipeResult(List<RecipeProposal> proposals, String confirmMessage, boolean fallback,
                               List<String> suggestQuestions) {
        /** 兼容旧三参构造：suggestQuestions 空 = 无反问。 */
        public RecipeResult(List<RecipeProposal> proposals, String confirmMessage, boolean fallback) {
            this(proposals, confirmMessage, fallback, List.of());
        }
    }

    /**
     * 旧版单方案入口，保留给命令层使用。
     *
     * @param playerWant 玩家输入
     * @return 永远非 null 的单方案
     */
    public static RecipeProposal ask(String playerWant) {
        RecipeResult result = ask(playerWant, "weapon", "rare", List.of(), "recommend");
        return result.proposals().isEmpty()
                ? new RecipeProposal(List.of(), 0.0, "")
                : result.proposals().getFirst();
    }

    /**
     * 新版带产物类型、档位、当前材料、模式的入口。
     *
     * @param playerWant       玩家输入
     * @param targetType       产物类型：weapon/magic/armor/tool
     * @param targetTier       目标档位：common/rare/epic/legendary
     * @param currentMaterials 当前材料槽中的 registry name 列表
     * @param mode             "recommend" 或 "confirm"
     * @return 永远非 null 的完整结果
     */
    public static RecipeResult ask(String playerWant, String targetType, String targetTier,
                                    List<String> currentMaterials, String mode) {
        return ask(playerWant, targetType, targetTier, currentMaterials, mode, List.of());
    }

    /**
     * 带材料白名单的完整入口。
     *
     * @param allowedMaterials 玩家勾选的材料白名单（registry name）；null/空 = 不限制（全部材料可用）
     * @return 永远非 null 的完整结果
     */
    public static RecipeResult ask(String playerWant, String targetType, String targetTier,
                                    List<String> currentMaterials, String mode, List<String> allowedMaterials) {
        try {
            String type = safeType(targetType);
            String tier = safeTier(targetTier);
            String m = mode == null ? "recommend" : mode.trim().toLowerCase(Locale.ROOT);
            List<String> mats = currentMaterials == null ? List.of() : currentMaterials;
            Set<String> allowed = normalizeAllowed(allowedMaterials);

            List<MaterialLibrary.MaterialEntry> lib = MaterialLibrary.snapshot();
            if (allowed != null) {
                // 材料白名单：prompt 只列玩家勾选的材料
                lib = lib.stream().filter(e -> allowed.contains(e.registryName())).toList();
            }
            if (lib.isEmpty()) {
                return "confirm".equals(m)
                        ? FallbackRecipes.proposeForConfirm(playerWant, type, tier, mats, allowedMaterials)
                        : FallbackRecipes.propose3(playerWant, type, tier, allowedMaterials);
            }

            String systemPrompt = buildSystemPrompt(lib, playerWant, type, tier, mats, m, allowed != null);
            var aiOpt = AIGateway.chat(playerWant, systemPrompt);
            if (aiOpt.isEmpty()) {
                return "confirm".equals(m)
                        ? FallbackRecipes.proposeForConfirm(playerWant, type, tier, mats, allowedMaterials)
                        : FallbackRecipes.propose3(playerWant, type, tier, allowedMaterials);
            }

            String confirmMessage = extractConfirmMessage(aiOpt.get());
            List<String> questions = extractQuestions(aiOpt.get());
            List<RecipeProposal> proposals = parseProposals(aiOpt.get(), type);
            proposals = restrictToWhitelist(proposals, allowed);
            if (proposals.isEmpty()) {
                return "confirm".equals(m)
                        ? FallbackRecipes.proposeForConfirm(playerWant, type, tier, mats, allowedMaterials)
                        : FallbackRecipes.propose3(playerWant, type, tier, allowedMaterials);
            }

            if (!"confirm".equals(m)) {
                proposals = ensureTierMatch(proposals, type, tier);
                proposals = restrictToWhitelist(proposals, allowed);
                if (proposals.isEmpty()) {
                    return FallbackRecipes.propose3(playerWant, type, tier, allowedMaterials);
                }
            }
            boolean fallback = allFallback(proposals);
            // AI 没给反问但需求模糊时，用关键词检测补一组反问选项
            if (questions.isEmpty() && !"confirm".equals(m)) {
                questions = FallbackRecipes.suggestQuestions(playerWant, type);
            }
            return new RecipeResult(proposals, confirmMessage, fallback, questions);
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] PhaseAIRecipeService.ask 异常，退 FallbackRecipes：{}",
                    t.getClass().getSimpleName() + ": " + t.getMessage());
            return FallbackRecipes.propose3(playerWant, targetType, targetTier);
        }
    }

    /**
     * 供网络处理器直接使用的便捷入口。
     */
    public static RecipeResult ask(AiRequestPayload payload) {
        return ask(payload.request(), payload.targetType(), payload.targetTier(),
                payload.currentMaterials(), payload.mode(), payload.allowedMaterials());
    }

    // ===================== 材料白名单 =====================

    /**
     * 归一化白名单：null/空/全空白 → null（=不限制）；否则去空白去重的集合。
     */
    static Set<String> normalizeAllowed(List<String> allowedMaterials) {
        if (allowedMaterials == null || allowedMaterials.isEmpty()) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String s : allowedMaterials) {
            if (s == null || s.isBlank()) continue;
            out.add(s.trim());
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 把方案列表裁剪到白名单内：剔除非白名单材料，剩余不足 2 个材料的方案整条丢弃。
     * allowed 为 null（不限制）时原样返回。供 {@link FallbackRecipes} 共用。
     */
    static List<RecipeProposal> restrictToWhitelist(List<RecipeProposal> proposals, List<String> allowedMaterials) {
        return restrictToWhitelist(proposals, normalizeAllowed(allowedMaterials));
    }

    static List<RecipeProposal> restrictToWhitelist(List<RecipeProposal> proposals, Set<String> allowed) {
        if (allowed == null || proposals == null) return proposals == null ? List.of() : proposals;
        List<RecipeProposal> out = new ArrayList<>();
        for (RecipeProposal p : proposals) {
            if (p == null) continue;
            List<String> mats = new ArrayList<>();
            for (String name : p.materialNames()) {
                if (allowed.contains(name)) mats.add(name);
            }
            if (mats.size() >= 2) {
                out.add(new RecipeProposal(mats, p.estimatedPower(), p.summary(), p.spellJson(), p.movesetJson()));
            }
        }
        return out;
    }

    // ===================== Prompt 构造 =====================

    private static String buildSystemPrompt(List<MaterialLibrary.MaterialEntry> lib, String request,
                                            String targetType, String targetTier,
                                            List<String> currentMaterials, String mode) {
        return buildSystemPrompt(lib, request, targetType, targetTier, currentMaterials, mode, false);
    }

    private static String buildSystemPrompt(List<MaterialLibrary.MaterialEntry> lib, String request,
                                            String targetType, String targetTier,
                                            List<String> currentMaterials, String mode, boolean restricted) {
        String typeDesc = typeDescription(targetType);
        String tierDesc = tierDescription(targetTier);
        boolean isConfirm = "confirm".equalsIgnoreCase(mode);
        StringBuilder sb = new StringBuilder();
        sb.append("你是《千相》MC mod 的材料配方 AI。");
        sb.append("玩家需求：").append(request == null ? "" : request).append("；");
        sb.append("目标产物类型：").append(typeDesc).append("；");
        sb.append("目标强度档位：").append(tierDesc).append("（高挡位应优先选高 tier 材料，低挡位优先低 tier）。\n\n");
        if (restricted) {
            sb.append("【材料白名单】玩家已在「材料筛选」中勾选了愿意使用的材料，下列材料库就是白名单——");
            sb.append("每个方案的所有材料必须来自该白名单，严禁使用白名单外的任何材料。\n");
        } else {
            sb.append("【材料范围】玩家未限制材料范围，下列材料库中的全部材料均可使用。\n");
        }
        sb.append("【材料库】（registryName 显示名：功能算子+档位+相性）\n");
        sb.append("材料库现在是全物品库：任何原版物品都有概念与相性——土石草木皆可当辅料");
        sb.append("（泥土=万物之基、石头=厚重、草木=生机），不要局限于高价值物品；");
        sb.append("高档位需求仍以高 tier 材料为核心，普通物品适合当基底/填充/调相辅料。\n");
        for (MaterialLibrary.MaterialEntry e : lib) {
            sb.append("- ").append(e.registryName())
              .append(" | ").append(e.displayName())
              .append(" | functions=").append(e.functions())
              .append(" | tier=").append(e.tier())
              .append('\n');
        }
        sb.append("\n【功能性需求指引】\n");
        sb.append("材料库已扩展功能算子：POISON(中毒)/FROST(霜冻)/LEVITATION(漂浮)/STRENGTH(力量)/");
        sb.append("NIGHT_VISION(夜视)/SPEED_BOOST(迅捷)/JUMP_BOOST(跳跃)/RESISTANCE(抗性)/FIRE_RESIST(抗火)/");
        sb.append("WATER_BREATH(水下呼吸)/REGENERATION(再生)/GROWTH(催熟)/AREA_HARVEST(广域采集)。\n");
        sb.append("玩家可能要求功能性物品，例如「反伤装甲」「夜视头盔」「能耕3×3地的锄头」「催熟5×5作物的水壶」。\n");
        sb.append("- armor（装备/防具）需求：优先挑 DEFENSE/BASE_HIDE/RESISTANCE/REFLECT 类材料；");
        sb.append("夜视/迅捷/跳跃/抗火/水下呼吸/再生等穿戴效果，选带对应功能算子的材料。\n");
        sb.append("- tool（工具）需求：优先挑 AREA_HARVEST/GROWTH 类功能性材料，再配 BASE_METAL/BASE_WOOD 基底。\n");
        sb.append("- weapon（武器）需求：除 EDGE/IGNITE/LIFESTEAL 外，中毒选 POISON、冰冻选 FROST、增伤选 STRENGTH。\n");
        appendFreeEffectSection(sb);
        sb.append("\n【强度代价】\n");
        sb.append("游戏机制：强力产物必带代价。强度总分 >8 或正面效果 ≥3 种时，");
        sb.append("系统会自动给产物附加 1~2 个代价效果（frail 易碎=耐久消耗加倍 / heavy 沉重=移速下降 / ");
        sb.append("draining 耗力=攻击饥饿 / unstable 不稳=概率反噬自己 / cursed 诅咒=随机负面状态），");
        sb.append("强度越高代价越多。具体代价按主导效果映射：吸血→耗力、高攻→沉重、多效果→不稳/诅咒。\n");
        sb.append("因此：不要无脑堆强度，适度即好；若玩家明确要强力的武器/装备，尽管给强组合，");
        sb.append("但必须在 summary 中说明它将付出的代价，例如「这把武器很强但会带来迟缓」「吸血猛但吃着费力」。\n");
        appendFreeSpellSection(sb, targetType);
        appendMovesetSection(sb, targetType);
        appendBroadRequestSection(sb);
        appendEffectGlossarySection(sb);
        sb.append("\n【规则】\n");
        if (isConfirm) {
            sb.append("当前玩家已放入材料：").append(String.join("、", currentMaterials)).append("。\n");
            sb.append("1. 评估这组材料能否实现玩家需求、是否符合目标类型与档位。\n");
            sb.append("2. 在 confirmMessage 中给出结论与修改建议：替换、添加、降级/升级。\n");
            sb.append("3. proposals 中放 1~2 个方案：第 1 个用已给材料做评价，第 2 个（可选）给出建议调整后的材料。\n");
            sb.append("4. 只输出 JSON，不要 Markdown、不要解释。格式：\n");
            sb.append("{\"proposals\":[");
            sb.append("{\"materials\":[\"qianxiang:xxx\",\"qianxiang:yyy\"],\"summary\":\"当前组合评价\"},");
            sb.append("{\"materials\":[\"qianxiang:zzz\",\"qianxiang:www\"],\"summary\":\"建议调整\"}");
            sb.append("],\"confirmMessage\":\"结论与修改建议\"}\n");
            sb.append("5. magic 类型且玩家在描述法术时，每个 proposal 可加可选 spell 字段（见【自由法术系统】）。\n");
            sb.append("6. 玩家在描述攻击动作时，每个 proposal 可加可选 moveset 字段（见【动作定制】）。\n");
        } else {
            sb.append("1. 只能从上述材料里挑，registryName 必须原样照抄（含 namespace 前缀，如 qianxiang: 或 minecraft:）。\n");
            sb.append("2. 每个方案选 2~").append(maxProposalMaterials()).append(" 个材料，覆盖玩家需求与目标类型约束；");
            sb.append("普通需求 2~4 个即可，只有「全部负面/全部增益」这类全集需求才用满上限（见【效果词典】）。\n");
            sb.append("3. 必须给出 1~3 个方案；其中至少一个方案的平均档位要与目标档位 ").append(tierDesc).append(" 匹配。\n");
            sb.append("4. 只输出 JSON，不要 Markdown、不要解释。格式：\n");
            sb.append("{\"proposals\":[");
            sb.append("{\"materials\":[\"qianxiang:xxx\",\"minecraft:yyy\"],\"summary\":\"方案一句话说明\"},");
            sb.append("{\"materials\":[\"minecraft:zzz\",\"qianxiang:www\"],\"summary\":\"若无法完全匹配档位，给出最接近方案并说明\"}");
            sb.append("],\"confirmMessage\":\"\"}\n");
            sb.append("5. magic 类型且玩家在描述法术时，每个 proposal 必须加 spell 字段（见【自由法术系统】），例如：\n");
            sb.append("{\"materials\":[\"minecraft:blaze_powder\",\"minecraft:redstone\"],\"summary\":\"追踪火球\",");
            sb.append("\"spell\":{\"element\":\"fire\",\"form\":\"projectile\",\"effect\":\"damage\",\"modifiers\":[\"homing\"],\"power\":1}}\n");
            sb.append("6. 【反问】若玩家需求模糊（没说清想要的造型或效果，例如只说「一把武器」），");
            sb.append("在 JSON 顶层加 \"questions\" 字段：2~3 个极短的追问选项词（如 [\"巨剑\",\"匕首\",\"火焰\"]），");
            sb.append("每个词都必须能直接追加到玩家输入末尾来细化需求；需求已足够具体则省略该字段。\n");
            sb.append("7. 玩家描述了攻击动作时，每个 proposal 可加可选 moveset 字段（见【动作定制】），例如：\n");
            sb.append("{\"materials\":[\"qianxiang:ember_iron\",\"qianxiang:beast_fang\"],\"summary\":\"三段连斩太刀\",");
            sb.append("\"moveset\":{\"category\":\"tachi\",\"combos\":[\"epicfight:biped/combat/tachi_auto1\",");
            sb.append("\"epicfight:biped/combat/tachi_auto2\",\"epicfight:biped/combat/tachi_auto3\"],\"collider\":\"tachi\"}}\n");
        }
        return sb.toString();
    }

    /**
     * 自由法术系统指引：magic 类型需求可以要求元素×形式×效果×修饰的自由组合，不限魔法类型。
     * LLM 需把玩家的法术描述翻译成 spell JSON（契约：element/form/effect/modifiers/power）。
     */
    private static void appendFreeSpellSection(StringBuilder sb, String targetType) {
        sb.append("\n【自由法术系统】\n");
        sb.append("本作法术不限制类型，任何「元素×形式×效果×修饰」组合都合法：\n");
        sb.append("- 元素 element（选一）：fire 火焰 / frost 寒霜 / lightning 雷电 / nature 自然 / shadow 暗影 / ");
        sb.append("holy 神圣 / blood 鲜血 / ender 末影 / arcane 奥术。\n");
        sb.append("- 形式 form（选一）：projectile 投射 / self 自身 / aoe 范围 / beam 光束 / touch 触击。\n");
        sb.append("- 效果 effect（选一）：damage 伤害 / heal 治疗 / buff 增益 / debuff 减益 / utility 功能。\n");
        sb.append("- 修饰 modifiers（可多选，可空）：homing 追踪 / piercing 穿透 / extended 持续 / amplified 强化 / chain 连锁。\n");
        sb.append("- power：1~10 的数，按目标档位与描述强度给（普通1~3、稀有3~5、史诗5~8、传奇8~10）。\n");
        sb.append("示例：「追踪火球」={\"element\":\"fire\",\"form\":\"projectile\",\"effect\":\"damage\",\"modifiers\":[\"homing\"],\"power\":2}；");
        sb.append("「范围治疗」={\"element\":\"nature\",\"form\":\"aoe\",\"effect\":\"heal\",\"modifiers\":[],\"power\":2}；");
        sb.append("「雷链」={\"element\":\"lightning\",\"form\":\"projectile\",\"effect\":\"damage\",\"modifiers\":[\"chain\"],\"power\":3}。\n");
        if ("magic".equals(safeType(targetType))) {
            sb.append("当前是 magic 需求：请从玩家描述中提炼上述组合，填进每个 proposal 的 spell 字段；");
            sb.append("材料需与元素呼应（火→烈焰粉/岩浆膏，冰→雪球/冰，雷→红石+金，自然→骨粉/种子，");
            sb.append("暗影→墨囊/回响，神圣→金胡萝卜/荧石，鲜血→蜘蛛眼/血根，末影→末影珍珠，奥术→青金石/裂隙精髓）。\n");
        } else {
            sb.append("当前不是 magic 需求，可忽略 spell 字段。\n");
        }
    }

    /**
     * 动作定制指引：玩家描述攻击动作（「三段连斩」「回旋斩」「突刺连击」「重劈」「双刀乱舞」「拔刀斩」等）时，
     * LLM 需从 Epic Fight 动画库挑 1~4 个动画组成连击序列，填进 proposal 的可选 moveset 字段
     * （契约：category/combos/collider，由动作集子代理的 WeaponMoveset 消费）。
     * 硬边界：AI 只能组合 EF 现有动画，不能编造库外动画。
     */
    private static void appendMovesetSection(StringBuilder sb, String targetType) {
        sb.append("\n【动作定制（Epic Fight 连击）】\n");
        sb.append("玩家若描述了攻击动作（如「三段连斩」「回旋斩」「突刺连击」「重劈」「双刀乱舞」「拔刀斩」「居合」），");
        sb.append("除挑材料外，还要从下方动画库挑 1~4 个动画组成 combos 连击序列，");
        sb.append("在 proposal 里加可选 moveset 字段：\n");
        sb.append("{\"category\":\"tachi\",\"combos\":[\"epicfight:biped/combat/tachi_auto1\",");
        sb.append("\"epicfight:biped/combat/tachi_auto2\"],\"collider\":\"tachi\"}\n");
        sb.append("- category 按武器形态选一：巨剑→greatsword、匕首→dagger、太刀→tachi、长枪→spear、");
        sb.append("斧→axe、剑→sword/longsword、打刀/居合→uchigatana；collider 通常与 category 相同。\n");
        sb.append("- combos 的动画 id 必须原样照抄下方动画库（带 epicfight:biped/combat/ 前缀），");
        sb.append("严禁编造库外动画——只能组合现有动画，不能生成新动画。\n");
        sb.append("- 语义匹配：连斩/连击→COMBO 序列、突刺/突进/冲刺→DASH、跳劈/空斩→AIRSLASH、");
        sb.append("重劈/重击→HEAVY、快攻/连刺→FAST、双刀/双持→DUAL、拔刀/居合→SHEATH、");
        sb.append("德式剑术→LIECHTENAUER、骑乘→MOUNT；同类动画按顺序排列即成连击段序。\n");
        sb.append("- 玩家没描述攻击动作时省略 moveset 字段。\n");
        sb.append("【Epic Fight 动画库】\n");
        try {
            sb.append(com.qianxiang.compat.MovesetCompat.promptSummary()).append('\n');
        } catch (Throwable t) {
            // 动画库摘要失败不拖垮 prompt
        }
        if (!"weapon".equals(safeType(targetType))) {
            sb.append("当前不是 weapon 需求：玩家仍描述了攻击动作时才给 moveset，否则忽略该字段。\n");
        }
    }

    /**
     * 宽泛需求语义：告诉 LLM「所有效果」「全负面」「随机」这类说法怎么落地成材料组合。
     * 「全部负面/全部增益」的完整效果清单与材料对照见【效果词典】段（{@link EffectGlossary}）。
     */
    private static void appendBroadRequestSection(StringBuilder sb) {
        sb.append("\n【宽泛需求语义】\n");
        sb.append("- 「所有效果 / 全效果 / 全部正面 / buff 全要」：玩家想要多种正面效果的组合——");
        sb.append("按【效果词典】的 BUFFS 顺序尽量选齐增益效果材料（抗火→岩浆膏、力量→烈焰粉、迅捷→糖、");
        sb.append("跳跃→兔子脚、缓降→幻影膜、生命恢复→恶魂之泪、抗性→海龟鳞甲、水下呼吸→河豚、夜视→金胡萝卜…）。\n");
        sb.append("- 「所有负面 / 全负面 / 全部负面 / debuff 全要 / 诅咒」：玩家想要多种负面效果的组合——");
        sb.append("按【效果词典】的 DEBUFFS 顺序尽量选齐负面效果材料（灼烧→余烬石/烈焰粉、冻伤→雪球/冰、");
        sb.append("中毒→蜘蛛眼/河豚、凋零→凋零玫瑰、缓慢→迟缓材料、饥饿→腐肉、虚弱→发酵蛛眼、寄生→虫蚀方块…），");
        sb.append("再配 BASE_* 基底。\n");
        sb.append("- 「随机 / 随便 / 惊喜」：随机挑 2~3 个效果材料组合，鼓励意外但可用的搭配，并在 summary 里说明随机到了什么。\n");
    }

    /**
     * 效果词典摘要：把 {@link EffectGlossary} 的负面/增益全集与「效果 → 典型材料」对照
     * 嵌进 prompt，让 LLM 对「全部负面状态」「全部增益状态」有确定的理解。
     */
    private static void appendEffectGlossarySection(StringBuilder sb) {
        try {
            sb.append(EffectGlossary.promptSummary(maxProposalMaterials()));
        } catch (Throwable t) {
            // 词典摘要失败不拖垮 prompt
        }
    }

    /**
     * 自由状态效果指引：列出当前数据包注册的全部 qianxiang:materials/effect/* 材料，
     * 告诉 LLM 任意状态效果需求（「凋零刀」「隐身斗篷」等）都可以用效果材料 + BASE_* 基底实现。
     */
    private static void appendFreeEffectSection(StringBuilder sb) {
        java.util.Map<String, List<String>> index;
        try {
            index = MaterialLibrary.effectMaterialIndex();
        } catch (Throwable t) {
            return; // 索引失败不拖垮 prompt
        }
        if (index == null || index.isEmpty()) {
            return;
        }
        sb.append("\n【自由状态效果材料】\n");
        sb.append("以下材料带 qianxiang:materials/effect/* 标签，可为产物附加任意 MC 状态效果");
        sb.append("（武器=攻击时施加给目标，防具=穿戴时持续生效）：\n");
        for (var e : index.entrySet()) {
            sb.append("- ").append(MaterialLibrary.effectConcept(e.getKey()))
              .append("(").append(e.getKey()).append(")：")
              .append(String.join("、", e.getValue()))
              .append('\n');
        }
        sb.append("玩家要求任意状态效果时（如「凋零刀」「隐身斗篷」「幸运工具」），");
        sb.append("在 2~4 个材料中选 1 个对应效果材料，再搭配 BASE_* 基底与目标类型材料即可实现。\n");
    }

    private static String typeDescription(String type) {
        return switch (safeType(type)) {
            case "magic" -> "魔法/法杖（优先 MANA 或 IGNITE/LIFESTEAL/SLOW/HEAL/POISON/FROST/REGENERATION 效果）";
            case "armor" -> "装备/防具（优先 DEFENSE、BASE_HIDE、RESISTANCE、REFLECT；穿戴效果如 NIGHT_VISION/SPEED_BOOST/JUMP_BOOST/FIRE_RESIST/WATER_BREATH/REGENERATION）";
            case "tool"  -> "工具（优先 AREA_HARVEST/GROWTH 等功能材料 + BASE_METAL/BASE_WOOD 基底，或 EDGE/IGNITE/SLOW 等实用效果）";
            default      -> "武器（优先 EDGE、IGNITE、LIFESTEAL、POISON、FROST、STRENGTH、BASE_METAL/BASE_BONE）";
        };
    }

    private static String tierDescription(String tier) {
        return switch (safeTier(tier)) {
            case "common"    -> "普通";
            case "rare"      -> "稀有";
            case "epic"      -> "史诗";
            case "legendary" -> "传奇";
            default          -> "稀有";
        };
    }

    // ===================== JSON 解析与真实性校验 =====================

    private static List<RecipeProposal> parseProposals(String aiOutput, String targetType) {
        String json = extractJson(aiOutput);
        if (json == null) return List.of();

        JsonObject obj;
        try {
            obj = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return List.of();
        }

        List<RecipeProposal> result = new ArrayList<>();
        if (obj.has("proposals") && obj.get("proposals").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("proposals")) {
                if (!el.isJsonObject()) continue;
                RecipeProposal p = parseSingleProposal(el.getAsJsonObject(), targetType);
                if (!p.materialNames().isEmpty()) {
                    result.add(p);
                }
            }
        }

        // 兼容旧版单方案格式
        if (result.isEmpty()) {
            RecipeProposal single = parseSingleProposal(obj, targetType);
            if (!single.materialNames().isEmpty()) {
                result.add(single);
            }
        }

        return result;
    }

    private static String extractConfirmMessage(String aiOutput) {
        String json = extractJson(aiOutput);
        if (json == null) return "";
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("confirmMessage") && obj.get("confirmMessage").isJsonPrimitive()) {
                return obj.get("confirmMessage").getAsString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 提取 AI 给的反问选项（顶层 {@code "questions"} 数组）。
     * 只保留非空、≤12 字的短词，最多 3 个；解析失败/无字段 → 空列表。永不抛异常。
     */
    private static List<String> extractQuestions(String aiOutput) {
        String json = extractJson(aiOutput);
        if (json == null) return List.of();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("questions") || !obj.get("questions").isJsonArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("questions")) {
                if (!el.isJsonPrimitive()) continue;
                String q = el.getAsString().trim();
                if (q.isEmpty() || q.length() > 12 || out.contains(q)) continue;
                out.add(q);
                if (out.size() >= 3) break;
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static RecipeProposal parseSingleProposal(JsonObject obj, String targetType) {
        Set<String> picks = new LinkedHashSet<>();
        if (obj.has("materials") && obj.get("materials").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("materials")) {
                if (!el.isJsonPrimitive()) continue;
                String name = el.getAsString();
                if (name == null || name.isBlank()) continue;
                String normalized = normalizeName(name.trim());
                if (MaterialLibrary.exists(normalized)) {
                    picks.add(normalized);
                } else {
                    Qianxiang.LOGGER.warn("[Qianxiang] AI 输出了不存在的材料，已剔除：{}", name);
                }
            }
        }

        String summary = obj.has("summary") && obj.get("summary").isJsonPrimitive()
                ? obj.get("summary").getAsString()
                : "";

        if (picks.isEmpty()) {
            return new RecipeProposal(List.of(), 0.0, "");
        }

        List<String> materialNames = new ArrayList<>(picks);
        if (materialNames.size() > maxProposalMaterials()) {
            materialNames = new ArrayList<>(materialNames.subList(0, maxProposalMaterials()));
        }

        double power = estimatePower(materialNames);
        String sumText = (summary == null || summary.isBlank())
                ? "qianxiang.forge_table.summary.default"
                : summary;
        String spellJson = "magic".equals(safeType(targetType)) ? parseSpellJson(obj) : "";
        String movesetJson = parseMovesetJson(obj);
        return new RecipeProposal(materialNames, power, sumText, spellJson, movesetJson);
    }

    /**
     * 解析并校验 LLM 输出的可选 spell 字段。
     * <p>本方法只负责宽容补默认值（LLM 常漏字段）：form/effect 缺失或越出词表时回填
     * projectile/damage；element 不补——必须显式给出。之后统一交给
     * {@link CustomSpell#fromSpellJson}（spellJson 的唯一校验入口）：element 必须在白名单内、
     * modifiers 逐个过滤、power 夹到 1~10。任何一步不合法 → 返回 ""（无法术），不抛异常。
     */
    static String parseSpellJson(JsonObject proposalObj) {
        try {
            if (proposalObj == null || !proposalObj.has("spell") || !proposalObj.get("spell").isJsonObject()) {
                return "";
            }
            JsonObject spell = proposalObj.getAsJsonObject("spell").deepCopy();

            String form = spell.has("form") && spell.get("form").isJsonPrimitive()
                    ? spell.get("form").getAsString().trim().toLowerCase(Locale.ROOT) : "";
            if (!CustomSpell.FORMS.contains(form)) spell.addProperty("form", "projectile");

            String effect = spell.has("effect") && spell.get("effect").isJsonPrimitive()
                    ? spell.get("effect").getAsString().trim().toLowerCase(Locale.ROOT) : "";
            if (!CustomSpell.EFFECTS.contains(effect)) spell.addProperty("effect", "damage");

            CustomSpell parsed = CustomSpell.fromSpellJson(spell.toString(), 1);
            if (parsed == null) {
                Qianxiang.LOGGER.warn("[Qianxiang] AI 输出了非法 spell 字段，已丢弃：{}", spell);
                return "";
            }

            JsonObject normalized = new JsonObject();
            normalized.addProperty("element", parsed.element());
            normalized.addProperty("form", parsed.form());
            normalized.addProperty("effect", parsed.effect());
            var modArr = new com.google.gson.JsonArray();
            for (String m : parsed.modifiers()) modArr.add(m);
            normalized.add("modifiers", modArr);
            normalized.addProperty("power", parsed.power());
            return normalized.toString();
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 解析 spell 字段失败，已忽略：{}", t.getMessage());
            return "";
        }
    }

    /**
     * 解析并校验 LLM 输出的可选 moveset 字段（动作定制：EF 连击）。
     * <p>契约（与动作集子代理 WeaponMoveset 一致）：{category, combos, collider}。
     * category 必须在 {@link #MOVESET_CATEGORIES} 内；combos 逐个校验——
     * 必须是 epicfight:biped/combat/ 下的合法 ResourceLocation（AI 只能组合 EF 现有动画），
     * 去重、上限 4 个；collider 缺省取 category。任何一步不合法 → 返回 ""（无动作定制），不抛异常。
     */
    static String parseMovesetJson(JsonObject proposalObj) {
        try {
            if (proposalObj == null || !proposalObj.has("moveset") || !proposalObj.get("moveset").isJsonObject()) {
                return "";
            }
            JsonObject moveset = proposalObj.getAsJsonObject("moveset");

            String category = moveset.has("category") && moveset.get("category").isJsonPrimitive()
                    ? moveset.get("category").getAsString().trim().toLowerCase(Locale.ROOT) : "";
            if (!MOVESET_CATEGORIES.contains(category)) {
                Qianxiang.LOGGER.warn("[Qianxiang] AI 输出了非法动作 category，已丢弃 moveset：{}", category);
                return "";
            }

            List<String> combos = new ArrayList<>();
            if (moveset.has("combos") && moveset.get("combos").isJsonArray()) {
                for (JsonElement el : moveset.getAsJsonArray("combos")) {
                    if (!el.isJsonPrimitive()) continue;
                    String raw = el.getAsString().trim().toLowerCase(Locale.ROOT);
                    var id = net.minecraft.resources.ResourceLocation.tryParse(raw);
                    // 真实性校验：只允许 EF 战斗动画库内的 id，剔除 LLM 瞎编的动画
                    if (id == null || !MOVESET_ANIM_NAMESPACE.equals(id.getNamespace())
                            || !id.getPath().startsWith(MOVESET_ANIM_PATH_PREFIX)) {
                        Qianxiang.LOGGER.warn("[Qianxiang] AI 输出了库外动画，已剔除：{}", raw);
                        continue;
                    }
                    String normalized = id.toString();
                    if (!combos.contains(normalized)) combos.add(normalized);
                    if (combos.size() >= 4) break;
                }
            }
            if (combos.isEmpty()) {
                return "";
            }

            String collider = moveset.has("collider") && moveset.get("collider").isJsonPrimitive()
                    ? moveset.get("collider").getAsString().trim().toLowerCase(Locale.ROOT) : "";
            if (!MOVESET_CATEGORIES.contains(collider)) collider = category;

            JsonObject normalized = new JsonObject();
            normalized.addProperty("category", category);
            var comboArr = new com.google.gson.JsonArray();
            for (String c : combos) comboArr.add(c);
            normalized.add("combos", comboArr);
            normalized.addProperty("collider", collider);
            return normalized.toString();
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 解析 moveset 字段失败，已忽略：{}", t.getMessage());
            return "";
        }
    }

    /** LLM 有时会裹 markdown ```json ... ```，这里抽出最外层 { ... }。 */
    private static String extractJson(String raw) {        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
    }

    /** 容错：AI 可能漏 namespace，补上 qianxiang:。 */
    static String normalizeName(String name) {
        if (name == null) return "";
        if (name.contains(":")) return name;
        return "qianxiang:" + name;
    }

    static double estimatePower(List<String> materialNames) {
        List<AttributeScheme.MaterialInput> inputs = new ArrayList<>();
        for (String name : materialNames) {
            var entryOpt = MaterialLibrary.find(name);
            if (entryOpt.isEmpty()) continue;
            var e = entryOpt.get();
            Set<PhaseFunction> fns = e.functions();
            if (fns == null || fns.isEmpty()) continue;
            PhaseTier tier = e.tier() == null ? PhaseTier.COMMON : e.tier();
            inputs.add(AttributeScheme.MaterialInput.of(tier, fns.toArray(new PhaseFunction[0])));
        }
        if (inputs.isEmpty()) return 0.0;
        return AttributeScheme.compose(inputs).powerScore();
    }

    /**
     * 保证返回列表中至少有一个方案的平均档位与目标档位匹配。
     * 若全部不匹配，追加一个最接近方案并标注。
     */
    private static List<RecipeProposal> ensureTierMatch(List<RecipeProposal> proposals, String targetType, String targetTier) {
        PhaseTier target = tierFromString(targetTier);
        for (RecipeProposal p : proposals) {
            if (averageTier(p.materialNames()) == target) {
                return proposals;
            }
        }

        // 没有精确匹配：用 fallback 生成一个最接近的方案追加进去
        RecipeProposal closest = FallbackRecipes.closestTierProposal(targetType, target);
        List<RecipeProposal> extended = new ArrayList<>(proposals);
        if (!closest.materialNames().isEmpty()) {
            extended.add(closest);
        }
        return extended;
    }

    /** 计算一组材料的平均档位。 */
    static PhaseTier averageTier(List<String> materialNames) {
        if (materialNames == null || materialNames.isEmpty()) return PhaseTier.COMMON;
        double sum = 0;
        int count = 0;
        for (String name : materialNames) {
            var opt = MaterialLibrary.find(name);
            if (opt.isEmpty()) continue;
            sum += opt.get().tier().ordinal();
            count++;
        }
        if (count == 0) return PhaseTier.COMMON;
        int avg = (int) Math.round(sum / count);
        return PhaseTier.values()[Math.clamp(avg, 0, PhaseTier.values().length - 1)];
    }

    private static boolean allFallback(List<RecipeProposal> proposals) {
        for (RecipeProposal p : proposals) {
            if (!p.isFallback()) return false;
        }
        return !proposals.isEmpty();
    }

    static String safeType(String type) {
        if (type == null) return "weapon";
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "magic", "armor", "tool" -> type.toLowerCase(Locale.ROOT);
            default -> "weapon";
        };
    }

    static String safeTier(String tier) {
        if (tier == null) return "rare";
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "common", "rare", "epic", "legendary" -> tier.toLowerCase(Locale.ROOT);
            default -> "rare";
        };
    }

    static PhaseTier tierFromString(String tier) {
        return switch (safeTier(tier)) {
            case "common" -> PhaseTier.COMMON;
            case "epic" -> PhaseTier.EPIC;
            case "legendary" -> PhaseTier.LEGENDARY;
            default -> PhaseTier.RARE;
        };
    }

    /** 按产物类型与档位筛选最贴近目标档位的材料。 */
    static Optional<MaterialLibrary.MaterialEntry> findByTypeAndTier(String targetType, PhaseTier tier) {
        for (var e : MaterialLibrary.snapshot()) {
            if (e.tier() != tier) continue;
            if (matchesType(e, targetType)) return Optional.of(e);
        }
        return Optional.empty();
    }

    static boolean matchesType(MaterialLibrary.MaterialEntry e, String targetType) {
        Set<PhaseFunction> fns = e.functions();
        if (fns == null || fns.isEmpty()) return true;
        return switch (safeType(targetType)) {
            case "magic" -> fns.contains(PhaseFunction.MANA)
                    || fns.contains(PhaseFunction.IGNITE) || fns.contains(PhaseFunction.LIFESTEAL)
                    || fns.contains(PhaseFunction.SLOW) || fns.contains(PhaseFunction.HEAL)
                    || fns.contains(PhaseFunction.REFLECT)
                    || hasFn(fns, "POISON") || hasFn(fns, "FROST") || hasFn(fns, "REGENERATION");
            case "armor" -> fns.contains(PhaseFunction.DEFENSE) || fns.contains(PhaseFunction.REFLECT)
                    || fns.contains(PhaseFunction.BASE_HIDE) || fns.contains(PhaseFunction.MANA)
                    || hasFn(fns, "RESISTANCE") || hasFn(fns, "NIGHT_VISION") || hasFn(fns, "SPEED_BOOST")
                    || hasFn(fns, "JUMP_BOOST") || hasFn(fns, "FIRE_RESIST") || hasFn(fns, "WATER_BREATH")
                    || hasFn(fns, "REGENERATION");
            case "tool"  -> fns.contains(PhaseFunction.EDGE) || fns.contains(PhaseFunction.IGNITE)
                    || fns.contains(PhaseFunction.SLOW) || fns.contains(PhaseFunction.DEFENSE)
                    || fns.contains(PhaseFunction.BASE_METAL) || fns.contains(PhaseFunction.BASE_WOOD)
                    || hasFn(fns, "AREA_HARVEST") || hasFn(fns, "GROWTH");
            default      -> fns.contains(PhaseFunction.EDGE) || fns.contains(PhaseFunction.IGNITE)
                    || fns.contains(PhaseFunction.LIFESTEAL) || fns.contains(PhaseFunction.BASE_METAL)
                    || fns.contains(PhaseFunction.BASE_BONE)
                    || hasFn(fns, "POISON") || hasFn(fns, "FROST") || hasFn(fns, "STRENGTH")
                    || hasFn(fns, "LEVITATION");
        };
    }

    /**
     * 按算子名（字符串）判断功能集合是否包含该算子。
     * <p>新算子（POISON/FROST/GROWTH/AREA_HARVEST 等）可能尚未进 {@link PhaseFunction} 枚举，
     * 用 name() 比较保证编译期不依赖枚举常量，运行时新算子一加即生效。
     */
    static boolean hasFn(Set<PhaseFunction> fns, String name) {
        if (fns == null) return false;
        for (PhaseFunction f : fns) {
            if (f.name().equals(name)) return true;
        }
        return false;
    }
}
