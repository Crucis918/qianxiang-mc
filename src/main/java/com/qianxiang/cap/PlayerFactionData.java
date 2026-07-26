package com.qianxiang.cap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 玩家势力/烙印数据 —— MVP 骨架。
 *
 * <p>包含：
 * <ul>
 *   <li>{@code reputation}：势力好感度，交易/屠杀会改变。</li>
 *   <li>{@code slaughterCount}：屠杀计数，击杀特定生物时增加。</li>
 *   <li>{@code diplomacyCount}：外交计数，与 NPC 交互/交易时增加。</li>
 *   <li>{@code title}：当前称号 key（如 {@code qianxiang.faction.title.wanderer}）。</li>
 * </ul>
 *
 * <p>「烙印」由屠杀/外交计数占优方决定：
 * <ul>
 *   <li>slaughter &gt; diplomacy：屠杀烙印</li>
 *   <li>diplomacy &gt; slaughter：外交烙印</li>
 *   <li>否则：中和</li>
 * </ul>
 *
 * <p>本类型为不可变 record，写操作返回新实例，配合 AttachmentType 实现持久化。
 */
public record PlayerFactionData(int reputation, int slaughterCount, int diplomacyCount, String title) {

    /** 好感度上限。 */
    public static final int MAX_REPUTATION = 1000;

    /** 好感度下限。 */
    public static final int MIN_REPUTATION = -1000;

    /** 新增称号阈值：当某一方领先另一方达到此值时切换称号。 */
    public static final int TITLE_LEAD_THRESHOLD = 10;

    /** 空数据：0 好感度，0 屠杀，0 外交， wanderer 称号。 */
    public static PlayerFactionData empty() {
        return new PlayerFactionData(0, 0, 0, "qianxiang.faction.title.wanderer");
    }

    /** 增加屠杀计数，并降低少量势力好感度。 */
    public PlayerFactionData withSlaughter(int delta) {
        return new PlayerFactionData(
                clampReputation(reputation - 1),
                Math.max(0, slaughterCount + delta),
                diplomacyCount,
                title);
    }

    /** 增加外交计数，并提升少量势力好感度。 */
    public PlayerFactionData withDiplomacy(int delta) {
        return new PlayerFactionData(
                clampReputation(reputation + 1),
                slaughterCount,
                Math.max(0, diplomacyCount + delta),
                title);
    }

    /** 直接修改好感度。 */
    public PlayerFactionData withReputation(int delta) {
        return new PlayerFactionData(clampReputation(reputation + delta), slaughterCount, diplomacyCount, title);
    }

    /** 设置称号。 */
    public PlayerFactionData withTitle(String title) {
        return new PlayerFactionData(reputation, slaughterCount, diplomacyCount, title);
    }

    /** 根据当前计数重新计算称号。 */
    public PlayerFactionData updateTitle() {
        int lead = diplomacyCount - slaughterCount;
        String next;
        if (lead >= TITLE_LEAD_THRESHOLD) {
            next = "qianxiang.faction.title.diplomat";
        } else if (lead <= -TITLE_LEAD_THRESHOLD) {
            next = "qianxiang.faction.title.butcher";
        } else {
            next = "qianxiang.faction.title.wanderer";
        }
        return withTitle(next);
    }

    /** 返回占优烙印标识：slaughter / diplomacy / neutral。 */
    public String getDominantBrand() {
        if (slaughterCount > diplomacyCount) return "slaughter";
        if (diplomacyCount > slaughterCount) return "diplomacy";
        return "neutral";
    }

    private static int clampReputation(int value) {
        return Math.max(MIN_REPUTATION, Math.min(MAX_REPUTATION, value));
    }

    /** 序列化：落盘 + copyOnDeath。 */
    public static final Codec<PlayerFactionData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.fieldOf("reputation").forGetter(PlayerFactionData::reputation),
                    Codec.INT.fieldOf("slaughter_count").forGetter(PlayerFactionData::slaughterCount),
                    Codec.INT.fieldOf("diplomacy_count").forGetter(PlayerFactionData::diplomacyCount),
                    Codec.STRING.fieldOf("title").forGetter(PlayerFactionData::title)
            ).apply(instance, PlayerFactionData::new));
}
