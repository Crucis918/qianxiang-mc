package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import com.qianxiang.item.QianxiangArmorItem;
import com.qianxiang.item.QianxiangMaterialItem;
import com.qianxiang.item.QianxiangToolItem;
import com.qianxiang.item.QianxiangWeaponItem;
import com.qianxiang.phase.ItemConceptResolver;
import com.qianxiang.phase.Phase;
import com.qianxiang.phase.PhaseFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.Map;
import java.util.Set;

/**
 * 全局物品概念 tooltip——让任何物品（背包/箱子/工作台）悬停都显示千相概念。
 * <p>
 * 玩家要「万物皆有意义」能直接看到：悬停草方块显示「大地 {生命/中和}」、
 * 悬停圆石显示「坚岩 {沉潜/秩序}·金属基底/防御」。
 * <p>
 * 跳过自定义千相物品（{@link QianxiangMaterialItem} 等已自带完整 tooltip），
 * 只对原版/无自带概念 tooltip 的物品追加，避免重复。
 * 全部走 {@link ItemConceptResolver#resolve} 推导，与合成端口径一致。
 * 客户端事件，任何异常吞掉——绝不让 tooltip 渲染炸客户端。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class ItemConceptTooltipHandler {

    private ItemConceptTooltipHandler() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        try {
            ItemStack stack = event.getItemStack();
            if (stack == null || stack.isEmpty()) return;
            // 自定义千相物品自带完整 tooltip，跳过（避免重复）
            if (stack.getItem() instanceof QianxiangMaterialItem
                    || stack.getItem() instanceof QianxiangWeaponItem
                    || stack.getItem() instanceof QianxiangArmorItem
                    || stack.getItem() instanceof QianxiangToolItem) {
                return;
            }

            ItemConceptResolver.ItemConcept concept = ItemConceptResolver.resolve(stack);
            if (concept == null) return;

            Set<Phase> phases = concept.phases() == null ? Set.of() : concept.phases();
            Set<PhaseFunction> functions = concept.functions() == null ? Set.of() : concept.functions();
            Map<ResourceLocation, Integer> effects = concept.effects() == null ? Map.of() : concept.effects();
            String conceptKey = concept.conceptKey();

            // 行1：概念名（青）+ 相性（黄 {生命/中和}）
            MutableComponent line1 = Component.translatable("qianxiang.tooltip.concept_prefix")
                    .withStyle(ChatFormatting.DARK_AQUA);
            if (conceptKey != null && !conceptKey.isBlank()) {
                line1.append(Component.translatable(conceptKey).withStyle(ChatFormatting.AQUA));
            }
            if (!phases.isEmpty()) {
                MutableComponent ph = Component.literal(" {").withStyle(ChatFormatting.GRAY);
                boolean first = true;
                for (Phase p : phases) {
                    if (!first) ph.append("/");
                    ph.append(Component.translatable("qianxiang.phase." + p.name().toLowerCase())
                            .withStyle(ChatFormatting.YELLOW));
                    first = false;
                }
                ph.append("}");
                line1.append(ph);
            }
            event.getToolTip().add(line1);

            // 行2：功能算子（绿）+ 自由状态效果（浅绿），有才显示
            if (!functions.isEmpty()) {
                MutableComponent line2 = Component.empty();
                boolean first = true;
                for (PhaseFunction fn : functions) {
                    if (!first) line2.append(Component.literal("·").withStyle(ChatFormatting.DARK_GRAY));
                    line2.append(fn.display().copy().withStyle(ChatFormatting.GREEN));
                    first = false;
                }
                event.getToolTip().add(line2);
            }
            if (!effects.isEmpty()) {
                MutableComponent line3 = Component.translatable("qianxiang.tooltip.concept_effects")
                        .withStyle(ChatFormatting.DARK_GREEN);
                boolean first = true;
                for (var e : effects.entrySet()) {
                    if (!first) line3.append(Component.literal("·").withStyle(ChatFormatting.DARK_GRAY));
                    line3.append(Component.translatable("effect." + e.getKey().getNamespace() + "." + e.getKey().getPath())
                            .withStyle(ChatFormatting.GREEN));
                    if (e.getValue() != null && e.getValue() > 1) line3.append("×" + e.getValue());
                    first = false;
                }
                event.getToolTip().add(line3);
            }
        } catch (Throwable ignored) {
            // tooltip 渲染绝不能炸
        }
    }
}
