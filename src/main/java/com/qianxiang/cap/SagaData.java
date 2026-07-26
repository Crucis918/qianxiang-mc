package com.qianxiang.cap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * 相谱录 + 位格 —— 玩家不可逆的传记（律二：相不可逆铭刻，只增不减）。
 *
 * <p>灵魂契约：
 * <ul>
 *   <li>{@code position}：位格 P∈[0,100]，探索权限门控（深层残界/高级材料区需阈值）。clamp 到区间。</li>
 *   <li>{@code entries}：相谱条目，已格式化的显示串。{@code 只追加，不删改}——
 *       {@link #withEntry(String)} 永远返回包含全部旧条目 + 新条目的新实例。</li>
 *   <li>{@code forgotten}：已淡忘的条目数。条目总数超过 {@link #MAX_ENTRIES} 时，
 *       最旧的条目从明细中淡出（防存档无限膨胀），但铭刻总数不减——
 *       淡忘的条目仍计入 {@code forgotten}，律二"不可逆"以总量形式保留。</li>
 * </ul>
 *
 * <p>本类型为不可变 record，所有写操作返回新实例，配合 AttachmentType 的 setData 实现"只增不减"。
 *
 * <p>TODO(i18n): MVP 雏形直接存显示串；后续若做多语言，应改为存结构化事件 + 客户端格式化。
 */
public record SagaData(int position, List<String> entries, int forgotten) {

    /** 位格上限。 */
    public static final int MAX_POSITION = 100;

    /** 位格下限。 */
    public static final int MIN_POSITION = 0;

    /** 相谱明细条目上限：超出后最旧条目淡忘（只留计数），防止玩家 NBT 无限增长。 */
    public static final int MAX_ENTRIES = 500;

    /** 空相谱：0 位格，无条目。玩家首次访问时的默认值。 */
    public static SagaData empty() {
        return new SagaData(MIN_POSITION, List.of(), 0);
    }

    /** 铭刻总数 = 明细条目 + 已淡忘条目。 */
    public int totalInscribed() {
        return entries.size() + Math.max(0, forgotten);
    }

    /**
     * 记一笔——律二「相不可逆铭刻」的核心。
     *
     * <p>不可逆：只追加新条目，不删改旧条目；位格不变（位格增减走 {@link #withBumpedPosition(int)}）。
     * 明细超过 {@link #MAX_ENTRIES} 时最旧条目淡忘，淡忘数计入 {@link #forgotten}。
     *
     * @param entry 已格式化的显示串（MVP 不做 i18n）
     * @return 包含旧条目 + 新条目的新实例（本实例不变）
     */
    public SagaData withEntry(String entry) {
        List<String> next = new ArrayList<>(this.entries.size() + 1);
        next.addAll(this.entries);
        next.add(entry);
        int faded = this.forgotten;
        while (next.size() > MAX_ENTRIES) {
            next.remove(0);
            faded++;
        }
        return new SagaData(this.position, List.copyOf(next), faded);
    }

    /**
     * 位格增减——clamp 到 [{@value #MIN_POSITION}, {@value #MAX_POSITION}]。
     *
     * <p>条目不变。{@code delta} 可为负（律二不禁止位格波动，只禁止相谱删改）。
     *
     * @param delta 位格增量（正/负均可）
     * @return 位格被 clamp 后的新实例（条目原样保留）
     */
    public SagaData withBumpedPosition(int delta) {
        int next = Math.max(MIN_POSITION, Math.min(MAX_POSITION, this.position + delta));
        return new SagaData(next, this.entries, this.forgotten);
    }

    /**
     * 序列化：position 用 intRange 锁死 [0,100]，entries 用字符串列表。
     * {@code forgotten} 为可选字段（默认 0），旧存档无该字段也能正常读取。
     * 只有序列化才能落盘 + copyOnDeath（玩家死后相谱保留）。
     */
    public static final Codec<SagaData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.intRange(MIN_POSITION, MAX_POSITION).fieldOf("position").forGetter(SagaData::position),
                    Codec.STRING.listOf().fieldOf("entries").forGetter(SagaData::entries),
                    Codec.INT.optionalFieldOf("forgotten", 0).forGetter(SagaData::forgotten)
            ).apply(instance, SagaData::new));
}
