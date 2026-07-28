package com.qianxiang.client;

import com.qianxiang.network.TableSuggestionPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 「能做啥」主动建议的客户端缓存：服务端材料防抖后下发
 * （{@link TableSuggestionPayload}），两台界面渲染一行「可做：X」。
 * 纯展示——不碰 AI 结果区、不弹卡、不打断输入。换世界/断线清（{@link ClientStateReset}）。
 */
public final class ClientTableSuggestion {

    private ClientTableSuggestion() {}

    private static TableSuggestionPayload last;

    public static void receive(TableSuggestionPayload payload) {
        last = payload;
    }

    public static void clear() {
        last = null;
    }

    /** 当前应显示的一行（无建议/空建议返回 null）。 */
    public static Component line() {
        if (last == null || last.itemId().isEmpty()) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(last.itemId()))
                .orElse(Items.AIR);
        if (item == Items.AIR) return null;
        String value = String.format(java.util.Locale.ROOT, "%.1f", last.value());
        Component detail;
        if (last.magic()) {
            detail = Component.translatable("qianxiang.table.suggestion.detail_magic",
                    item.getDescription(), value);
        } else if (last.formId().isEmpty()) {
            detail = Component.translatable("qianxiang.table.suggestion.detail_noform",
                    item.getDescription(), value);
        } else {
            detail = Component.translatable("qianxiang.table.suggestion.detail",
                    item.getDescription(), value,
                    Component.translatable("qianxiang.weapon_form." + last.formId()));
        }
        return Component.translatable("qianxiang.table.suggestion", detail);
    }
}
