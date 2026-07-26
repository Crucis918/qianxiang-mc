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
 * 深渊商人 —— 来自沉潜裂隙的异相交易者。
 *
 * <p>卖「暗面」的相材料（暗影/剧毒/深渊系）与裂隙硬通货（裂隙精髓、逆相之核），
 * 收购龙骨。定位：中后期贵价黑市——原材料比相师那边阴，但硬货只有这里有现成的。
 */
public class QianxiangAbyssMerchant extends QianxiangNPCBase {
    public QianxiangAbyssMerchant(EntityType<? extends QianxiangAbyssMerchant> type, Level level) {
        super(type, level);
    }

    @Override
    protected void populateTrades(MerchantOffers offers) {
        // —— 卖暗系材料（绿宝石 → 相材料）——
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 5),
                new ItemStack(QianxiangMaterials.SHADOWHIDE_PATCH.get(), 2), 12, 2, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 6),
                new ItemStack(QianxiangMaterials.SHADOW_DUST.get(), 2), 10, 3, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 7),
                new ItemStack(QianxiangMaterials.VENOM_GLAND.get()), 8, 3, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 7),
                new ItemStack(QianxiangMaterials.ABYSS_IRON.get()), 8, 3, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 8),
                new ItemStack(QianxiangMaterials.BLOODROOT.get(), 2), 8, 3, 0.05F));
        // —— 裂隙硬通货（贵，但省去自己合成/远征）——
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 18),
                new ItemStack(QianxiangMaterials.RIFT_ESSENCE.get()), 4, 8, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 40), Optional.of(new ItemCost(Items.ENDER_EYE, 1)),
                new ItemStack(QianxiangItems.REVERSE_CORE.get()), 2, 15, 0.05F));
        // —— 收购（龙骨 → 绿宝石）——
        offers.add(new MerchantOffer(new ItemCost(QianxiangMaterials.DRAGON_BONE.get(), 1),
                new ItemStack(Items.EMERALD, 10), 8, 5, 0.05F));
    }

    @Override
    protected String getDialogPrefix() {
        return "qianxiang.npc.abyss_merchant";
    }

    @Override
    protected String getPriceHintKey() {
        return "qianxiang.npc.abyss_merchant.price";
    }
}
