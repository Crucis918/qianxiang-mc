package com.qianxiang.item;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.spell.CustomSpell;
import com.qianxiang.spell.SpellBookData;
import com.qianxiang.spell.SpellCastHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 千相法术书 —— 自由法术系统的施法载体。
 * <p>
 * 内容存于 {@code qianxiang:spellbook} 组件（{@link SpellBookData}：
 * 法术列表 + 当前选中下标）。法术可以是预置法术（见 {@link CustomSpell#registry()}），
 * 也可以是锻造台按材料算子生成的法术（见 {@link com.qianxiang.phase.ForgeComposer}）。
 * </p>
 * <ul>
 *   <li>右键：施放当前选中的法术（服务端走
 *       {@link SpellCastHandler#castCustomSpell}：法力/冷却校验 →
 *       {@link com.qianxiang.spell.SpellEffectEngine#cast} 兑现效果）。</li>
 *   <li>潜行 + 右键：循环切换选中法术（服务端直接改组件，
 *       组件已 networkSynchronized，tooltip 自动刷新）。</li>
 *   <li>V 键施法也兼容：主手持书时按 V 与右键等效（见 {@link SpellCastHandler#handle}）。</li>
 *   <li>tooltip：列出全部法术（元素/形式/效果/威力/消耗），当前选中项高亮。</li>
 * </ul>
 */
public class SpellBookItem extends Item {

    public SpellBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }

        SpellBookData book = stack.get(QianxiangDataComponents.SPELLBOOK.get());
        if (book == null || book.isEmpty()) {
            if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(Component.translatable("qianxiang.spellbook.empty"), true);
            }
            return InteractionResultHolder.fail(stack);
        }

        if (player.isShiftKeyDown()) {
            // 潜行 + 右键：切换选中法术。
            SpellBookData next = book.cycle();
            stack.set(QianxiangDataComponents.SPELLBOOK.get(), next);
            CustomSpell selected = next.selected();
            if (selected != null && player instanceof ServerPlayer sp) {
                sp.displayClientMessage(Component.translatable("qianxiang.spellbook.switched",
                        selected.displayName()), true);
            }
            return InteractionResultHolder.success(stack);
        }

        // 右键：施放当前选中的法术。
        CustomSpell spell = book.selected();
        if (spell == null || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.fail(stack);
        }
        boolean cast = SpellCastHandler.castCustomSpell(spell, sp);
        return cast ? InteractionResultHolder.success(stack) : InteractionResultHolder.fail(stack);
    }

    /** 法术书恒带附魔光泽。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        SpellBookData book = stack.get(QianxiangDataComponents.SPELLBOOK.get());
        if (book == null || book.isEmpty()) {
            tooltip.add(Component.translatable("qianxiang.spellbook.empty").withStyle(ChatFormatting.GRAY));
            return;
        }
        for (int i = 0; i < book.spells().size(); i++) {
            CustomSpell spell = book.spells().get(i);
            boolean selected = i == book.selectedIndex();
            tooltip.add(spellLine(spell, selected));
        }
        tooltip.add(Component.translatable("qianxiang.spellbook.hint").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 单个法术的 tooltip 行：选中高亮 + 名称 + 元素/形式/效果 + 威力/消耗。 */
    private static Component spellLine(CustomSpell spell, boolean selected) {
        ChatFormatting color = selected ? ChatFormatting.GOLD : ChatFormatting.GRAY;
        net.minecraft.network.chat.MutableComponent line = Component.literal(selected ? "▶ " : "  ");
        line.append(spell.displayName().copy().withStyle(selected ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
        line.append(Component.literal(" "));
        line.append(Component.translatable("qianxiang.spellbook.stats",
                CustomSpell.elementName(spell.element()),
                CustomSpell.formName(spell.form()),
                CustomSpell.effectName(spell.effect()),
                spell.power(),
                spell.manaCost()));
        return line.withStyle(color);
    }
}
