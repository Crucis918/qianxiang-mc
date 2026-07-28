package com.qianxiang.entity;

import com.qianxiang.QianxiangItems;
import com.qianxiang.QianxiangMaterials;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * 流浪相师 —— 游荡于主世界的千相知识持有者。
 *
 * <p>卖「明面」的相材料与法术知识（自然/光/冰系 + 法术书），收购万象森罗的残片。
 * 定位：生存前中期的材料补给站 + 残片/裂片的绿宝石回收口。
 */
public class QianxiangWanderingSage extends QianxiangNPCBase {
    public QianxiangWanderingSage(EntityType<? extends QianxiangWanderingSage> type, Level level) {
        super(type, level);
    }

    @Override
    protected void populateTrades(MerchantOffers offers) {
        // —— 卖材料（绿宝石 → 相材料）——
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 4),
                new ItemStack(QianxiangItems.EMBER_CRYSTAL.get(), 2), 12, 2, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 5),
                new ItemStack(QianxiangMaterials.GLIMMER_WOOD_SAP.get(), 3), 12, 2, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 6),
                new ItemStack(QianxiangMaterials.NATURE_BREATH.get()), 8, 3, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 6),
                new ItemStack(QianxiangMaterials.FROST_CRYSTAL.get()), 8, 3, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 10),
                new ItemStack(QianxiangMaterials.HOLY_SHARD.get()), 6, 5, 0.05F));
        // —— 卖知识（书 + 绿宝石 → 法术书）——
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 14), Optional.of(new ItemCost(Items.BOOK, 1)),
                new ItemStack(QianxiangItems.SPELL_BOOK.get()), 3, 10, 0.05F));
        // —— 收购（维度产物 → 绿宝石）——
        offers.add(new MerchantOffer(new ItemCost(QianxiangItems.MYRIAD_FRAGMENT.get(), 2),
                new ItemStack(Items.EMERALD, 5), 12, 3, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(QianxiangMaterials.VOID_SHARD.get(), 1),
                new ItemStack(Items.EMERALD, 12), 8, 5, 0.05F));
    }

    /** 相师厌恶杀戮：屠杀烙印占优的玩家加价 10%，外交烙印享额外 5% 折扣。 */
    @Override
    protected int brandBias(com.qianxiang.cap.PlayerFactionData data) {
        return switch (data.getDominantBrand()) {
            case "slaughter" -> -10;
            case "diplomacy" -> 5;
            default -> 0;
        };
    }

    @Override
    protected String getDialogPrefix() {
        return "qianxiang.npc.wandering_sage";
    }

    @Override
    protected String getPriceHintKey() {
        return "qianxiang.npc.wandering_sage.price";
    }

    // ============================ 熟练度：开启 / 开树（潜行+右键） ============================

    /** 开启确认窗：玩家 UUID → 首次提示时刻（5 秒内再次潜行右键即确认开启）。 */
    private static final java.util.Map<java.util.UUID, Long> UNLOCK_CONFIRM_WINDOW =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long UNLOCK_CONFIRM_MS = 5_000L;

    @Override
    protected boolean onSneakInteract(ServerPlayer player) {
        var data = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        if (!data.unlocked()) {
            // 未开启：5 秒确认窗 —— 首击提示，窗内再击确认开启
            Long first = UNLOCK_CONFIRM_WINDOW.get(player.getUUID());
            long now = System.currentTimeMillis();
            if (first != null && now - first < UNLOCK_CONFIRM_MS) {
                UNLOCK_CONFIRM_WINDOW.remove(player.getUUID());
                com.qianxiang.cap.ProficiencyHelper.unlock(player);
                var saga = player.getData(com.qianxiang.cap.QianxiangAttachments.SAGA_DATA);
                player.setData(com.qianxiang.cap.QianxiangAttachments.SAGA_DATA,
                        saga.withEntry("§d[修行] §r拜相师开启修行之路"));
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "qianxiang.proficiency.sage.congrats"));
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player, new com.qianxiang.network.OpenSkillTreePayload());
            } else {
                UNLOCK_CONFIRM_WINDOW.put(player.getUUID(), now);
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "qianxiang.proficiency.sage.offer"));
            }
            return true;
        }
        // 已开启：短对话 + 打开技能树
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "qianxiang.proficiency.sage.open_tree"));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new com.qianxiang.network.OpenSkillTreePayload());
        return true;
    }
}
