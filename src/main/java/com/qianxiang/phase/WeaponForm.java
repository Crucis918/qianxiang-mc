package com.qianxiang.phase;

import java.util.Locale;

/**
 * 武器形态（WeaponForm）——外观纹理 / Epic Fight 动作底座 / 判定盒的统一事实源。
 * <p>
 * 形态在锻造时由 {@link WeaponFormProfile#deriveForm} 一次推导并写入
 * {@link ComposedAttributes.AppearanceData#form}（组件持久化），下游三处
 * （DynamicWeaponTexture.shapeFor / QianxiangEFCompat.classify / MovesetEditorScreen）
 * 统一按形走，不再各自推导。非武器产物不写形态（空串），法杖/法术书写物品固有形态。
 * </p>
 */
public enum WeaponForm {
    SWORD,
    GREATSWORD,
    DAGGER,
    KATANA,
    SPEAR,
    AXE,
    HAMMER,
    SCYTHE,
    MACE,
    /** 物品固有形态：相杖（非推导，phase_staff 恒为此形）。 */
    STAFF,
    /** 物品固有形态：法术书（非推导，spell_book 恒为此形）。 */
    BOOK;

    /** 组件/映射表用的字符串 id（小写枚举名；空串 = 无形态）。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 按 id 反查；空串/未知返回 null（旧存档无 form 字段走回退路径）。 */
    public static WeaponForm byId(String id) {
        if (id == null || id.isEmpty()) return null;
        for (WeaponForm form : values()) {
            if (form.id().equals(id)) return form;
        }
        return null;
    }
}
