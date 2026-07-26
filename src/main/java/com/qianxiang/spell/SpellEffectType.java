package com.qianxiang.spell;

/**
 * 千相法术的效果类型。
 *
 * @deprecated 旧法术系统的类型枚举，仅为旧存档兼容保留（见 {@link Spell} 的封存说明）。
 *             自由法术的元素/形式/效果枚举在 {@link CustomSpell} 白名单中。
 */
@Deprecated
public enum SpellEffectType {
    FIREBALL,
    HEAL,
    SHIELD,
    SLOW
}
