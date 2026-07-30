package com.qianxiang.cap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职业技能：每职业 2 个主动技能（J=技能1，潜行+J=技能2）。
 * <p>
 * 字段即自由法术契约（{@code element/form/effect/modifiers/power}，全部落在
 * {@link com.qianxiang.spell.CustomSpell} 白名单内，不造新元素），外加
 * {@link Special} 机制标记——{@code NONE} 直接走
 * {@link com.qianxiang.spell.SpellEffectEngine#cast} 标准结算，
 * 其余由 {@link ClassSkillMechanics} 兑现（突进/闪现/连段/护盾/召唤/牵拉/烟雾/扇形/强化）。
 * </p>
 * <p>调平常量：强度 power 4~7、蓝耗 20~40、冷却 10~30s（200~600t），全表集中于此。</p>
 */
public record ClassSkill(String id, String displayKey, String element, String form, String effect,
                         List<String> modifiers, int power, int manaCost, int cooldownTicks,
                         Special special) {

    /** 技能机制（NONE = 纯引擎标准结算）。 */
    public enum Special { NONE, DASH, BLINK, FLURRY, SHIELD, SUMMON_WOLF, SUMMON_GOLEM, PULL, SMOKE, FAN3, EMPOWER }

    /** 24 职业 × 2 技能（注册顺序 = 技能1/技能2）。
     *  id 策略同 {@link ClassCore#TEMPLATES}：技能 id（如 swordsman_flurry）不变，
     *  显示名走 lang（qianxiang.classskill.*），换皮不改 id，存档/冷却表零迁移。 */
    public static final Map<String, ClassSkill[]> BY_CLASS = new LinkedHashMap<>() {{
            put("swordsman", skills(
                    skill("swordsman_flurry", "fire", "touch", "damage", 5, 25, 240, Special.FLURRY),
                    skill("swordsman_draw", "fire", "aoe", "damage", 5, 30, 300, Special.NONE)));
            put("spellsword", skills(
                    skill("spellsword_resonance", "arcane", "self", "buff", 4, 25, 400, Special.EMPOWER),
                    skill("spellsword_dash", "lightning", "touch", "damage", 5, 25, 240, Special.DASH)));
            put("berserker", skills(
                    skill("berserker_rage", "blood", "self", "buff", List.of("extended"), 4, 30, 500, Special.NONE),
                    skill("berserker_slam", "fire", "aoe", "damage", 6, 30, 300, Special.NONE)));
            put("reaper", skills(
                    skill("reaper_shadowstep", "shadow", "self", "buff", List.of("extended"), 4, 25, 400, Special.NONE),
                    skill("reaper_coldprison", "frost", "aoe", "damage", 5, 30, 300, Special.NONE)));
            put("sharpshooter", skills(
                    skill("sharpshooter_fan", "lightning", "projectile", "damage", 4, 25, 240, Special.FAN3),
                    skill("sharpshooter_snipe", "lightning", "beam", "damage", 7, 35, 400, Special.NONE)));
            put("sapper", skills(
                    skill("sapper_hellfire", "fire", "aoe", "damage", 6, 35, 400, Special.NONE),
                    skill("sapper_flashbang", "shadow", "aoe", "debuff", 4, 25, 300, Special.NONE)));
            put("mechanic", skills(
                    skill("mechanic_turret", "arcane", "self", "utility", 4, 40, 600, Special.SUMMON_GOLEM),
                    skill("mechanic_selfdestruct", "fire", "aoe", "damage", 5, 30, 300, Special.NONE)));
            put("artillery", skills(
                    skill("artillery_focusflame", "fire", "beam", "damage", 6, 30, 300, Special.NONE),
                    skill("artillery_quantum", "fire", "projectile", "damage", List.of("amplified"), 6, 35, 400, Special.NONE)));
            put("pugilist", skills(
                    skill("pugilist_kicks", "fire", "touch", "damage", 4, 20, 200, Special.FLURRY),
                    skill("pugilist_blink", "lightning", "touch", "damage", 5, 25, 240, Special.DASH)));
            put("judoka", skills(
                    skill("judoka_throw", "nature", "touch", "damage", 6, 30, 300, Special.NONE),
                    skill("judoka_whirlwind", "lightning", "aoe", "damage", 4, 25, 300, Special.NONE)));
            put("qigong", skills(
                    skill("qigong_bolt", "nature", "projectile", "damage", List.of("homing"), 5, 30, 300, Special.NONE),
                    skill("qigong_shield", "holy", "self", "buff", 4, 25, 400, Special.SHIELD)));
            put("rogue", skills(
                    skill("rogue_poisonmist", "nature", "aoe", "debuff", 4, 25, 300, Special.NONE),
                    skill("rogue_net", "shadow", "aoe", "debuff", 4, 25, 300, Special.PULL)));
            put("elementalist", skills(
                    skill("elementalist_storm", "frost", "aoe", "damage", 6, 35, 400, Special.NONE),
                    skill("elementalist_barrier", "frost", "self", "buff", 4, 25, 400, Special.SHIELD)));
            put("battle_mage", skills(
                    skill("battlemage_dragonrise", "ender", "touch", "damage", 5, 25, 240, Special.NONE),
                    skill("battlemage_skysoar", "fire", "touch", "damage", 5, 25, 240, Special.DASH)));
            put("summoner", skills(
                    skill("summoner_reinforce", "nature", "self", "utility", 4, 35, 600, Special.SUMMON_WOLF),
                    skill("summoner_empower", "nature", "self", "buff", 4, 25, 400, Special.EMPOWER)));
            put("scholar", skills(
                    skill("scholar_blink", "ender", "self", "utility", 4, 20, 200, Special.BLINK),
                    skill("scholar_antigravity", "ender", "aoe", "debuff", 4, 25, 300, Special.NONE)));
            put("assassin", skills(
                    skill("assassin_shadowstrike", "shadow", "touch", "damage", 5, 25, 240, Special.BLINK),
                    skill("assassin_shadowdance", "shadow", "touch", "damage", 5, 25, 240, Special.FLURRY)));
            put("thief", skills(
                    skill("thief_smokebomb", "shadow", "self", "utility", 4, 25, 300, Special.SMOKE),
                    skill("thief_grapple", "ender", "touch", "damage", 4, 20, 200, Special.DASH)));
            put("necro", skills(
                    skill("necro_bonespear", "shadow", "projectile", "damage", List.of("piercing"), 5, 30, 300, Special.NONE),
                    skill("necro_revive", "blood", "self", "heal", 4, 30, 400, Special.NONE)));
            put("ninja", skills(
                    skill("ninja_shadowdance", "shadow", "touch", "damage", 5, 25, 240, Special.FLURRY),
                    skill("ninja_substitute", "shadow", "self", "buff", 4, 30, 500, Special.SHIELD)));
            put("priest", skills(
                    skill("priest_massheal", "holy", "aoe", "heal", 4, 35, 400, Special.NONE),
                    skill("priest_holyshield", "holy", "self", "buff", 4, 25, 400, Special.SHIELD)));
            put("paladin", skills(
                    skill("paladin_lightcharge", "holy", "beam", "damage", 5, 25, 240, Special.NONE),
                    skill("paladin_judgement", "holy", "touch", "damage", 6, 30, 300, Special.NONE)));
            put("exorcist", skills(
                    skill("exorcist_barrier", "holy", "aoe", "damage", 5, 30, 300, Special.NONE),
                    skill("exorcist_talisman", "holy", "touch", "damage", 5, 25, 240, Special.NONE)));
            put("avenger", skills(
                    skill("avenger_bloodslash", "blood", "touch", "damage", 6, 20, 240, Special.NONE),
                    skill("avenger_echo", "blood", "aoe", "damage", 5, 30, 300, Special.NONE)));
        }};

    private static ClassSkill[] skills(ClassSkill... skills) {
        return skills;
    }

    private static ClassSkill skill(String id, String element, String form, String effect,
                                    int power, int manaCost, int cooldownTicks, Special special) {
        return new ClassSkill(id, "qianxiang.classskill." + id, element, form, effect,
                List.of(), power, manaCost, cooldownTicks, special);
    }

    private static ClassSkill skill(String id, String element, String form, String effect,
                                    List<String> modifiers, int power, int manaCost, int cooldownTicks,
                                    Special special) {
        return new ClassSkill(id, "qianxiang.classskill." + id, element, form, effect,
                modifiers, power, manaCost, cooldownTicks, special);
    }

    /** 某职业的第 slot 个技能（0/1）；无职业/越界返回 null。 */
    public static ClassSkill skillOf(String classId, int slot) {
        ClassSkill[] skills = BY_CLASS.get(classId);
        if (skills == null || slot < 0 || slot >= skills.length) return null;
        return skills[slot];
    }
}
