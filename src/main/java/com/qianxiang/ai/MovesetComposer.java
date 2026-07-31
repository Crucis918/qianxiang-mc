package com.qianxiang.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qianxiang.combat.AnimationLibrary;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 自然语言动作编排：「我要一个啥样的动作」→ 动作集 JSON（category + combos + collider）。
 * <p>
 * 双路径：
 * </p>
 * <ul>
 *   <li><b>AI 在线</b>：prompt 给动画库清单（{@link AnimationLibrary#promptSummary}）+
 *       语义指引（连/快=低 power 高 speed，重/劈/崩=高 power 低 speed，突/冲=DASH，
 *       扫/旋=连击横扫，伤害时机=动画攻击帧）+ 2 个 few-shot；
 *       输出走与提案同款的 {@link PhaseAIRecipeService#parseMovesetJson} 真实性校验
 *       （库外动画剔除、category 白名单、≤4 段去重）。</li>
 *   <li><b>AI 离线兜底</b>：{@link #fallbackSegments} 关键词检索拼装——
 *       数词定段数、语义映射表选动画，零 HTTP 也永远出结果。</li>
 * </ul>
 */
public final class MovesetComposer {

    private MovesetComposer() {}

    /** 段数上限（编辑器/服务端应用口径）。 */
    private static final int MAX_SEGMENTS = 6;

    /**
     * 编排入口：AI 在线走 AI（校验后），否则关键词兜底；永远非 null（除非需求为空）。
     */
    @Nullable
    public static String composeMoveset(String request, @Nullable ServerPlayer player) {
        String want = request == null ? "" : request.trim();
        if (want.isEmpty()) return null;
        try {
            var ai = AIGateway.chat(want, buildPrompt(want));
            if (ai.isPresent()) {
                String validated = validate(ai.get());
                if (validated != null) return validated;
            }
        } catch (Throwable t) {
            // 网关/解析任何意外都落兜底（兜底零依赖）
        }
        return fallback(want);
    }

    // ============================ AI 路径 ============================

    private static String buildPrompt(String request) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是《千相》MC mod 的战斗动作编排师。玩家会用自然语言描述想要的连招，")
                .append("你要从动画库里挑动画按出招顺序拼成连段。\n");
        sb.append("【编排语义】combos 按出招顺序；每段的 speed（1慢~3快）与 power（1轻~3重）")
                .append("决定节奏——连/快/速=低 power 高 speed 的密集段，重/劈/崩/猛=高 power 低 speed 的重段，")
                .append("突/冲=dash 突进段，扫/旋=连击横扫段，跳/空=airslash 跳劈段，拔=sheath 拔刀段；")
                .append("伤害出现时机=各动画的攻击帧，重段放收尾最有力。段数 2~4 段，按玩家说的次数来。\n");
        sb.append("【输出格式】只输出 JSON 对象：{\"category\":\"sword\",\"combos\":[\"动画id\",...],\"collider\":\"sword\"}；")
                .append("动画 id 必须从下列库中原样照抄（含 epicfight:biped/combat/ 前缀），禁止编造。\n");
        sb.append("【示例一】玩家说「三段快斩接一次重劈」→ ")
                .append("{\"category\":\"sword\",\"combos\":[\"epicfight:biped/combat/dagger_auto1\",")
                .append("\"epicfight:biped/combat/dagger_auto2\",\"epicfight:biped/combat/dagger_auto3\",")
                .append("\"epicfight:biped/combat/greatsword_auto1\"],\"collider\":\"sword\"}\n");
        sb.append("【示例二】玩家说「突进然后横扫」→ ")
                .append("{\"category\":\"sword\",\"combos\":[\"epicfight:biped/combat/sword_dash\",")
                .append("\"epicfight:biped/combat/sword_auto1\",\"epicfight:biped/combat/sword_auto2\"],\"collider\":\"sword\"}\n");
        sb.append("【玩家需求】").append(request).append('\n');
        sb.append(AnimationLibrary.promptSummary());
        return sb.toString();
    }

    /** AI 输出校验（与提案同款 parseMovesetJson；库外动画/非法 category 直接废）。 */
    @Nullable
    public static String validate(String rawAiOutput) {
        JsonObject obj = extractObject(rawAiOutput);
        if (obj == null) return null;
        // 兼容两种形态：直接给 moveset 对象，或包一层 {"moveset":{...}}
        JsonObject proposal = obj.has("moveset") && obj.get("moveset").isJsonObject()
                ? obj : wrapMoveset(obj);
        if (proposal == null) return null;
        String ms = PhaseAIRecipeService.parseMovesetJson(proposal);
        return ms.isEmpty() ? null : ms;
    }

    @Nullable
    private static JsonObject wrapMoveset(JsonObject moveset) {
        if (!moveset.has("combos")) return null;
        JsonObject wrapper = new JsonObject();
        wrapper.add("moveset", moveset);
        return wrapper;
    }

    /** 从 AI 回包里抠第一个完整 JSON 对象（剥围栏/寒暄；失败返回 null）。 */
    @Nullable
    private static JsonObject extractObject(String raw) {
        if (raw == null) return null;
        String text = raw.replace("```json", "").replace("```", "").trim();
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        try {
                            return JsonParser.parseString(text.substring(start, i + 1)).getAsJsonObject();
                        } catch (Throwable t) {
                            break;
                        }
                    }
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    // ============================ 关键词兜底 ============================

    /**
     * 关键词兜底拼装（纯函数，GameTest 直测）：
     * 按需求里关键词的出现顺序选段——突/冲→DASH，连/快/速/疾→FAST（speed 3），
     * 重/劈/崩/猛/砸→HEAVY（power 3），扫/旋→COMBO，跳/空→AIRSLASH，拔→SHEATH；
     * 数词（一/二/两/三/四/五/六 + 阿拉伯数字）定段数；全部落空 → 剑连击三段。
     */
    public static List<AnimationLibrary.AnimInfo> fallbackSegments(String request) {
        String want = request == null ? "" : request;
        List<AnimationLibrary.AnimInfo> picked = new ArrayList<>();

        // 按关键词在原文出现的先后排序匹配，保证「突进然后横扫」顺序正确
        record Rule(String[] words, AnimationLibrary.AnimType type, int max) {}
        List<Rule> rules = List.of(
                new Rule(new String[]{"突", "冲", "猛进"}, AnimationLibrary.AnimType.DASH, 1),
                new Rule(new String[]{"快", "连", "速", "疾"}, AnimationLibrary.AnimType.FAST, 3),
                new Rule(new String[]{"重", "劈", "崩", "猛", "砸"}, AnimationLibrary.AnimType.HEAVY, 2),
                new Rule(new String[]{"扫", "旋"}, AnimationLibrary.AnimType.COMBO, 3),
                new Rule(new String[]{"跳", "空"}, AnimationLibrary.AnimType.AIRSLASH, 1),
                new Rule(new String[]{"拔"}, AnimationLibrary.AnimType.SHEATH, 1)
        );
        int target = countWord(want);
        for (Rule rule : rules) {
            int at = firstIndexOfAny(want, rule.words());
            if (at < 0) continue;
            int take = rule.max();
            // 有数词时按数词给主类型多分配（「三连快斩」→ 快 ×3）
            if (target > 0 && picked.isEmpty()) take = Math.min(rule.max() == 1 ? rule.max() : target, target);
            List<AnimationLibrary.AnimInfo> list = AnimationLibrary.byType(rule.type());
            for (int i = 0; i < take && i < list.size() && picked.size() < MAX_SEGMENTS; i++) {
                if (!picked.contains(list.get(i))) picked.add(list.get(i));
            }
        }
        if (picked.isEmpty()) {
            // 无关键词：默认剑连击三段（兜底永远出结果）
            List<AnimationLibrary.AnimInfo> combos = AnimationLibrary.byType(AnimationLibrary.AnimType.COMBO);
            for (int i = 0; i < 3 && i < combos.size(); i++) picked.add(combos.get(i));
        }
        if (picked.size() > MAX_SEGMENTS) {
            picked = new ArrayList<>(picked.subList(0, MAX_SEGMENTS));
        }
        return picked;
    }

    /** 兜底出口：拼装结果 → 动作集 JSON（category/collider 取首段武器类别）。 */
    public static String fallback(String request) {
        List<AnimationLibrary.AnimInfo> segs = fallbackSegments(request);
        if (segs.isEmpty()) return null;
        JsonObject out = new JsonObject();
        out.addProperty("category", segs.get(0).weapon());
        JsonArray combos = new JsonArray();
        for (AnimationLibrary.AnimInfo info : segs) combos.add(info.id().toString());
        out.add("combos", combos);
        out.addProperty("collider", segs.get(0).weapon());
        return out.toString();
    }

    /** 数词 → 段数（0 = 未提及）：一/二/两/三/四/五/六 与 1~6 阿拉伯数字。 */
    private static int countWord(String want) {
        String[][] words = {{"六", "6"}, {"五", "5"}, {"四", "4"}, {"三", "3"},
                {"二", "两", "2"}, {"一", "1"}};
        for (int n = words.length; n >= 1; n--) {
            for (String w : words[n - 1]) {
                if (want.contains(w)) return n;
            }
        }
        return 0;
    }

    private static int firstIndexOfAny(String want, String... words) {
        int best = -1;
        for (String w : words) {
            int at = want.indexOf(w);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }
}
