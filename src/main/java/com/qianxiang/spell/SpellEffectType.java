package com.qianxiang.spell;

/**
 * 千相法术的效果类型。
 * <p>
 * MVP 阶段只实现火球、治疗、护盾三种可施放效果；
 * SLOW 作为预留类型注册在法术表中，供后续扩展。
 */
public enum SpellEffectType {
    FIREBALL,
    HEAL,
    SHIELD,
    SLOW
}
