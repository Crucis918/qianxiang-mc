package com.qianxiang.entity;

import com.qianxiang.QianxiangItems;
import com.qianxiang.QianxiangMaterials;
import com.qianxiang.phase.PhaseFunctionResolver;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.network.chat.Component;
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
 * 深渊商人 —— 来自沉潜裂隙的异相交易者。
 *
 * <p>卖「暗面」的相材料（暗影/剧毒/深渊系）与裂隙硬通货（裂隙精髓、逆相之核），
 * 收购龙骨。定位：中后期贵价黑市——原材料比相师那边阴，但硬货只有这里有现成的。
 *
 * <p><b>黄金经济</b>：本店以金粒计价（金粒是通行全游戏的中和硬币，
 * 汇率 1 绿宝石 = 4 金粒），另设通用「卖料换金」——潜行+右键把主手持有的
 * 任意带相材料卖给他，按材料档位付金粒（每 MC 日每玩家限 {@value #DAILY_SELL_LIMIT} 次）。
 */
public class QianxiangAbyssMerchant extends QianxiangNPCBase {

    /** 「卖料换金」每 MC 日每玩家的收购上限（次）。 */
    public static final int DAILY_SELL_LIMIT = 8;

    /** 收购计次的 PersistentData 键（日号 + 当日已用次数）。 */
    private static final String SELL_DAY_KEY = "qianxiang:gold_sell_day";
    private static final String SELL_COUNT_KEY = "qianxiang:gold_sell_count";

    public QianxiangAbyssMerchant(EntityType<? extends QianxiangAbyssMerchant> type, Level level) {
        super(type, level);
    }

    @Override
    protected void populateTrades(MerchantOffers offers) {
        buildOffers(offers);
    }

    /** 商品表（金粒计价；独立成静态公开方法供 GameTest 断言契约）。 */
    public static void buildOffers(MerchantOffers offers) {
        // —— 卖暗系材料（金粒 → 相材料；汇率 1 绿宝石 = 4 金粒）——
        offers.add(new MerchantOffer(new ItemCost(Items.GOLD_NUGGET, 20),
                new ItemStack(QianxiangMaterials.SHADOWHIDE_PATCH.get(), 2), 12, 2, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.GOLD_NUGGET, 24),
                new ItemStack(QianxiangMaterials.SHADOW_DUST.get(), 2), 10, 3, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.GOLD_NUGGET, 28),
                new ItemStack(QianxiangMaterials.VENOM_GLAND.get()), 8, 3, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.GOLD_NUGGET, 28),
                new ItemStack(QianxiangMaterials.ABYSS_IRON.get()), 8, 3, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.GOLD_NUGGET, 32),
                new ItemStack(QianxiangMaterials.BLOODROOT.get(), 2), 8, 3, 0.05F));
        // —— 裂隙硬通货（贵，但省去自己合成/远征）——
        offers.add(new MerchantOffer(new ItemCost(Items.GOLD_NUGGET, 72),
                new ItemStack(QianxiangMaterials.RIFT_ESSENCE.get()), 4, 8, 0.05F));
        offers.add(new MerchantOffer(new ItemCost(Items.GOLD_NUGGET, 160), Optional.of(new ItemCost(Items.ENDER_EYE, 1)),
                new ItemStack(QianxiangItems.REVERSE_CORE.get()), 2, 15, 0.05F));
        // —— 收购（龙骨 → 金粒）——
        offers.add(new MerchantOffer(new ItemCost(QianxiangMaterials.DRAGON_BONE.get(), 1),
                new ItemStack(Items.GOLD_NUGGET, 40), 8, 5, 0.05F));
    }

    /** 深渊欣赏强者：屠杀烙印占优的玩家享额外 8% 折扣——与相师价值观相反。 */
    @Override
    protected int brandBias(com.qianxiang.cap.PlayerFactionData data) {
        return "slaughter".equals(data.getDominantBrand()) ? 8 : 0;
    }

    @Override
    protected String getDialogPrefix() {
        return "qianxiang.npc.abyss_merchant";
    }

    @Override
    protected String getPriceHintKey() {
        return "qianxiang.npc.abyss_merchant.price";
    }

    // ============================ 通用「卖料换金」（潜行+右键） ============================

    /** 收购价（金粒/件）：按材料强度档付金——COMMON 1 / RARE 3 / EPIC 6 / LEGENDARY 12。 */
    public static int buybackPrice(PhaseTier tier) {
        return switch (tier) {
            case COMMON -> 1;
            case RARE -> 3;
            case EPIC -> 6;
            case LEGENDARY -> 12;
        };
    }

    /**
     * 通用收购：玩家主手持有任意带相材料 → 收 1 件、按 {@link #buybackPrice} 付金粒，
     * 每 MC 日每玩家限 {@value #DAILY_SELL_LIMIT} 次（PersistentData 记日号+次数，零新 attachment 基建）。
     * 主手不是相材料 / 当日已满时给提示，返回 false。
     */
    public static boolean trySellMaterial(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !PhaseFunctionResolver.isMaterial(held)) {
            player.sendSystemMessage(Component.translatable(
                    "qianxiang.npc.abyss_merchant.buy.not_material"));
            return false;
        }
        long day = player.level().getDayTime() / 24000L;
        var data = player.getPersistentData();
        if (data.getLong(SELL_DAY_KEY) != day) {
            data.putLong(SELL_DAY_KEY, day);
            data.putInt(SELL_COUNT_KEY, 0);
        }
        int used = data.getInt(SELL_COUNT_KEY);
        if (used >= DAILY_SELL_LIMIT) {
            player.sendSystemMessage(Component.translatable(
                    "qianxiang.npc.abyss_merchant.buy.limit", DAILY_SELL_LIMIT));
            return false;
        }
        int pay = buybackPrice(PhaseFunctionResolver.resolveTier(held));
        held.shrink(1);
        player.getInventory().placeItemBackInInventory(new ItemStack(Items.GOLD_NUGGET, pay));
        data.putInt(SELL_COUNT_KEY, used + 1);
        player.sendSystemMessage(Component.translatable(
                "qianxiang.npc.abyss_merchant.buy.done", pay));
        return true;
    }

    /** 潜行+右键 = 卖料换金（与普通交易界面互不抢占）。 */
    @Override
    protected boolean onSneakInteract(ServerPlayer player) {
        trySellMaterial(player);
        return true;
    }
}
