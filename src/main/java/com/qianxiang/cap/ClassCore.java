package com.qianxiang.cap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主职业内核：玩家自选 <b>2 个元素</b>（9 选 2）+ <b>1 个武器形态</b>（9 选 1）。
 * 内核内全力发挥、内核外可用但弱（倍率集中在 {@link ClassCoreHelper} 顶部）。
 * 未设定（{@link #EMPTY}，旧存档）= 全部 ×1.0，向后兼容。
 * <p>
 * 校验规则：两元素互不相同且在 {@link #ELEMENTS} 白名单、形态在 {@link #FORMS} 白名单；
 * 预设模板见 {@link #TEMPLATES}（8 个，一键选择，仍可自定义覆盖）。
 * </p>
 */
public record ClassCore(String elementA, String elementB, String form) {

    /** 未设定（旧存档/从未选择）：效果全中性。 */
    public static final ClassCore EMPTY = new ClassCore("", "", "");

    /** 元素白名单（与自由法术九元素同词表）。 */
    public static final List<String> ELEMENTS = List.of(
            "fire", "frost", "lightning", "nature", "shadow", "holy", "blood", "ender", "arcane");
    /** 武器形态白名单（九个推导形态 id，与 WeaponFormProfile 一致；
     *  另含 staff——预设「元素使」的内核形态是法杖（phase_staff form=staff），
     *  法杖手持近战按内核形态结算，与九个推导形态同口径）。 */
    public static final List<String> FORMS = List.of(
            "sword", "greatsword", "dagger", "katana", "spear", "axe", "hammer", "scythe", "mace",
            "staff");

    /** 预设模板：id → 内核（注册顺序即 UI 展示顺序）。 */
    public static final Map<String, ClassCore> TEMPLATES = new LinkedHashMap<>() {{
            put("battle_mage", new ClassCore("fire", "arcane", "sword"));
            put("elementalist", new ClassCore("fire", "frost", "staff"));
            put("blood_witch", new ClassCore("blood", "shadow", "dagger"));
            put("cleric", new ClassCore("holy", "nature", "mace"));
            put("shadow_dancer", new ClassCore("shadow", "ender", "katana"));
            put("berserker", new ClassCore("fire", "lightning", "greatsword"));
            put("ranger", new ClassCore("frost", "lightning", "spear"));
            put("artificer", new ClassCore("nature", "arcane", "hammer"));
        }};

    public static final Codec<ClassCore> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("element_a", "").forGetter(ClassCore::elementA),
            Codec.STRING.optionalFieldOf("element_b", "").forGetter(ClassCore::elementB),
            Codec.STRING.optionalFieldOf("form", "").forGetter(ClassCore::form)
    ).apply(instance, ClassCore::new));

    /** 已设定（三字段齐全）。 */
    public boolean isSet() {
        return !elementA.isEmpty() && !elementB.isEmpty() && !form.isEmpty();
    }

    /** 元素是否在内核内。 */
    public boolean hasElement(String element) {
        return isSet() && (elementA.equals(element) || elementB.equals(element));
    }

    /** 形态是否就是内核形态（空串形态恒为「非内核」）。 */
    public boolean hasForm(String formId) {
        return isSet() && !formId.isEmpty() && form.equals(formId);
    }

    /** 合法性：两元素互不相同且在白名单、形态在白名单。 */
    public boolean valid() {
        return isSet()
                && !elementA.equals(elementB)
                && ELEMENTS.contains(elementA) && ELEMENTS.contains(elementB)
                && FORMS.contains(form);
    }

    /** 预设模板 id → 内核；未知 id 返回 null。 */
    public static ClassCore template(String templateId) {
        return templateId == null ? null : TEMPLATES.get(templateId);
    }
}
