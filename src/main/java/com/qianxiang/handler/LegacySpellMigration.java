package com.qianxiang.handler;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.cap.PlayerSpellData;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.spell.CustomSpell;
import com.qianxiang.spell.SpellBookData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 旧存档迁移：已学法术升级为存完整 {@link CustomSpell} 后，
 * 玩家背包里仍带法术组件的旧物品（相杖/法术书）就是唯一的法术数据来源。
 * <p>
 * 登录时扫一遍背包（主物品栏 + 盔甲槽 + 副手），把带
 * {@code custom_spell}/{@code spellbook} 组件物品里的全部法术 learn 进
 * {@link QianxiangAttachments#PLAYER_SPELL_DATA}，然后把这两个组件从物品上剥掉
 * —— 法术改由「已学列表」承载，物品回归纯净，避免同一法术被重复迁移、
 * 也避免旧物品继续暗示「法术长在物品上」。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class LegacySpellMigration {

    private LegacySpellMigration() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        PlayerSpellData data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        int learnedCount = 0;

        var inventory = player.getInventory();
        // items = 主物品栏，armor = 盔甲槽，offhand = 副手；同一 ItemStack 引用，原地改组件即生效。
        for (var stacks : java.util.List.of(inventory.items, inventory.armor, inventory.offhand)) {
            for (ItemStack stack : stacks) {
                if (stack.isEmpty()) continue;
                boolean touched = false;

                CustomSpell custom = stack.get(QianxiangDataComponents.CUSTOM_SPELL.get());
                if (custom != null) {
                    data = data.learn(custom);
                    learnedCount++;
                    touched = true;
                }
                SpellBookData book = stack.get(QianxiangDataComponents.SPELLBOOK.get());
                if (book != null) {
                    for (CustomSpell spell : book.spells()) {
                        data = data.learn(spell);
                        learnedCount++;
                    }
                    touched = true;
                }
                if (!touched) continue;

                // 剥掉法术组件（set(type, null) = 移除），旧物品变纯净。
                stack.set(QianxiangDataComponents.CUSTOM_SPELL.get(), null);
                stack.set(QianxiangDataComponents.SPELLBOOK.get(), null);
            }
        }

        if (learnedCount > 0) {
            player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, data);
            player.displayClientMessage(
                    Component.translatable("qianxiang.spell.migration.learned", learnedCount), false);
            Qianxiang.LOGGER.info("[Qianxiang] 旧存档法术迁移：玩家 {} 从物品学会 {} 个法术。",
                    player.getGameProfile().getName(), learnedCount);
        }
    }
}
