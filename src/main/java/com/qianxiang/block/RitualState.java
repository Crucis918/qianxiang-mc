package com.qianxiang.block;

/**
 * 合成仪式状态机：NONE（常态）→ FLYING（材料飞入）→ FORMING（创作成型）→ DONE（产物待取）。
 * <p>
 * FLYING/FORMING 时长见 {@link RitualLogic#FLYING_TICKS}/{@link RitualLogic#FORMING_TICKS}；
 * DONE 时产物写入 displayResult，空手右键拾取后回 NONE。
 * </p>
 */
public enum RitualState {
    NONE,
    FLYING,
    FORMING,
    DONE;

    /** 仪式进行中（投料/取回/再次触发一律拒绝的判据）。 */
    public boolean active() {
        return this != NONE;
    }
}
