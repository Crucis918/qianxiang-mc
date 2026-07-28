package com.qianxiang.cap;

/** 熟练度三轨：战斗 / 法术 / 技艺。 */
public enum ProficiencyTrack {
    COMBAT,
    ARCANE,
    CRAFT;

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
