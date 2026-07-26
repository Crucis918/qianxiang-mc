package com.qianxiang.phase;

import com.mojang.serialization.Codec;

/**
 * 强度档 = 稀有度（对应纪元层）。
 * 同一功能可用不同档位材料实现 → AI 换材料 = 调整最终强度（用户设计的核心）。
 * 想更强 → AI 换更烈材料 → 玩家去更危险的地方采。
 */
public enum PhaseTier {
    COMMON,    // 普通（散相纪日常之相）
    RARE,      // 稀有
    EPIC,      // 史诗（含怨念/祝福这种相之债/谊）
    LEGENDARY; // 传奇（浑相纪残片，唯裂隙偶出）

    public static final Codec<PhaseTier> CODEC = Codec.STRING.xmap(PhaseTier::valueOf, PhaseTier::name);
}
