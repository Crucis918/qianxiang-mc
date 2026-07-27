package com.qianxiang.item;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.cap.PlayerSpellData;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.spell.CustomSpell;
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
 * 魔法卷轴 —— 炼金台产物，「卷轴学习」的载体。
 * <p>
 * 单个法术存于 {@code qianxiang:custom_spell} 组件（{@link CustomSpell}）。
 * 右键消耗 1 个学习：法术进入 {@link PlayerSpellData#learnedSpells()}
 * （按 id 去重，后学覆盖）；已学会则提示且不消耗。
 * </p>
 */
public class SpellScrollItem extends Item {

    public SpellScrollItem(Properties properties) {
        super(properties);
    }

    /** 卷轴恒带附魔光泽（有法术铭刻时）。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(QianxiangDataComponents.CUSTOM_SPELL.get());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        CustomSpell spell = stack.get(QianxiangDataComponents.CUSTOM_SPELL.get());
        if (spell == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }

        PlayerSpellData data = serverPlayer.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        if (data.hasLearned(spell.id())) {
            serverPlayer.displayClientMessage(
                    Component.translatable("qianxiang.scroll.already_learned"), true);
            return InteractionResultHolder.fail(stack);
        }

        serverPlayer.setData(QianxiangAttachments.PLAYER_SPELL_DATA, data.learn(spell));
        stack.shrink(1);
        // 让客户端已学列表立刻可见（HUD/轮盘读同步包里的 learnedSpells）。
        SpellCastHandler.sync(serverPlayer);
        com.qianxiang.QianxiangAdvancements.grant(serverPlayer,
                com.qianxiang.QianxiangAdvancements.FIRST_SCROLL);
        serverPlayer.displayClientMessage(
                Component.translatable("qianxiang.scroll.learned", spell.displayName()), true);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CustomSpell spell = stack.get(QianxiangDataComponents.CUSTOM_SPELL.get());
        if (spell == null) {
            tooltip.add(Component.translatable("qianxiang.scroll.no_spell").withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(spell.displayName().copy().withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("qianxiang.scroll.stats",
                CustomSpell.elementName(spell.element()),
                CustomSpell.formName(spell.form()),
                CustomSpell.effectName(spell.effect()),
                spell.power(),
                spell.manaCost(),
                String.format("%.1f", spell.cooldownTicks() / 20.0)).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("qianxiang.scroll.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
