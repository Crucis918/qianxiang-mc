package com.qianxiang.phase;

import java.util.List;
import java.util.Optional;

/**
 * 效果词典：「全部负面状态」「全部增益状态」的权威文本库。
 * <p>
 * 解决 AI（以及玩家）对「全部负面/全部增益」这类宽泛说法理解不一致的问题——
 * 词典把这两个说法固定为下文两个不可变集合：
 * <ul>
 *   <li>{@link #DEBUFFS} 负面状态：灼烧/冻伤/中毒/凋零/缓慢/饥饿/虚弱/寄生/反胃/失明/漂浮；</li>
 *   <li>{@link #BUFFS} 增益状态：抗火/力量/迅捷/跳跃提升/缓降/生命恢复/抗性/水下呼吸/
 *       夜视/伤害吸收/生命提升/幸运/急迫。</li>
 * </ul>
 * 消费方：
 * <ul>
 *   <li>AI prompt（{@code PhaseAIRecipeService}）嵌入 {@link #promptSummary(int)} 的词典摘要，
 *       让 LLM 看到「效果 → 典型材料」对照；</li>
 *   <li>关键词回退（{@code FallbackRecipes}）按集合顺序取典型材料凑「全集」组合；</li>
 *   <li>说明书效果表页与材料性质卡用词典的中文名与分类上色（负面红/增益绿）。</li>
 * </ul>
 * registryId 说明：大部分是原版 MobEffect 的 registry path（poison/wither/…）；
 * 「灼烧 ignite」「冻伤 frost」是本 mod 的功能算子概念（无对应 MobEffect），
 * 沿用了 {@code qianxiang.phasefn.ignite/frost} 的 id。
 * <p>
 * 纯数据类，无注册表访问，服务端/客户端/AI 全链路可安全加载。
 */
public final class EffectGlossary {

    private EffectGlossary() {}

    /** 效果类别：负面（给敌人的 debuff）或增益（给自己的 buff）。 */
    public enum Category {
        DEBUFF, BUFF;

        public boolean isDebuff() { return this == DEBUFF; }
    }

    /**
     * 一条效果词条。
     *
     * @param registryId       效果 id（MobEffect registry path；ignite/frost 为本 mod 算子概念 id）
     * @param cnName           中文名（喂 AI prompt 与兜底显示用；界面显示优先走官方译名，见 {@link #displayKey}）
     * @param category         负面或增益
     * @param typicalMaterials 典型材料 registryName，按优先级排序（回退配方按序取第一个存在于材料库的）
     * @param description      一句话描述
     */
    public record EffectInfo(String registryId, String cnName, Category category,
                             List<String> typicalMaterials, String description) {

        public boolean isDebuff() { return category.isDebuff(); }

        /**
         * 界面显示名的本地化键：原版 MobEffect 复用官方译名 {@code effect.minecraft.<id>}；
         * 本 mod 概念（ignite/frost）走 {@code qianxiang.phasefn.<id>}。
         */
        public String displayKey() {
            return switch (registryId) {
                case "ignite", "frost" -> "qianxiang.phasefn." + registryId;
                default -> "effect.minecraft." + registryId;
            };
        }
    }

    // ===================== 负面状态（顺序 = 回退配方优先级） =====================

    public static final List<EffectInfo> DEBUFFS = List.of(
            new EffectInfo("ignite", "灼烧", Category.DEBUFF,
                    List.of("qianxiang:ember_crystal", "minecraft:blaze_powder", "minecraft:magma_cream"),
                    "攻击点燃目标，持续受到火焰伤害"),
            new EffectInfo("frost", "冻伤", Category.DEBUFF,
                    List.of("minecraft:snowball", "minecraft:ice", "minecraft:blue_ice"),
                    "攻击使目标如陷细雪般冻结掉血"),
            new EffectInfo("poison", "中毒", Category.DEBUFF,
                    List.of("minecraft:spider_eye", "minecraft:pufferfish",
                            "minecraft:poisonous_potato", "qianxiang:venom_gland"),
                    "目标持续中毒掉血"),
            new EffectInfo("wither", "凋零", Category.DEBUFF,
                    List.of("minecraft:wither_rose"),
                    "目标受到凋零侵蚀，持续掉血"),
            new EffectInfo("slowness", "缓慢", Category.DEBUFF,
                    List.of("qianxiang:shadowhide_patch", "minecraft:sand"),
                    "目标移动速度下降"),
            new EffectInfo("hunger", "饥饿", Category.DEBUFF,
                    List.of("minecraft:rotten_flesh"),
                    "目标饥饿值快速消耗"),
            new EffectInfo("weakness", "虚弱", Category.DEBUFF,
                    List.of("minecraft:fermented_spider_eye"),
                    "目标攻击力下降"),
            new EffectInfo("infested", "寄生", Category.DEBUFF,
                    List.of("minecraft:infested_stone", "minecraft:infested_cobblestone"),
                    "目标受伤时概率唤出蠹虫"),
            new EffectInfo("nausea", "反胃", Category.DEBUFF,
                    List.of("minecraft:pufferfish"),
                    "目标视野扭曲眩晕"),
            new EffectInfo("blindness", "失明", Category.DEBUFF,
                    List.of("minecraft:ink_sac"),
                    "目标视野陷入黑暗"),
            new EffectInfo("levitation", "漂浮", Category.DEBUFF,
                    List.of("minecraft:shulker_shell", "minecraft:phantom_membrane"),
                    "目标不受控地浮空")
    );

