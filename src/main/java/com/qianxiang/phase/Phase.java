package com.qianxiang.phase;

import com.mojang.serialization.Codec;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 相之九相 + 几个衍生相。材料可持一或多种相。
 * 九残界各由一种主导之相主导（世界观§三）。
 */
public enum Phase {
    ORDER("秩序"),       // 钢铁王座
    LIFE("生命"),        // 万象森罗
    TIME("时间"),        // 龙脊沙海
    KNOWLEDGE("智识"),   // 永夜极光
    ABYSS("沉潜"),       // 深渊之喉
    TRANSCEND("超脱"),   // 云端庭院
    CONFLICT("冲突"),    // 无尽战场
    NEUTRAL("中和"),     // 灵薄回廊
    CHAOS("混沌"),       // 裂隙之境
    // 衍生相
    FIRE("火"),
    FROST("冰"),
    SHADOW("暗"),
    LIGHT("圣");

    public static final Codec<Phase> CODEC = Codec.STRING.xmap(Phase::valueOf, Phase::name);
    public static final Codec<Set<Phase>> SET_CODEC = Codec.list(CODEC).xmap(
            list -> { var s = EnumSet.noneOf(Phase.class); s.addAll(list); return Collections.unmodifiableSet(s); },
            List::copyOf
    );

    private final String cn;
    Phase(String cn) { this.cn = cn; }
    public String cn() { return cn; }
}
