package com.qianxiang.phase;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 材料的功能算子——"这材料能干什么"。
 * 这是 AI 检索材料、判定合成的核心依据。
 * 例：火蜥蜴腺体=IGNITE（能喷火），血根=LIFESTEAL（能吸血），铁锭=BASE_METAL（武器骨架）。
 * <p>
 * 每个算子都带有 {@link Usage} 用途标签，告诉玩家/AI 它能参与打造哪种产物：
 * 武器、魔法、装备/护甲、工具。
 */
public enum PhaseFunction {
    // 基底（决定产物类型）
    BASE_METAL(Usage.WEAPON, Usage.TOOL),
    BASE_WOOD(Usage.WEAPON, Usage.MAGIC, Usage.TOOL),
    BASE_BONE(Usage.WEAPON),
    BASE_HIDE(Usage.ARMOR),
    // 攻击/效果
    IGNITE(Usage.WEAPON, Usage.MAGIC),
    LIFESTEAL(Usage.WEAPON),
    EDGE(Usage.WEAPON),
    DEFENSE(Usage.ARMOR),
    HEAL(Usage.MAGIC, Usage.ARMOR, Usage.TOOL),
    SLOW(Usage.WEAPON, Usage.MAGIC),
    REFLECT(Usage.ARMOR),
    MANA(Usage.MAGIC),
    // 扩展效果算子（武器/装备/工具新功能）
    POISON(Usage.WEAPON, Usage.MAGIC),
    FROST(Usage.WEAPON, Usage.MAGIC),
    LEVITATION(Usage.WEAPON, Usage.MAGIC),
    STRENGTH(Usage.WEAPON),
    NIGHT_VISION(Usage.ARMOR),
    SPEED_BOOST(Usage.ARMOR, Usage.TOOL),
    JUMP_BOOST(Usage.ARMOR),
    RESISTANCE(Usage.ARMOR),
    FIRE_RESIST(Usage.ARMOR),
    WATER_BREATH(Usage.ARMOR),
    REGENERATION(Usage.ARMOR, Usage.MAGIC),
    GROWTH(Usage.TOOL, Usage.MAGIC),
    AREA_HARVEST(Usage.TOOL),
    // 反转器（逆相之核）：机制开关而非数值料——在场时所有概念倒转含义
    // （防具效果由「接触反伤」变「抗性/免疫」；武器伤害型效果极性反转）。
    // AttributeScheme 不给它任何数值贡献，仅由 ForgeComposer 检测后写反转标志。
    REVERSE(Usage.WEAPON, Usage.MAGIC, Usage.ARMOR, Usage.TOOL);

    public static final Codec<PhaseFunction> CODEC = Codec.STRING.xmap(PhaseFunction::valueOf, PhaseFunction::name);
    public static final Codec<Set<PhaseFunction>> SET_CODEC = Codec.list(CODEC).xmap(
            list -> { var s = EnumSet.noneOf(PhaseFunction.class); s.addAll(list); return Collections.unmodifiableSet(s); },
            List::copyOf
    );

    private final Set<Usage> usages;

    PhaseFunction(Usage... usages) {
        var set = EnumSet.noneOf(Usage.class);
        Collections.addAll(set, usages);
        this.usages = Collections.unmodifiableSet(set);
    }

    /** 该算子可参与的产物用途集合。 */
    public Set<Usage> usages() {
        return usages;
    }

    /** 简短名称组件。 */
    public Component display() {
        return Component.translatable("qianxiang.phasefn." + name().toLowerCase());
    }

    /** 用途描述组件："可做：武器/魔法" 形式。 */
    public Component usageComponent() {
        if (usages.isEmpty()) return Component.empty();
        MutableComponent c = Component.translatable("qianxiang.phasefn.usages.prefix");
        boolean first = true;
        for (Usage u : usages) {
            if (!first) c.append("/");
            c.append(Component.translatable("qianxiang.phasefn.usages." + u.key));
            first = false;
        }
        return c;
    }

    /** 产物用途枚举。 */
    public enum Usage {
        WEAPON("weapon"),
        MAGIC("magic"),
        ARMOR("armor"),
        TOOL("tool");

        private final String key;
        Usage(String key) { this.key = key; }
        public String key() { return key; }
    }
}
