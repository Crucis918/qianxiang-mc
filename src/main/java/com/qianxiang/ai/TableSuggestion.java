package com.qianxiang.ai;

import com.qianxiang.menu.AlchemyTableMenu;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.TableSuggestionPayload;
import com.qianxiang.phase.ForgeComposer;
import com.qianxiang.phase.SpellScrollComposer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 「能做啥」主动建议的本地分析（服务端，纯只读，零副作用）。
 * <p>
 * 材料槽内容防抖后由 menu 调用：<b>不调 HTTP</b>，直接复用组合管线
 * （{@link ForgeComposer#compose} / {@link SpellScrollComposer#compose}，
 * 与产物预览同一事实源）算出当前材料能做出什么，打包成
 * {@link TableSuggestionPayload} 下发；AI 在线时的增强分析是后续可选项，
 * 本地分析永远先显示，玩家不等。
 * </p>
 */
public final class TableSuggestion {

    private TableSuggestion() {}

    /** 空建议（材料清空/组合无效）：itemId 空串 = 客户端清行。 */
    public static TableSuggestionPayload empty(boolean magic) {
        return new TableSuggestionPayload(magic, "", "", 0.0);
    }

    /** 锻造台：25 材料槽 → 本地组合 → （产物 id, 形态, 攻击力）。 */
    public static TableSuggestionPayload forgePayload(Container container) {
        List<ItemStack> mats = new ArrayList<>(ForgeTableMenu.MATERIAL_SLOTS);
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            mats.add(container.getItem(i));
        }
        ForgeComposer.Composition comp = ForgeComposer.compose(mats);
        if (!comp.valid() || comp.result().isEmpty()) {
            return empty(false);
        }
        String itemId = BuiltInRegistries.ITEM.getKey(comp.result().getItem()).toString();
        String form = comp.attributes() == null ? "" : comp.attributes().form();
        double atk = comp.attributes() == null ? 0.0 : comp.attributes().attackDamage();
        return new TableSuggestionPayload(false, itemId, form == null ? "" : form, atk);
    }

    /** 炼金台：6 材料槽 → 本地组合 → （卷轴 id, "", 法术强度）。 */
    public static TableSuggestionPayload alchemyPayload(Container container) {
        List<ItemStack> mats = new ArrayList<>(AlchemyTableMenu.MATERIAL_SLOTS);
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            mats.add(container.getItem(i));
        }
        SpellScrollComposer.Composition comp = SpellScrollComposer.compose(mats);
        if (!comp.valid() || comp.result().isEmpty()) {
            return empty(true);
        }
        String itemId = BuiltInRegistries.ITEM.getKey(comp.result().getItem()).toString();
        double power = comp.spell() == null ? 0.0 : comp.spell().power();
        return new TableSuggestionPayload(true, itemId, "", power);
    }
}
