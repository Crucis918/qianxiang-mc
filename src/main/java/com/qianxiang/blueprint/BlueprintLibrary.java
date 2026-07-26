package com.qianxiang.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家拥有的千相蓝图库。
 * <p>
 * 作为 NeoForge Attachment 持久化（{@link com.qianxiang.cap.QianxiangAttachments#BLUEPRINT_LIBRARY}），
 * 死亡后保留。内部不可变，写操作返回新实例。
 */
public record BlueprintLibrary(List<BlueprintData> blueprints) {

    public static BlueprintLibrary empty() {
        return new BlueprintLibrary(List.of());
    }

    public static final Codec<BlueprintLibrary> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlueprintData.CODEC.listOf().fieldOf("blueprints").forGetter(BlueprintLibrary::blueprints)
    ).apply(instance, BlueprintLibrary::new));

    /** 追加一份蓝图。 */
    public BlueprintLibrary withAdded(BlueprintData data) {
        List<BlueprintData> next = new ArrayList<>(this.blueprints.size() + 1);
        next.addAll(this.blueprints);
        next.add(data);
        return new BlueprintLibrary(List.copyOf(next));
    }
}
