package com.qianxiang.entity;

import com.qianxiang.QianxiangItems;
import com.qianxiang.QianxiangMaterials;
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
}