    // ===================== 增益状态（顺序 = 回退配方优先级） =====================

    public static final List<EffectInfo> BUFFS = List.of(
            new EffectInfo("fire_resistance", "抗火", Category.BUFF,
                    List.of("minecraft:magma_cream"),
                    "免疫火焰与岩浆伤害"),
            new EffectInfo("strength", "力量", Category.BUFF,
                    List.of("minecraft:blaze_powder", "qianxiang:thunder_stone"),
                    "近战攻击力提升"),
            new EffectInfo("speed", "迅捷", Category.BUFF,
                    List.of("minecraft:sugar"),
                    "移动速度提升"),
            new EffectInfo("jump_boost", "跳跃提升", Category.BUFF,
                    List.of("minecraft:rabbit_foot"),
                    "跳得更高"),
            new EffectInfo("slow_falling", "缓降", Category.BUFF,
                    List.of("minecraft:phantom_membrane"),
                    "缓缓降落，免疫摔落伤害"),
            new EffectInfo("regeneration", "生命恢复", Category.BUFF,
                    List.of("minecraft:ghast_tear", "qianxiang:holy_shard"),
                    "生命持续恢复"),
            new EffectInfo("resistance", "抗性", Category.BUFF,
                    List.of("minecraft:turtle_scute", "minecraft:netherite_ingot"),
                    "受到的伤害降低"),
            new EffectInfo("water_breathing", "水下呼吸", Category.BUFF,
                    List.of("minecraft:pufferfish", "minecraft:prismarine_crystals"),
                    "水下不再消耗氧气"),
            new EffectInfo("night_vision", "夜视", Category.BUFF,
                    List.of("minecraft:golden_carrot"),
                    "黑暗中视物如白昼"),
            new EffectInfo("absorption", "伤害吸收", Category.BUFF,
                    List.of("minecraft:golden_apple"),
                    "获得可抵消伤害的额外吸收心"),
            new EffectInfo("health_boost", "生命提升", Category.BUFF,
                    List.of("minecraft:enchanted_golden_apple"),
                    "最大生命值提升"),
            new EffectInfo("luck", "幸运", Category.BUFF,
                    List.of("minecraft:totem_of_undying"),
                    "掉落与战利品运气提升"),
            new EffectInfo("haste", "急迫", Category.BUFF,
                    List.of(), // 暂无典型材料
                    "挖掘与攻击速度提升（暂无典型材料）")
    );

    // ===================== 查询 =====================

    /** 按效果 id（registry path，可带 namespace 前缀）查词条。 */
    public static Optional<EffectInfo> byId(String registryId) {
        if (registryId == null || registryId.isBlank()) return Optional.empty();
        String path = registryId.trim();
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);
        for (EffectInfo info : DEBUFFS) {
            if (info.registryId().equals(path)) return Optional.of(info);
        }
        for (EffectInfo info : BUFFS) {
            if (info.registryId().equals(path)) return Optional.of(info);
        }
        return Optional.empty();
    }

    /** 该效果 id 是否词典收录的负面状态。 */
    public static boolean isDebuff(String registryId) {
        return byId(registryId).map(EffectInfo::isDebuff).orElse(false);
    }

    /** 该效果 id 是否词典收录的增益状态。 */
    public static boolean isBuff(String registryId) {
        return byId(registryId).map(i -> !i.isDebuff()).orElse(false);
    }

    // ===================== AI prompt 摘要 =====================

    /**
     * 词典摘要（喂 LLM 的 system prompt 段）：明确「全部负面/全部增益」的语义，
     * 并列出每条效果的中文名、id、一句话描述与典型材料 registryName（LLM 照抄用）。
     *
     * @param maxMaterials 单个方案允许的最大材料数（随材料槽位数变化，由调用方给）
     */
    public static String promptSummary(int maxMaterials) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n【效果词典】\n");
        sb.append("「全部负面状态 / 所有负面 / debuff 全要 / 诅咒」= 给武器施加下列全部负面效果的组合：");
        appendNames(sb, DEBUFFS);
        sb.append("。按词典顺序尽量选齐每个效果的典型材料（最多 ").append(maxMaterials)
          .append(" 个 + BASE_* 基底），材料不够时按顺序优先前面的：\n");
        for (EffectInfo info : DEBUFFS) {
            appendEntry(sb, info);
        }
        sb.append("「全部增益状态 / 所有正面 / buff 全要」= 给武器/防具提供下列全部增益效果的组合：");
        appendNames(sb, BUFFS);
        sb.append("。同样按词典顺序选典型材料（最多 ").append(maxMaterials)
          .append(" 个 + BASE_* 基底）：\n");
        for (EffectInfo info : BUFFS) {
            appendEntry(sb, info);
        }
        return sb.toString();
    }

    private static void appendNames(StringBuilder sb, List<EffectInfo> list) {
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("/");
            sb.append(list.get(i).cnName());
        }
    }

    private static void appendEntry(StringBuilder sb, EffectInfo info) {
        sb.append("- ").append(info.cnName())
          .append("(").append(info.registryId()).append(")：")
          .append(info.description()).append("；典型材料：");
        if (info.typicalMaterials().isEmpty()) {
            sb.append("暂无");
        } else {
            sb.append(String.join("、", info.typicalMaterials()));
        }
        sb.append('\n');
    }
}
