package com.qianxiang.phase;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一个材料的相之数据：功能算子（能干什么）+ 强度档 + 相性。
 * <p>
 * 这是「材料即零件」的数据化身——AI 检索材料、合成判定、强度计算，全靠它。
 * 存于物品的 {@code qianxiang:phase_data} DataComponent。
 */
public record PhaseData(Set<PhaseFunction> functions, PhaseTier tier, Set<Phase> phases) {

    public static final Codec<PhaseData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PhaseFunction.SET_CODEC.fieldOf("functions").forGetter(PhaseData::functions),
            PhaseTier.CODEC.fieldOf("tier").forGetter(PhaseData::tier),
            Phase.SET_CODEC.fieldOf("phases").forGetter(PhaseData::phases)
    ).apply(instance, PhaseData::new));

    /** 便捷构造：给定档位、相性、若干功能算子。 */
    public static PhaseData of(PhaseTier tier, Set<Phase> phases, PhaseFunction... functions) {
        var set = EnumSet.noneOf(PhaseFunction.class);
        Collections.addAll(set, functions);
        return new PhaseData(Collections.unmodifiableSet(set), tier, phases);
    }
}
