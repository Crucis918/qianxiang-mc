package com.qianxiang.cap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Set;

/**
 * 玩家熟练度数据 Attachment：三轨（combat/arcane/craft）的 xp/等级/技能点、
 * 已分配节点集合、「相师已开启」标记与主职业内核（{@link ClassCore}）。
 * <p>
 * 未开启（unlocked=false）时不攒 xp、一切效果查询返回中性值（见 {@link ProficiencyHelper}）。
 * 本 record 不可变，所有写操作返回新实例；CODEC 全 optionalFieldOf，旧存档读回默认值
 * （classCore 缺省 = 未设定，职业效果全 ×1.0）。
 * </p>
 */
public record PlayerProficiencyData(
        boolean unlocked,
        int combatXp, int arcaneXp, int craftXp,
        int combatLevel, int arcaneLevel, int craftLevel,
        int combatPoints, int arcanePoints, int craftPoints,
        Set<String> allocated,
        ClassCore classCore,
        String classTemplateId
) {

    public PlayerProficiencyData {
        allocated = allocated == null ? Set.of() : Set.copyOf(allocated);
        classCore = classCore == null ? ClassCore.EMPTY : classCore;
        classTemplateId = classTemplateId == null ? "" : classTemplateId;
    }

    /** 兼容旧十一参构造（无模板 id）：模板 id 空串（按内核三元组匹配被动）。 */
    public PlayerProficiencyData(boolean unlocked,
                                 int combatXp, int arcaneXp, int craftXp,
                                 int combatLevel, int arcaneLevel, int craftLevel,
                                 int combatPoints, int arcanePoints, int craftPoints,
                                 Set<String> allocated, ClassCore classCore) {
        this(unlocked, combatXp, arcaneXp, craftXp, combatLevel, arcaneLevel, craftLevel,
                combatPoints, arcanePoints, craftPoints, allocated, classCore, "");
    }

    /** 兼容旧十参构造（无职业内核）：classCore = 未设定。 */
    public PlayerProficiencyData(boolean unlocked,
                                 int combatXp, int arcaneXp, int craftXp,
                                 int combatLevel, int arcaneLevel, int craftLevel,
                                 int combatPoints, int arcanePoints, int craftPoints,
                                 Set<String> allocated) {
        this(unlocked, combatXp, arcaneXp, craftXp, combatLevel, arcaneLevel, craftLevel,
                combatPoints, arcanePoints, craftPoints, allocated, ClassCore.EMPTY, "");
    }

    public static PlayerProficiencyData empty() {
        return new PlayerProficiencyData(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, Set.of());
    }

    public static final Codec<PlayerProficiencyData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("unlocked", false).forGetter(PlayerProficiencyData::unlocked),
            Codec.INT.optionalFieldOf("combat_xp", 0).forGetter(PlayerProficiencyData::combatXp),
            Codec.INT.optionalFieldOf("arcane_xp", 0).forGetter(PlayerProficiencyData::arcaneXp),
            Codec.INT.optionalFieldOf("craft_xp", 0).forGetter(PlayerProficiencyData::craftXp),
            Codec.INT.optionalFieldOf("combat_level", 0).forGetter(PlayerProficiencyData::combatLevel),
            Codec.INT.optionalFieldOf("arcane_level", 0).forGetter(PlayerProficiencyData::arcaneLevel),
            Codec.INT.optionalFieldOf("craft_level", 0).forGetter(PlayerProficiencyData::craftLevel),
            Codec.INT.optionalFieldOf("combat_points", 0).forGetter(PlayerProficiencyData::combatPoints),
            Codec.INT.optionalFieldOf("arcane_points", 0).forGetter(PlayerProficiencyData::arcanePoints),
            Codec.INT.optionalFieldOf("craft_points", 0).forGetter(PlayerProficiencyData::craftPoints),
            Codec.STRING.listOf().optionalFieldOf("allocated", java.util.List.of())
                    .xmap(Set::copyOf, java.util.List::copyOf).forGetter(PlayerProficiencyData::allocated),
            // 主职业内核：optionalFieldOf 缺省 EMPTY（未设定），旧存档兼容
            ClassCore.CODEC.optionalFieldOf("class_core", ClassCore.EMPTY)
                    .forGetter(PlayerProficiencyData::classCore),
            // 职业模板 id（24 职业里 swordsman/battle_mage 同内核三元组，靠它区分被动；
            // 空串 = 自定义内核/旧存档，回退按三元组匹配）
            Codec.STRING.optionalFieldOf("class_template_id", "")
                    .forGetter(PlayerProficiencyData::classTemplateId)
    ).apply(instance, PlayerProficiencyData::new));

    // ============================ 按轨读写（不可变范式） ============================

    public int xpOf(ProficiencyTrack track) {
        return switch (track) {
            case COMBAT -> combatXp;
            case ARCANE -> arcaneXp;
            case CRAFT -> craftXp;
        };
    }

    public int levelOf(ProficiencyTrack track) {
        return switch (track) {
            case COMBAT -> combatLevel;
            case ARCANE -> arcaneLevel;
            case CRAFT -> craftLevel;
        };
    }

    public int pointsOf(ProficiencyTrack track) {
        return switch (track) {
            case COMBAT -> combatPoints;
            case ARCANE -> arcanePoints;
            case CRAFT -> craftPoints;
        };
    }

    public PlayerProficiencyData withUnlocked() {
        return unlocked ? this : new PlayerProficiencyData(true, combatXp, arcaneXp, craftXp,
                combatLevel, arcaneLevel, craftLevel, combatPoints, arcanePoints, craftPoints,
                allocated, classCore, classTemplateId);
    }

    public PlayerProficiencyData withXp(ProficiencyTrack track, int xp) {
        if (xpOf(track) == xp) return this;
        return switch (track) {
            case COMBAT -> new PlayerProficiencyData(unlocked, xp, arcaneXp, craftXp,
                    combatLevel, arcaneLevel, craftLevel, combatPoints, arcanePoints, craftPoints,
                    allocated, classCore, classTemplateId);
            case ARCANE -> new PlayerProficiencyData(unlocked, combatXp, xp, craftXp,
                    combatLevel, arcaneLevel, craftLevel, combatPoints, arcanePoints, craftPoints,
                    allocated, classCore, classTemplateId);
            case CRAFT -> new PlayerProficiencyData(unlocked, combatXp, arcaneXp, xp,
                    combatLevel, arcaneLevel, craftLevel, combatPoints, arcanePoints, craftPoints,
                    allocated, classCore, classTemplateId);
        };
    }

    public PlayerProficiencyData withLevel(ProficiencyTrack track, int level) {
        if (levelOf(track) == level) return this;
        return switch (track) {
            case COMBAT -> new PlayerProficiencyData(unlocked, combatXp, arcaneXp, craftXp,
                    level, arcaneLevel, craftLevel, combatPoints, arcanePoints, craftPoints,
                    allocated, classCore, classTemplateId);
            case ARCANE -> new PlayerProficiencyData(unlocked, combatXp, arcaneXp, craftXp,
                    combatLevel, level, craftLevel, combatPoints, arcanePoints, craftPoints,
                    allocated, classCore, classTemplateId);
            case CRAFT -> new PlayerProficiencyData(unlocked, combatXp, arcaneXp, craftXp,
                    combatLevel, arcaneLevel, level, combatPoints, arcanePoints, craftPoints,
                    allocated, classCore, classTemplateId);
        };
    }

    public PlayerProficiencyData withPoints(ProficiencyTrack track, int points) {
        if (pointsOf(track) == points) return this;
        return switch (track) {
            case COMBAT -> new PlayerProficiencyData(unlocked, combatXp, arcaneXp, craftXp,
                    combatLevel, arcaneLevel, craftLevel, points, arcanePoints, craftPoints,
                    allocated, classCore, classTemplateId);
            case ARCANE -> new PlayerProficiencyData(unlocked, combatXp, arcaneXp, craftXp,
                    combatLevel, arcaneLevel, craftLevel, combatPoints, points, craftPoints,
                    allocated, classCore, classTemplateId);
            case CRAFT -> new PlayerProficiencyData(unlocked, combatXp, arcaneXp, craftXp,
                    combatLevel, arcaneLevel, craftLevel, combatPoints, arcanePoints, points,
                    allocated, classCore, classTemplateId);
        };
    }

    public PlayerProficiencyData withAllocated(Set<String> next) {
        if (allocated.equals(next)) return this;
        return new PlayerProficiencyData(unlocked, combatXp, arcaneXp, craftXp,
                combatLevel, arcaneLevel, craftLevel, combatPoints, arcanePoints, craftPoints,
                next, classCore);
    }

    public boolean hasAllocated(String nodeId) {
        return allocated.contains(nodeId);
    }

    /** 写入主职业内核（自定义/回退路径：模板 id 清空，被动按三元组匹配）。 */
    public PlayerProficiencyData withClassCore(ClassCore next) {
        return withClassCore(next, "");
    }

    /** 写入主职业内核与模板 id（模板路径：同内核职业靠 id 区分被动）。 */
    public PlayerProficiencyData withClassCore(ClassCore next, String templateId) {
        ClassCore c = next == null ? ClassCore.EMPTY : next;
        String t = templateId == null ? "" : templateId;
        if (classCore.equals(c) && classTemplateId.equals(t)) return this;
        return new PlayerProficiencyData(unlocked, combatXp, arcaneXp, craftXp,
                combatLevel, arcaneLevel, craftLevel, combatPoints, arcanePoints, craftPoints,
                allocated, c, t);
    }
}
