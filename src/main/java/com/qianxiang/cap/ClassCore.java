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
    /** 形态白名单（推导形态 9 + 书本/法杖两物品固有形态——24 职业里 book/staff 形态职业
     *  （机械师/召唤师/魔道学者/死灵术士/气功师/元素法师/牧师）的内核形态是它们）。 */
    public static final List<String> FORMS = List.of(
            "sword", "greatsword", "dagger", "katana", "spear", "axe", "hammer", "scythe", "mace",
            "staff", "book");

    /** 职业系列（6 系，UI 分组与 lang 键用）。 */
    public static final List<String> SERIES = List.of(
            "swordsman", "gunner", "fighter", "mage", "dark", "priest");

    /** 职业模板：id + 系列 + 内核（招牌被动按 id 在 {@link ClassCoreHelper} 分派）。 */
    public record Template(String id, String series, ClassCore core) {}

    /** 24 职业（注册顺序即 UI 展示顺序，按系列分组；牵响原创名，与荣耀脱钩）：
     * 刃系：相剑士/霜刃/裂刃狂/影镰；弹系：贯星者/焰雨/械师/破城炮；
     * 拳系：疾拳/山崩/御气/毒手；术系：元素相师/斗术师/兽契者/玄机；
     * 影系：影袭/夜行/亡语/瞬身；辉系：辉医/磐辉/破邪/血誓。
     * <p><b>id 策略</b>：英文 id 全部保持不变（swordsman/priest/...），只换显示名——
     * 已存档玩家的 {@code classTemplateId} 因此零迁移兼容；若未来要改 id，
     * 必须同步加旧 id 映射（本注释留档防误伤）。</p> */
    public static final Map<String, Template> TEMPLATES = new LinkedHashMap<>() {{
            // —— 剑士系 ——
            put("swordsman", new Template("swordsman", "swordsman", new ClassCore("fire", "arcane", "sword")));
            put("spellsword", new Template("spellsword", "swordsman", new ClassCore("arcane", "lightning", "katana")));
            put("berserker", new Template("berserker", "swordsman", new ClassCore("fire", "blood", "greatsword")));
            put("reaper", new Template("reaper", "swordsman", new ClassCore("shadow", "frost", "scythe")));
            // —— 枪手系 ——
            put("sharpshooter", new Template("sharpshooter", "gunner", new ClassCore("lightning", "arcane", "spear")));
            put("sapper", new Template("sapper", "gunner", new ClassCore("fire", "lightning", "mace")));
            put("mechanic", new Template("mechanic", "gunner", new ClassCore("arcane", "nature", "book")));
            put("artillery", new Template("artillery", "gunner", new ClassCore("fire", "lightning", "hammer")));
            // —— 格斗系 ——
            put("pugilist", new Template("pugilist", "fighter", new ClassCore("fire", "lightning", "dagger")));
            put("judoka", new Template("judoka", "fighter", new ClassCore("nature", "lightning", "hammer")));
            put("qigong", new Template("qigong", "fighter", new ClassCore("nature", "holy", "staff")));
            put("rogue", new Template("rogue", "fighter", new ClassCore("shadow", "nature", "dagger")));
            // —— 法师系 ——
            put("elementalist", new Template("elementalist", "mage", new ClassCore("fire", "frost", "staff")));
            put("battle_mage", new Template("battle_mage", "mage", new ClassCore("fire", "arcane", "sword")));
            put("summoner", new Template("summoner", "mage", new ClassCore("nature", "arcane", "book")));
            put("scholar", new Template("scholar", "mage", new ClassCore("arcane", "shadow", "book")));
            // —— 暗夜系 ——
            put("assassin", new Template("assassin", "dark", new ClassCore("shadow", "ender", "dagger")));
            put("thief", new Template("thief", "dark", new ClassCore("shadow", "nature", "dagger")));
            put("necro", new Template("necro", "dark", new ClassCore("shadow", "blood", "book")));
            put("ninja", new Template("ninja", "dark", new ClassCore("shadow", "lightning", "katana")));
            // —— 圣职系 ——
            put("priest", new Template("priest", "priest", new ClassCore("holy", "nature", "staff")));
            put("paladin", new Template("paladin", "priest", new ClassCore("holy", "fire", "mace")));
            put("exorcist", new Template("exorcist", "priest", new ClassCore("holy", "lightning", "hammer")));
            put("avenger", new Template("avenger", "priest", new ClassCore("blood", "fire", "scythe")));
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
        Template t = templateId == null ? null : TEMPLATES.get(templateId);
        return t == null ? null : t.core();
    }

    /** 内核三元组 → 匹配的职业模板（招牌被动按此分派；自定义内核不匹配 = 无被动）。 */
    public static Template templateOf(ClassCore core) {
        if (core == null || !core.isSet()) return null;
        for (Template t : TEMPLATES.values()) {
            if (t.core().equals(core)) return t;
        }
        return null;
    }
}
