package com.qianxiang.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qianxiang.Qianxiang;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 千相工坊 —— 服务器级共享蓝图库（UGC 的服务器画廊）。
 * <p>
 * 玩家 {@code /qianxiang workshop publish} 把自己的蓝图发布到全服共享库，
 * 其他玩家 {@code list} 浏览、{@code take} 一键取到自己库里。取用次数计数，
 * 好配方自然浮出。数据存主世界 {@link SavedData}（{@code data/qianxiang_workshop.dat}），
 * 随存档持久化。
 * <p>
 * 边界：容量上限 {@value MAX_ENTRIES}，单作者上限 {@value MAX_PER_AUTHOR}；发布者与 OP(2) 可下架；
 * 同作者同名蓝图去重（重复发布覆盖旧条目并保留取用计数）。
 */
public class WorkshopSavedData extends SavedData {

    /** 全服共享蓝图上限。 */
    public static final int MAX_ENTRIES = 200;

    /** 单作者发布上限——防一人刷满全服画廊。 */
    public static final int MAX_PER_AUTHOR = 20;

    private static final String STORAGE_NAME = "qianxiang_workshop";

    /** 一条已发布蓝图：蓝图 + 作者名 + 作者 UUID 串 + 发布时间(epoch ms) + 取用次数。 */
    public record PublishedEntry(BlueprintData data, String author, String authorUuid,
                                 long publishedAt, int takes) {
        public static final Codec<PublishedEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlueprintData.CODEC.fieldOf("data").forGetter(PublishedEntry::data),
                Codec.STRING.fieldOf("author").forGetter(PublishedEntry::author),
                Codec.STRING.optionalFieldOf("author_uuid", "").forGetter(PublishedEntry::authorUuid),
                Codec.LONG.optionalFieldOf("published_at", 0L).forGetter(PublishedEntry::publishedAt),
                Codec.INT.optionalFieldOf("takes", 0).forGetter(PublishedEntry::takes)
        ).apply(instance, PublishedEntry::new));

        public PublishedEntry withTakes(int newTakes) {
            return new PublishedEntry(data, author, authorUuid, publishedAt, newTakes);
        }
    }

    private static final Codec<List<PublishedEntry>> LIST_CODEC = PublishedEntry.CODEC.listOf();

    private final List<PublishedEntry> entries = new ArrayList<>();

    public static WorkshopSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WorkshopSavedData::new, WorkshopSavedData::load, null),
                STORAGE_NAME);
    }

    public static WorkshopSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        WorkshopSavedData data = new WorkshopSavedData();
        try {
            Tag list = tag.get("entries");
            if (list != null) {
                LIST_CODEC.parse(NbtOps.INSTANCE, list)
                        .resultOrPartial(err ->
                                Qianxiang.LOGGER.warn("[Qianxiang] 工坊数据部分损坏，已跳过坏条目：{}", err))
                        .ifPresent(data.entries::addAll);
            }
        } catch (Exception e) {
            Qianxiang.LOGGER.error("[Qianxiang] 工坊数据读取失败（从空库开始）", e);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        LIST_CODEC.encodeStart(NbtOps.INSTANCE, List.copyOf(entries))
                .resultOrPartial(err -> Qianxiang.LOGGER.warn("[Qianxiang] 工坊数据写入部分失败：{}", err))
                .ifPresent(t -> tag.put("entries", t));
        return tag;
    }

    /** 只读视图。 */
    public List<PublishedEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * 发布：同作者同名覆盖旧条目（保留取用计数），否则追加。
     *
     * @return null = 成功；否则为拒绝原因
     */
    public String publish(BlueprintData blueprint, String author, String authorUuid) {
        for (int i = 0; i < entries.size(); i++) {
            PublishedEntry e = entries.get(i);
            if (e.authorUuid().equals(authorUuid) && e.data().name().equals(blueprint.name())) {
                entries.set(i, new PublishedEntry(blueprint, author, authorUuid,
                        System.currentTimeMillis(), e.takes()));
                setDirty();
                return null;
            }
        }
        if (entries.size() >= MAX_ENTRIES) {
            return "工坊已满（" + MAX_ENTRIES + " 条），请先下架旧蓝图";
        }
        long mine = entries.stream().filter(e -> e.authorUuid().equals(authorUuid)).count();
        if (mine >= MAX_PER_AUTHOR) {
            return "你已发布 " + MAX_PER_AUTHOR + " 条（单人上限），请先下架一些旧蓝图";
        }
        entries.add(new PublishedEntry(blueprint, author, authorUuid, System.currentTimeMillis(), 0));
        setDirty();
        return null;
    }

    /** 取用：序号合法则取用计数 +1 并返回条目，否则 null。 */
    public PublishedEntry take(int index) {
        if (index < 0 || index >= entries.size()) return null;
        PublishedEntry e = entries.get(index).withTakes(entries.get(index).takes() + 1);
        entries.set(index, e);
        setDirty();
        return e;
    }

    /**
     * 下架：仅发布者本人或 canModerate=true（OP）可操作。
     *
     * @return 被移除的条目；无权限/序号非法返回 null
     */
    public PublishedEntry remove(int index, String operatorUuid, boolean canModerate) {
        if (index < 0 || index >= entries.size()) return null;
        PublishedEntry e = entries.get(index);
        if (!canModerate && !e.authorUuid().equals(operatorUuid)) return null;
        entries.remove(index);
        setDirty();
        return e;
    }
}
