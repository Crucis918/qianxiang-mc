package com.qianxiang.cap;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 玩家法术数据 Attachment：mana、已学法术、冷却时间。
 * <p>
 * 通过 {@link QianxiangAttachments#PLAYER_SPELL_DATA} 挂在玩家身上，
 * NeoForge 负责序列化/反序列化与跨死亡保留（copyOnDeath）。
 * 本 record 不可变，所有写操作返回新实例。
 * <p>
 * 已学法术存完整 {@link CustomSpell}（「卷轴学习 + 轮盘施法」的地基），
 * 旧存档只存 id 列表（{@code learned} 字段），读入时自动迁移（见 {@link #CODEC}）。
 */
public record PlayerSpellData(
        int currentMana,
        int maxMana,
        List<CustomSpell> learnedSpells,
        Map<ResourceLocation, Integer> cooldowns
) {

    /** 默认最大法力值。 */
    public static final int DEFAULT_MAX_MANA = 100;

    public static PlayerSpellData empty() {
        return new PlayerSpellData(DEFAULT_MAX_MANA, DEFAULT_MAX_MANA, List.of(), Map.of());
    }

    // ============================ Codec ============================

    /** 新格式：已学法术存完整 CustomSpell。 */
    private static final Codec<PlayerSpellData> CURRENT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("current_mana").forGetter(PlayerSpellData::currentMana),
            Codec.INT.fieldOf("max_mana").forGetter(PlayerSpellData::maxMana),
            CustomSpell.CODEC.listOf().fieldOf("learned_spells").forGetter(PlayerSpellData::learnedSpells),
            Codec.unboundedMap(ResourceLocation.CODEC, Codec.INT).fieldOf("cooldowns").forGetter(PlayerSpellData::cooldowns)
    ).apply(instance, PlayerSpellData::new));

    /** 旧格式（存档兼容）：已学法术只有 id 列表，逐 id 查预置注册表转换，查不到的丢弃。 */
    private static final Codec<PlayerSpellData> LEGACY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("current_mana").forGetter(PlayerSpellData::currentMana),
            Codec.INT.fieldOf("max_mana").forGetter(PlayerSpellData::maxMana),
            ResourceLocation.CODEC.listOf().fieldOf("learned").forGetter(data -> List.of()),
            Codec.unboundedMap(ResourceLocation.CODEC, Codec.INT).fieldOf("cooldowns").forGetter(PlayerSpellData::cooldowns)
    ).apply(instance, (mana, maxMana, legacyIds, cooldowns) -> {
        List<CustomSpell> migrated = new ArrayList<>(legacyIds.size());
        for (ResourceLocation id : legacyIds) {
            CustomSpell spell = CustomSpell.byId(id);
            if (spell != null) migrated.add(spell);
        }
        return new PlayerSpellData(mana, maxMana, List.copyOf(migrated), cooldowns);
    }));

    /**
     * 向后兼容解码：先按新格式（{@code learned_spells}）读，失败再按旧格式（{@code learned}）迁移。
     * 写出永远只走新格式（{@link Either#left}），旧字段不再落盘。
     */
    public static final Codec<PlayerSpellData> CODEC =
            Codec.either(CURRENT_CODEC, LEGACY_CODEC)
                    .xmap(either -> either.map(data -> data, data -> data), Either::left);

    // ============================ 便捷查询 ============================

    public boolean hasLearned(ResourceLocation id) {
        return findLearned(id).isPresent();
    }

    /** 按 id 查已学法术；未学过返回空。 */
    public Optional<CustomSpell> findLearned(ResourceLocation id) {
        for (CustomSpell spell : learnedSpells) {
            if (spell.id().equals(id)) return Optional.of(spell);
        }
        return Optional.empty();
    }

    public boolean isOnCooldown(ResourceLocation id) {
        return cooldowns.getOrDefault(id, 0) > 0;
    }

    public int cooldownOf(ResourceLocation id) {
        return cooldowns.getOrDefault(id, 0);
    }

    // ============================ 写操作（返回新实例） ============================

    /** 学习一个法术；同 id 已学过则以后学的覆盖旧条目。 */
    public PlayerSpellData learn(CustomSpell spell) {
        List<CustomSpell> next = new ArrayList<>(learnedSpells.size() + 1);
        boolean replaced = false;
        for (CustomSpell existing : learnedSpells) {
            if (existing.id().equals(spell.id())) {
                next.add(spell);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) next.add(spell);
        return new PlayerSpellData(currentMana, maxMana, List.copyOf(next), cooldowns);
    }

    /** 整体替换已学法术列表（客户端同步用）。 */
    public PlayerSpellData withLearnedSpells(List<CustomSpell> spells) {
        if (learnedSpells.equals(spells)) return this;
        return new PlayerSpellData(currentMana, maxMana, List.copyOf(spells), cooldowns);
    }

    /** 设置当前法力，自动 clamp 到 [0, maxMana]。 */
    public PlayerSpellData withMana(int mana) {
        return withMana(mana, maxMana);
    }

    /**
     * 设置当前法力，clamp 到 [0, cap]——cap 由调用方给定，
     * 供含增幅器加成的「有效法力上限」使用（见 {@code AmplifierHelper#effectiveMaxMana}），
     * maxMana 字段本身不动（它是不含装备加成的基础上限）。
     */
    public PlayerSpellData withMana(int mana, int cap) {
        int clamped = Math.max(0, Math.min(Math.max(1, cap), mana));
        if (clamped == currentMana) return this;
        return new PlayerSpellData(clamped, maxMana, learnedSpells, cooldowns);
    }

    /** 设置最大法力；当前法力会被 clamp 到新上限。 */
    public PlayerSpellData withMaxMana(int max) {
        int clampedMax = Math.max(1, max);
        if (clampedMax == maxMana && currentMana <= maxMana) return this;
        return new PlayerSpellData(Math.min(currentMana, clampedMax), clampedMax, learnedSpells, cooldowns);
    }

    /** 设置某法术的剩余冷却 tick；ticks <= 0 时移除该条目。 */
    public PlayerSpellData setCooldown(ResourceLocation id, int ticks) {
        if (ticks <= 0) {
            if (!cooldowns.containsKey(id)) return this;
            Map<ResourceLocation, Integer> next = new HashMap<>(cooldowns);
            next.remove(id);
            return new PlayerSpellData(currentMana, maxMana, learnedSpells, Map.copyOf(next));
        }
        if (cooldowns.getOrDefault(id, 0) == ticks) return this;
        Map<ResourceLocation, Integer> next = new HashMap<>(cooldowns);
        next.put(id, ticks);
        return new PlayerSpellData(currentMana, maxMana, learnedSpells, Map.copyOf(next));
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
        return new PlayerSpellData(currentMana, maxMana, learnedSpells, Map.copyOf(next));
    }
}
