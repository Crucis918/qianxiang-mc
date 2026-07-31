package com.qianxiang.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qianxiang.Qianxiang;
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
     * 单个方案允许的最大材料数：<b>独立于槽位数</b>——25 槽是玩家的摆放自由度，
     * 不是让 AI 一次推 24 个材料（prompt 预算与方案可读性约束）。
     * 「全部负面/全部增益」这类全集组合（见 {@link EffectGlossary}）12 个也足够覆盖。
     */
    private static final int MAX_PROPOSAL_MATERIALS = 12;

    static int maxProposalMaterials() {
        return MAX_PROPOSAL_MATERIALS;
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

        /**
         * 便于判断本次结果是否来自关键词回退（summary 以基础配方前缀或 key 开头）。
         * <p>必须涵盖 confirm 模式的兜底前缀——漏掉它会让 confirm 兜底时状态灯仍是绿的，
         * 玩家误以为看到的是 AI 的真实评价。
         */
        public boolean isFallback() {
            return summary != null && (summary.startsWith("（基础配方）")
                    || summary.startsWith("qianxiang.forge_table.summary.fallback")
                    || summary.startsWith("qianxiang.forge_table.confirm.summary"));
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
        return ask(playerWant, targetType, targetTier, currentMaterials, mode, allowedMaterials, null);
    }

    /**
     * 带玩家上下文的入口（自创魔法贴合职业）：
     * type==magic 且玩家已设主职业时——AI prompt 注入职业信息（指示优先内核元素、
     * 贴合职业风格），且兜底路径的产物法术元素强制贴合内核（fitClassToCore）。
     */
    public static RecipeResult ask(String playerWant, String targetType, String targetTier,
                                    List<String> currentMaterials, String mode, List<String> allowedMaterials,
                                    @javax.annotation.Nullable net.minecraft.server.level.ServerPlayer player) {
        // 飞轮日志追踪（WQ-71）：本次 ask 的剔除材料与兜底原因，handler 在同一
        // AI 线程 ask 返回后立即读取（单线程执行器，无串扰）。
        LAST_DROPPED.get().clear();
        LAST_FALLBACK_REASON.set("");
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
                LAST_FALLBACK_REASON.set("材料库为空");
                return fitClassToCore("confirm".equals(m)
                        ? FallbackRecipes.proposeForConfirm(playerWant, type, tier, mats, allowedMaterials)
                        : FallbackRecipes.propose3(playerWant, type, tier, allowedMaterials), type, player);
            }

            // confirm 模式的任务是「评价玩家已放的材料」——槽是空的就没什么可评价，
            // 送去问 AI 只会得到一段重新推荐（还白烧一次 token）。
            if ("confirm".equals(m) && (mats == null || mats.isEmpty())) {
                LAST_FALLBACK_REASON.set("confirm 空槽直接兜底");
                return fitClassToCore(
                        FallbackRecipes.proposeForConfirm(playerWant, type, tier, mats, allowedMaterials),
                        type, player);
            }

            String systemPrompt = buildSystemPrompt(lib, playerWant, type, tier, mats, m, allowed != null)
                    + classPromptLine(player, type);
            var aiOpt = AIGateway.chat(playerWant, systemPrompt);
            if (aiOpt.isEmpty()) {
                LAST_FALLBACK_REASON.set("AI 无响应（离线/超时/熔断）");
                return fitClassToCore("confirm".equals(m)
                        ? FallbackRecipes.proposeForConfirm(playerWant, type, tier, mats, allowedMaterials)
                        : FallbackRecipes.propose3(playerWant, type, tier, allowedMaterials), type, player);
            }

            // 只解析一次：此前 extractConfirmMessage / extractQuestions / parseProposals
            // 各自跑一遍 extractJson + JsonParser，同一份回包被解析三次。
            JsonObject root = parseAiRoot(aiOpt.get());
            String confirmMessage = extractConfirmMessage(root);
            List<String> questions = extractQuestions(root);
            List<RecipeProposal> proposals = parseProposals(root, type);
            proposals = restrictToWhitelist(proposals, allowed);
            if (proposals.isEmpty()) {
                LAST_FALLBACK_REASON.set("解析后无有效方案");
                return fitClassToCore("confirm".equals(m)
                        ? FallbackRecipes.proposeForConfirm(playerWant, type, tier, mats, allowedMaterials)
                        : FallbackRecipes.propose3(playerWant, type, tier, allowedMaterials), type, player);
            }

            if (!"confirm".equals(m)) {
                proposals = ensureTierMatch(proposals, type, tier);
                proposals = restrictToWhitelist(proposals, allowed);
                if (proposals.isEmpty()) {
                    LAST_FALLBACK_REASON.set("档位匹配后无方案");
                    return fitClassToCore(FallbackRecipes.propose3(playerWant, type, tier, allowedMaterials),
                            type, player);
                }
            }
            boolean fallback = allFallback(proposals);
            // AI 没给反问但需求模糊时，用关键词检测补一组反问选项
            if (questions.isEmpty() && !"confirm".equals(m)) {
                questions = FallbackRecipes.suggestQuestions(playerWant, type);
            }
            return new RecipeResult(proposals, confirmMessage, fallback, questions);
        } catch (Throwable t) {
            LAST_FALLBACK_REASON.set("ask 异常：" + t.getClass().getSimpleName());
            Qianxiang.LOGGER.warn("[Qianxiang] PhaseAIRecipeService.ask 异常，退 FallbackRecipes：{}",
                    t.getClass().getSimpleName() + ": " + t.getMessage());
            return fitClassToCore(FallbackRecipes.propose3(playerWant, targetType, targetTier),
                    safeType(targetType), player);
        }
    }

    /**
     * 自创魔法贴合职业（兜底路径）：type==magic 且玩家已设主职业时，把产物法术的
     * element 改写为内核元素（已是内核元素不动，其余字段原样保留）。
     * AI 在线路径不做强制改写——prompt 已注入职业信息让 AI 自觉贴合（见 buildSystemPrompt）。
     */
    private static RecipeResult fitClassToCore(RecipeResult result, String type,
                                               net.minecraft.server.level.ServerPlayer player) {
        if (!"magic".equals(type) || player == null || result == null
                || result.proposals().isEmpty()) {
            return result;
        }
        var core = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA)
                .classCore();
        if (!core.isSet()) return result;
        List<RecipeProposal> mapped = new ArrayList<>();
        boolean changed = false;
        for (RecipeProposal p : result.proposals()) {
            if (!p.hasSpell()) {
                mapped.add(p);
                continue;
            }
            String fitted = fitSpellJsonElement(p.spellJson(), core);
            if (fitted.equals(p.spellJson())) {
                mapped.add(p);
                continue;
            }
            changed = true;
            mapped.add(new RecipeProposal(p.materialNames(), p.estimatedPower(), p.summary(),
                    fitted, p.movesetJson()));
        }
        return changed
                ? new RecipeResult(mapped, result.confirmMessage(), result.fallback(), result.suggestQuestions())
                : result;
    }

    /** spellJson 的 element 贴合内核：非内核元素改写为 elementA（JSON 其余字段不动）。 */
    private static String fitSpellJsonElement(String spellJson, com.qianxiang.cap.ClassCore core) {
        try {
            JsonObject obj = JsonParser.parseString(spellJson).getAsJsonObject();
            String element = obj.has("element") ? obj.get("element").getAsString() : "";
            if (core.hasElement(element)) return spellJson;
            obj.addProperty("element", core.elementA());
            return obj.toString();
        } catch (Throwable t) {
            return spellJson; // 解析不动就原样（防御）
        }
    }

    /** 注入 prompt 的职业行（自创魔法贴合职业①；无职业/非 magic 返回 ""）。 */
    static String classPromptLine(net.minecraft.server.level.ServerPlayer player, String type) {
        if (player == null || !"magic".equals(type)) return "";
        var data = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        var core = data.classCore();
        if (!core.isSet()) return "";
        String classId = data.classTemplateId().isEmpty() ? "自定义职业" : data.classTemplateId();
        return "【玩家职业】" + classId + "（内核元素：" + core.elementA() + " / " + core.elementB()
                + "）——自创魔法就是角色技能：请优先选择内核元素的材料与法术元素，"
                + "并贴合该职业的风格（形态：" + core.form() + "）。\n";
    }

    // ===================== 飞轮日志追踪（WQ-71，同 AI 线程读后即清） =====================

    /** 本次 ask 剔除的不存在材料（AI 编的名）；handler 在 ask 返回后读取写日志。 */
    private static final ThreadLocal<List<String>> LAST_DROPPED = ThreadLocal.withInitial(ArrayList::new);
    /** 本次 ask 落兜底的原因（"" = 未兜底）。 */
    private static final ThreadLocal<String> LAST_FALLBACK_REASON = ThreadLocal.withInitial(() -> "");

    /** 本次 ask 被剔除的材料名（同一 AI 线程有效）。 */
    public static List<String> lastDroppedMaterials() {
        return List.copyOf(LAST_DROPPED.get());
    }

    /** 本次 ask 的兜底原因（"" = AI 正常产出）。 */
    public static String lastFallbackReason() {
        return LAST_FALLBACK_REASON.get();
    }

    /**
     * 供网络处理器直接使用的便捷入口。
     */
    public static RecipeResult ask(AiRequestPayload payload) {
        return ask(payload, null);
    }

    /** 带玩家上下文的便捷入口（自创魔法贴合职业；两台 AI handler 用）。 */
    public static RecipeResult ask(AiRequestPayload payload,
                                   @javax.annotation.Nullable net.minecraft.server.level.ServerPlayer player) {
        return ask(payload.request(), payload.targetType(), payload.targetTier(),
                payload.currentMaterials(), payload.mode(), payload.allowedMaterials(), player);
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
                                            List<String> currentMaterials, String mode, boolean restricted) {
        String typeDesc = typeDescription(targetType);
        String tierDesc = tierDescription(targetTier);
        boolean isConfirm = "confirm".equalsIgnoreCase(mode);
        StringBuilder sb = new StringBuilder();
        sb.append("你是《千相》MC mod 的材料配方 AI。");
        if (isConfirm) {
            // confirm 模式的差异化信息必须放在最前：7B 级模型的注意力会被前面
            // 大段推荐向内容稀释，把「当前材料」埋在末尾会让它答非所问、
            // 变成又一次重新推荐。
            sb.append("\n【本次任务】评价玩家已经放入的这组材料，不是重新推荐。\n");
            sb.append("玩家当前已放入：")
              .append(currentMaterials == null || currentMaterials.isEmpty()
                      ? "（空）" : String.join("、", currentMaterials))
              .append("\n");
        }
        sb.append("玩家需求：").append(request == null ? "" : request).append("；");
        sb.append("目标强度档位：").append(tierDesc).append("（高挡位应优先选高 tier 材料，低挡位优先低 tier）。\n");
        // 【口语理解】玩家可能说得很含糊/口语：先复述理解再给方案，不确定给 questions 而非乱猜
        sb.append("【口语理解】玩家可能说得很口语、很含糊（如「想要猛一点的」「整把帅的」）。");
        sb.append("先在 summary 里写一句「我理解你想要：X」复述你的理解；需求有多种合理理解时，");
        sb.append("用 questions 给玩家 2~3 个澄清选项，不要硬猜。\n");
        sb.append("示例一：「想要猛一点的刀」→ summary：「我理解你想要：高攻击力的刀」，");
        sb.append("questions：[「要多猛？可以接受自伤代价吗」「要火焰还是纯粹的力量？」]\n");
        sb.append("示例二：「整把帅的」→ summary：「我理解你想要：外观帅气的武器」，");
        sb.append("questions：[「喜欢火焰特效还是暗影风格？」]\n");
        sb.append("目标产物类型：").append(typeDesc).append("；\n\n");
        if (restricted) {
            sb.append("【材料白名单】玩家已在「材料筛选」中勾选了愿意使用的材料，下列材料库就是白名单——");
            sb.append("每个方案的所有材料必须来自该白名单，严禁使用白名单外的任何材料。\n");
        } else {
            sb.append("【材料范围】玩家未限制材料范围，下列材料库中的全部材料均可使用。\n");
        }
        // 材料库是「全物品库」（概念推导兜底让 1391 件原版物品全部入库，约 114KB）。
        // 整段倾倒进 prompt ≈4 万 token —— Ollama 默认 num_ctx 只有 2048~4096，
        // 新版直接 500、旧版静默截断，而被截掉的恰恰是排在后面的玩家需求本身。
        // 改为检索式召回：按需求关键词 + 产物类型 + 目标档位挑 Top-48，分三组给出。
        java.util.Set<String> allowedSet = null;
        if (restricted) {
            allowedSet = new java.util.HashSet<>();
            for (MaterialLibrary.MaterialEntry e : lib) {
                allowedSet.add(e.registryName());
            }
        }
        var groups = MaterialRecall.recall(lib, request, targetType, tierFromString(targetTier), allowedSet,
                currentMaterials);
        sb.append("【候选材料】（已按你的需求筛选，registryName | 显示名 | 功能算子 | 档位）\n");
        sb.append("只能从下列材料中挑选，registryName 必须原样照抄（含命名空间、全小写）。\n");
        for (MaterialRecall.Group g : groups) {
            sb.append("· ").append(g.title()).append('\n');
            for (MaterialLibrary.MaterialEntry e : g.entries()) {
                sb.append("  - ").append(e.registryName())
                  .append(" | ").append(e.displayName())
                  .append(" | ").append(e.functions())
                  .append(" | ").append(e.tier())
                  .append('\n');
            }
        }
        if (!isConfirm) {
            // 【功能性需求指引】是「如何从零挑功能材料」的推荐向指引，对评价任务只是噪声（WQ-66③）。
            sb.append("\n【功能性需求指引】\n");
            sb.append("材料库已扩展功能算子：POISON(中毒)/FROST(霜冻)/LEVITATION(漂浮)/STRENGTH(力量)/");
            sb.append("NIGHT_VISION(夜视)/SPEED_BOOST(迅捷)/JUMP_BOOST(跳跃)/RESISTANCE(抗性)/FIRE_RESIST(抗火)/");
            sb.append("WATER_BREATH(水下呼吸)/REGENERATION(再生)/GROWTH(催熟)/AREA_HARVEST(广域采集)。\n");
            sb.append("玩家可能要求功能性物品，例如「反伤装甲」「夜视头盔」「能耕3×3地的锄头」「催熟5×5作物的水壶」。\n");
            // 各类型的选料优先级已在上文「目标产物类型」一行（typeDescription）写明，
            // 这里不再重复列举（WQ-66④：功能性指引与 typeDescription 去重）。
            appendFreeEffectSection(sb);
        }
        sb.append("\n【强度代价】\n");
        sb.append("游戏机制：强力产物必带代价。强度总分 >8 或正面效果 ≥3 种时，");
        sb.append("系统会自动给产物附加 1~2 个代价效果（frail 易碎=耐久消耗加倍 / heavy 沉重=移速下降 / ");
        sb.append("draining 耗力=攻击饥饿 / unstable 不稳=概率反噬自己 / cursed 诅咒=随机负面状态），");
        sb.append("强度越高代价越多。具体代价按主导效果映射：吸血→耗力、高攻→沉重、多效果→不稳/诅咒。\n");
        sb.append("因此：不要无脑堆强度，适度即好；若玩家明确要强力的武器/装备，尽管给强组合，");
        sb.append("但必须在 summary 中说明它将付出的代价，例如「这把武器很强但会带来迟缓」「吸血猛但吃着费力」。\n");
        // 自由法术段只在 magic 需求时插入（WQ-66②）：非 magic 需求这段近 1K 字符全是噪声。
        if ("magic".equals(safeType(targetType))) {
            appendFreeSpellSection(sb, targetType);
        }
        // EF 动画库段（≈3.5K 字符）只在玩家描述了攻击动作时才插入（WQ-66①）——
        // 「一把剑」这种普通需求背着整个动画库，是把 num_ctx 8192 撑爆的主因之一。
        if (mentionsAttackAction(request)) {
            appendMovesetSection(sb, targetType);
        }
        if (!isConfirm) {
            // 这三段都是「如何从零挑材料」的指引，对评价任务只是噪声，
            // 砍掉能显著提高 confirm 模式的命中率（也省 token）。
            appendBroadRequestSection(sb);
            appendEffectGlossarySection(sb);
        }
        sb.append("\n【规则】\n");
        if (isConfirm) {
            sb.append("当前玩家已放入材料：").append(String.join("、", currentMaterials)).append("。\n");
            sb.append("1. 评估这组材料能否实现玩家需求、是否符合目标类型与档位。\n");
            sb.append("2. 在 confirmMessage 中给出结论与修改建议：替换、添加、降级/升级。\n");
            sb.append("3. proposals 中放 1~2 个方案：第 1 个用已给材料做评价，第 2 个（可选）给出建议调整后的材料。\n");
            sb.append("4. 输出格式严格遵守文末【输出格式】段（confirmMessage 填结论与建议）。\n");
            sb.append("5. magic 类型且玩家在描述法术时，每个 proposal 可加可选 spell 字段（见【自由法术系统】）。\n");
            sb.append("6. 玩家在描述攻击动作时，每个 proposal 可加可选 moveset 字段（见【动作定制】）。\n");
        } else {
            sb.append("1. 只能从上述材料里挑，registryName 必须原样照抄（含 namespace 前缀，如 qianxiang: 或 minecraft:）。\n");
            sb.append("2. 每个方案选 2~").append(maxProposalMaterials()).append(" 个材料，覆盖玩家需求与目标类型约束；");
            sb.append("普通需求 2~4 个即可，只有「全部负面/全部增益」这类全集需求才用满上限（见【效果词典】）。\n");
            sb.append("3. 必须给出 1~3 个方案；其中至少一个方案的平均档位要与目标档位 ").append(tierDesc).append(" 匹配。\n");
            sb.append("4. 输出格式严格遵守文末【输出格式】段。\n");
            sb.append("5. magic 类型且玩家在描述法术时，每个 proposal 必须加 spell 字段（见【自由法术系统】）。\n");
            sb.append("6. 【反问】若玩家需求模糊（没说清想要的造型或效果，例如只说「一把武器」），");
            sb.append("在 JSON 顶层加 \"questions\" 字段：2~3 个极短的追问选项词（如 [\"巨剑\",\"匕首\",\"火焰\"]），");
            sb.append("每个词都必须能直接追加到玩家输入末尾来细化需求；需求已足够具体则省略该字段。\n");
            sb.append("7. 玩家描述了攻击动作时，每个 proposal 可加可选 moveset 字段（见【动作定制】）。\n");
        }
        // 【输出格式】独立成段放末尾（WQ-66⑥）：输出契约是模型最不能忘的东西，
        // 埋在规则列表中部容易被前面的说明书淹没。
        sb.append("\n【输出格式】\n");
        sb.append("只输出一个 JSON 对象，不要 Markdown 围栏、不要任何解释文字。骨架：\n");
        sb.append("{\"proposals\":[{\"materials\":[\"registryName1\",\"registryName2\"],\"summary\":\"一句话说明\"}],");
        sb.append("\"confirmMessage\":\"\",\"questions\":[\"可选追问词\"]}\n");
        sb.append("magic 需求时 proposal 另加 \"spell\" 字段；玩家描述攻击动作时另加 \"moveset\" 字段；");
        sb.append("无反问时省略 \"questions\"。\n");
        // few-shot（WQ-39④ / WQ-66⑥）：两条完整 输入→输出 示例，覆盖
        // 「模糊需求给 questions」与「magic 明确需求给 spell」两个最易错的形态。
        sb.append("\n【示例】\n");
        sb.append("输入：玩家需求「一把武器」（weapon/稀有，需求模糊）→ 输出：");
        sb.append("{\"proposals\":[],\"confirmMessage\":\"\",\"questions\":[\"巨剑\",\"匕首\",\"火焰\"]}\n");
        sb.append("输入：玩家需求「追踪火球法杖」（magic/稀有，需求明确）→ 输出：");
        sb.append("{\"proposals\":[{\"materials\":[\"minecraft:blaze_powder\",\"minecraft:redstone\"],");
        sb.append("\"summary\":\"追踪火球\",\"spell\":{\"element\":\"fire\",\"form\":\"projectile\",");
        sb.append("\"effect\":\"damage\",\"modifiers\":[\"homing\"],\"power\":2}}],\"confirmMessage\":\"\"}\n");
        String prompt = sb.toString();
        // prompt 长度日志（WQ-66⑤）：验收「日志确认 <8KB」此前无从执行——
        // 没有这条日志，瘦身是否达标只能靠猜。字符≈token（中文），对照 num_ctx=8192。
        Qianxiang.LOGGER.info("[Qianxiang] AI prompt 构建完成：mode={} type={} tier={}，{} 字符 / {} 字节",
                mode, targetType, targetTier, prompt.length(),
                prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return prompt;
    }

    /** 玩家输入是否描述了攻击动作（决定要不要插入 ≈3.5K 字符的 EF 动画库段）。 */
    private static boolean mentionsAttackAction(String request) {
        if (request == null) return false;
        String t = request.toLowerCase(Locale.ROOT);
        for (String k : ACTION_KEYWORDS) {
            if (t.contains(k)) return true;
        }
        return false;
    }

    /** 动作描述关键词（与【动作定制】段的语义匹配表同源）。 */
    private static final String[] ACTION_KEYWORDS = {
            "连斩", "连击", "连刺", "回旋", "突刺", "突进", "冲刺", "重劈", "重击",
            "跳劈", "空斩", "快攻", "双刀", "双持", "拔刀", "居合", "乱舞", "骑乘",
            "combo", "slash", "dash", "stab", "moveset"
    };

    /**
     * 测试入口：暴露 prompt 构造的最终产物（分段裁剪与长度是 AI 可用性的硬约束，
     * 断言拼好的整串而不是某个内部判据）。
     */
    public static String buildPromptForTest(String request, String targetType, String targetTier,
                                            List<String> currentMaterials, String mode) {
        return buildSystemPrompt(MaterialLibrary.snapshot(), request, safeType(targetType),
                safeTier(targetTier), currentMaterials == null ? List.of() : currentMaterials,
                mode == null ? "recommend" : mode, false);
    }

    /**
     * 自由法术系统指引：magic 类型需求可以要求元素×形式×效果×修饰的自由组合，不限魔法类型。
     * LLM 需把玩家的法术描述翻译成 spell JSON（契约：element/form/effect/modifiers/power）。
     * <p>只在 type==magic 时被调用（WQ-66②）——非 magic 需求这段全是噪声。
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
        // 强度上限由材料档位决定（ForgeComposer 会硬性夹取），提前告诉 AI 免得它开空头支票
        sb.append("- 重要：power 的实际上限由玩家放入的材料档位决定——");
        sb.append("普通材料最高 4、稀有 6、史诗 8、传奇 10。");
        sb.append("超出预算的数值会被系统夹回，请在预算内塑形（宁可靠 modifiers 与 form 做出特色）。\n");
        sb.append("示例：「追踪火球」={\"element\":\"fire\",\"form\":\"projectile\",\"effect\":\"damage\",\"modifiers\":[\"homing\"],\"power\":2}；");
        sb.append("「范围治疗」={\"element\":\"nature\",\"form\":\"aoe\",\"effect\":\"heal\",\"modifiers\":[],\"power\":2}；");
        sb.append("「雷链」={\"element\":\"lightning\",\"form\":\"projectile\",\"effect\":\"damage\",\"modifiers\":[\"chain\"],\"power\":3}。\n");
        sb.append("当前是 magic 需求：请从玩家描述中提炼上述组合，填进每个 proposal 的 spell 字段；");
        sb.append("材料需与元素呼应（火→烈焰粉/岩浆膏，冰→雪球/冰，雷→红石+金，自然→骨粉/种子，");
        sb.append("暗影→墨囊/回响，神圣→金胡萝卜/荧石，鲜血→蜘蛛眼/血根，末影→末影珍珠，奥术→青金石/裂隙精髓）。\n");
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
            sb.append(com.qianxiang.combat.AnimationLibrary.promptSummary()).append('\n');
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

    private static List<RecipeProposal> parseProposals(JsonObject obj, String targetType) {
        if (obj == null) return List.of();
        List<RecipeProposal> result = new ArrayList<>();
        JsonElement proposalsEl = fieldIgnoreCase(obj, "proposals");
        if (proposalsEl != null && proposalsEl.isJsonArray()) {
            for (JsonElement el : proposalsEl.getAsJsonArray()) {
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

    private static String extractConfirmMessage(JsonObject obj) {
        if (obj == null) return "";
        try {
            JsonElement el = fieldIgnoreCase(obj, "confirmMessage");
            if (el != null && el.isJsonPrimitive()) {
                return el.getAsString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 提取 AI 给的反问选项（顶层 {@code "questions"} 数组）。
     * 只保留非空、≤12 字的短词，最多 3 个；解析失败/无字段 → 空列表。永不抛异常。
     */
    private static List<String> extractQuestions(JsonObject obj) {
        if (obj == null) return List.of();
        try {
            JsonElement questionsEl = fieldIgnoreCase(obj, "questions");
            if (questionsEl == null || !questionsEl.isJsonArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonElement el : questionsEl.getAsJsonArray()) {
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
        JsonElement materialsEl = fieldIgnoreCase(obj, "materials");
        if (materialsEl != null && materialsEl.isJsonArray()) {
            for (JsonElement el : materialsEl.getAsJsonArray()) {
                if (!el.isJsonPrimitive()) continue;
                String name = el.getAsString();
                if (name == null || name.isBlank()) continue;
                // 必须存库里的规范 registryName：AI 常给「Minecraft:Iron_Ingot」这类
                // 大小写混合写法，原样存下去会一路传到客户端图标与放料的
                // ResourceLocation.tryParse —— 而 MC 的 id 不接受大写，全返 null。
                var entry = MaterialLibrary.find(normalizeName(name));
                if (entry.isPresent()) {
                    picks.add(entry.get().registryName());
                } else {
                    LAST_DROPPED.get().add(name); // 飞轮日志：dropped_materials（WQ-71）
                    Qianxiang.LOGGER.warn("[Qianxiang] AI 输出了不存在的材料，已剔除：{}", name);
                }
            }
        }

        JsonElement summaryEl = fieldIgnoreCase(obj, "summary");
        String summary = summaryEl != null && summaryEl.isJsonPrimitive()
                ? summaryEl.getAsString()
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

    /**
     * 从 LLM 原始回包里抽出可解析的 JSON 对象。
     * <p>
     * 实测必须处理的几类输入（原实现「首个 {{ 到末个 }}」对后四类全部失败）：
     * <ul>
     *   <li>markdown 围栏 <code>```json ... ```</code>；</li>
     *   <li>推理模型（deepseek-r1 等）的 {@code <think>...</think>} 块——
     *       块里常含花括号，会把起点定位到思考内容里；</li>
     *   <li>回包前置寒暄/末尾追加闲聊，寒暄里还可能带花括号（如「{想法}」）；</li>
     *   <li>顶层是数组（模型直接给了 proposals 列表）→ 包一层再返回。</li>
     * </ul>
     * 策略：先剥围栏与 think 块，然后——
     * <ol>
     *   <li><b>优先取包含 {@code "proposals"} 键的完整对象</b>（WQ-65）。
     *       旧实现无脑取第一个配平对象：对 {@code [{...},{...}]} 会命中数组内
     *       第一个方案对象直接返回，顶层数组的包装分支成了死代码，
     *       后果不是解析失败而是<b>静默只保留第一个方案</b>，其余方案丢失无日志；
     *       寒暄里的杂散花括号（不含 proposals 键）也会被跳过，不会污染结果。</li>
     *   <li>数组早于对象出现 → 模型直接给了方案列表，包一层 {@code {"proposals":...}}；</li>
     *   <li>否则退回第一个配平对象（兼容旧版单方案格式）；</li>
     *   <li>最后兜底顶层数组包装。</li>
     * </ol>
     */
    /**
     * 把 AI 原始回包解析成 JSON 对象；失败返回 null。
     * <p>解析失败此前完全静默——玩家只看到状态灯变黄和「（基础配方）」，
     * 服主翻日志也查不出模型到底吐了什么。这里补一条 WARN + 原文前 300 字。
     */
    private static JsonObject parseAiRoot(String aiOutput) {
        String json = extractJson(aiOutput);
        if (json == null) {
            Qianxiang.LOGGER.warn("[Qianxiang] AI 回包里找不到可解析的 JSON，落兜底。原文前 300 字：{}",
                    truncateForLog(aiOutput));
            return null;
        }
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            Qianxiang.LOGGER.warn("[Qianxiang] AI 回包 JSON 解析失败（{}），落兜底。原文前 300 字：{}",
                    e.getClass().getSimpleName(), truncateForLog(aiOutput));
            return null;
        }
    }

    private static String truncateForLog(String raw) {
        if (raw == null) return "(null)";
        String oneLine = raw.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    /** 测试入口：暴露 JSON 抽取逻辑（真实回包的容错是本类最脆弱的一环）。 */
    public static String extractJsonForTest(String raw) {
        return extractJson(raw);
    }

    /**
     * 测试入口：把 AI 原始回包一路解析成方案列表（抽取 → 解析 → 真实性校验的最终产出，
     * 数组/大小写/寒暄容错是否生效，看这个列表而不是中间字符串）。
     */
    public static List<RecipeProposal> parseProposalsForTest(String rawAiOutput, String targetType) {
        return parseProposals(parseAiRoot(rawAiOutput), targetType);
    }

    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String text = raw;
        // ① 剥 <think>...</think>（可能多段、可能未闭合）
        text = text.replaceAll("(?s)<think>.*?</think>", " ");
        int danglingThink = text.indexOf("<think>");
        if (danglingThink >= 0) {
            text = text.substring(0, danglingThink);
        }
        // ② 剥 markdown 围栏标记（内容保留）
        text = text.replaceAll("```[a-zA-Z]*", " ");

        // ③ 优先取包含 "proposals" 键的完整对象——跳过寒暄里的杂散花括号，
        //    也避免把顶层数组里的第一个方案对象误当整体（那会静默丢掉其余方案）。
        String withProposals = firstObjectWithKey(text, "proposals");
        if (withProposals != null) return withProposals;

        int braceIdx = text.indexOf('{');
        int bracketIdx = text.indexOf('[');
        if (bracketIdx >= 0 && (braceIdx < 0 || bracketIdx < braceIdx)) {
            // ④ 数组早于对象出现：模型直接给了方案列表，包一层 {"proposals":...}
            String arr = firstBalanced(text, '[', ']');
            if (arr != null) return "{\"proposals\":" + arr + "}";
        }

        // ⑤ 兼容旧版单方案格式（无 proposals 包装的裸对象）
        String obj = firstBalanced(text, '{', '}');
        if (obj != null) return obj;

        // ⑥ 兜底：顶层数组
        String arr = firstBalanced(text, '[', ']');
        if (arr != null) return "{\"proposals\":" + arr + "}";
        return null;
    }

    /**
     * 扫描文本中的配平对象，返回第一个<b>可解析且包含指定键</b>（大小写不敏感）的。
     * 找不到返回 null。逐个用 Gson 验证——寒暄里的「{想法}」这类杂散片段
     * 要么解析失败、要么没有该键，都会被跳过。
     */
    private static String firstObjectWithKey(String text, String key) {
        int from = 0;
        while (true) {
            int start = text.indexOf('{', from);
            if (start < 0) return null;
            String seg = balancedFrom(text, start, '{', '}');
            if (seg == null) return null;
            try {
                JsonObject obj = JsonParser.parseString(seg).getAsJsonObject();
                if (fieldIgnoreCase(obj, key) != null) return seg;
            } catch (Exception ignored) {
                // 杂散花括号片段：跳过继续找
            }
            from = start + 1;
        }
    }

    /**
     * 大小写不敏感地取对象字段（WQ-65）。模型常把字段写成 {@code "Proposals"}、
     * {@code "Materials"}——严格匹配会让整份合法回包静默落兜底。
     */
    private static JsonElement fieldIgnoreCase(JsonObject obj, String key) {
        if (obj == null || key == null) return null;
        JsonElement direct = obj.get(key);
        if (direct != null) return direct;
        for (java.util.Map.Entry<String, JsonElement> e : obj.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    /**
     * 括号配平地取出第一个完整片段（正确跳过字符串字面量与转义）。
     * 找不到完整片段返回 null。
     */
    private static String firstBalanced(String text, char open, char close) {
        int start = text.indexOf(open);
        if (start < 0) return null;
        return balancedFrom(text, start, open, close);
    }

    /**
     * 从指定位置（必须是 {@code open}）起做括号配平，取出完整片段。
     * 配平不到结尾返回 null。
     */
    private static String balancedFrom(String text, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 规范化 AI 给出的材料名。
     * <p>
     * <b>不再</b>给无冒号的名字强加 {@code qianxiang:} 前缀——小模型省略 namespace 是
     * 最高频的瑕疵，而「iron_ingot」被改成「qianxiang:iron_ingot」后永远匹配不上，
     * {@link MaterialLibrary#find} 的短名容错反而成了死代码，整条方案被清空静默落兜底。
     * 现在原样交给 find 做短名匹配，命中后取库里的规范 registryName。
     */
    static String normalizeName(String name) {
        if (name == null) return "";
        return name.trim();
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
