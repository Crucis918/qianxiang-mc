package com.qianxiang.cap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家法术数据 Attachment：mana、已学法术、冷却时间。
 * <p>
 * 通过 {@link QianxiangAttachments#PLAYER_SPELL_DATA} 挂在玩家身上，
 * NeoForge 负责序列化/反序列化与跨死亡保留（copyOnDeath）。
 * 本 record 不可变，所有写操作返回新实例。
 */
public record PlayerSpellData(
        int currentMana,
        int maxMana,
        List<ResourceLocation> learned,
        Map<ResourceLocation, Integer> cooldowns
) {

    /** 默认最大法力值。 */
    public static final int DEFAULT_MAX_MANA = 100;

    public static PlayerSpellData empty() {
        return new PlayerSpellData(DEFAULT_MAX_MANA, DEFAULT_MAX_MANA, List.of(), Map.of());
    }

    // ============================ Codec ============================

    public static final Codec<PlayerSpellData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("current_mana").forGetter(PlayerSpellData::currentMana),
            Codec.INT.fieldOf("max_mana").forGetter(PlayerSpellData::maxMana),
            ResourceLocation.CODEC.listOf().fieldOf("learned").forGetter(PlayerSpellData::learned),
            Codec.unboundedMap(ResourceLocation.CODEC, Codec.INT).fieldOf("cooldowns").forGetter(PlayerSpellData::cooldowns)
    ).apply(instance, PlayerSpellData::new));

    // ============================ 便捷查询 ============================

    public boolean hasLearned(ResourceLocation id) {
        return learned.contains(id);
    }

    public boolean isOnCooldown(ResourceLocation id) {
        return cooldowns.getOrDefault(id, 0) > 0;
    }

    public int cooldownOf(ResourceLocation id) {
        return cooldowns.getOrDefault(id, 0);
    }

    // ============================ 写操作（返回新实例） ============================

    /** 学习一个法术；已学过则返回 this。 */
    public PlayerSpellData learn(ResourceLocation id) {
        if (hasLearned(id)) return this;
        List<ResourceLocation> next = new ArrayList<>(learned.size() + 1);
        next.addAll(learned);
        next.add(id);
        return new PlayerSpellData(currentMana, maxMana, List.copyOf(next), cooldowns);
    }

    /** 设置当前法力，自动 clamp 到 [0, maxMana]。 */
    public PlayerSpellData withMana(int mana) {
        int clamped = Math.max(0, Math.min(maxMana, mana));
        if (clamped == currentMana) return this;
        return new PlayerSpellData(clamped, maxMana, learned, cooldowns);
    }

    /** 设置最大法力；当前法力会被 clamp 到新上限。 */
    public PlayerSpellData withMaxMana(int max) {
        int clampedMax = Math.max(1, max);
        if (clampedMax == maxMana && currentMana <= maxMana) return this;
        return new PlayerSpellData(Math.min(currentMana, clampedMax), clampedMax, learned, cooldowns);
    }

    /** 设置某法术的剩余冷却 tick；ticks <= 0 时移除该条目。 */
    public PlayerSpellData setCooldown(ResourceLocation id, int ticks) {
        if (ticks <= 0) {
            if (!cooldowns.containsKey(id)) return this;
            Map<ResourceLocation, Integer> next = new HashMap<>(cooldowns);
            next.remove(id);
            return new PlayerSpellData(currentMana, maxMana, learned, Map.copyOf(next));
        }
        if (cooldowns.getOrDefault(id, 0) == ticks) return this;
        Map<ResourceLocation, Integer> next = new HashMap<>(cooldowns);
        next.put(id, ticks);
        return new PlayerSpellData(currentMana, maxMana, learned, Map.copyOf(next));
    }

    /** 每 tick 调用：所有冷却 -1。没有冷却或全部归零则返回 this。 */
    public PlayerSpellData tickCooldowns() {
        if (cooldowns.isEmpty()) return this;
        Map<ResourceLocation, Integer> next = new HashMap<>(cooldowns.size());
        boolean changed = false;
        for (var entry : cooldowns.entrySet()) {
            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                next.put(entry.getKey(), remaining);
            }
            if (remaining != entry.getValue()) {
                changed = true;
            }
        }
        if (!changed) return this;
        return new PlayerSpellData(currentMana, maxMana, learned, Map.copyOf(next));
    }
}
