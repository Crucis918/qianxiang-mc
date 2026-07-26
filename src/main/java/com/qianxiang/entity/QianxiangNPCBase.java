package com.qianxiang.entity;

import com.qianxiang.Qianxiang;
import com.qianxiang.cap.PlayerFactionData;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.cap.SagaData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * 千相 NPC 基础类 —— 继承原版 {@link Villager} 获得 Villager-like AI 与渲染，
 * 在其上实现千相自己的交易与外交：
 * <ul>
 *   <li>右键：打招呼（按烙印选对话）+ 打开<b>真实交易界面</b>（原版 Merchant GUI）。</li>
 *   <li>潜行 + 右键：只对话，不开交易（保留旧 MVP 的纯对话路径）。</li>
 *   <li>定价：按玩家势力好感度动态折扣/加价（specialPriceDiff，±25% 封顶）——
 *       声望不再只是聊天提示，而是真金白银。</li>
 *   <li>好感度：<b>完成交易</b>才 +1 外交烙印并记相谱（右键刷好感的老路废弃）。</li>
 *   <li>补货：距上次补货超过一个 MC 日（24000 tick）时重置交易次数。</li>
 * </ul>
 * 商品表由子类 {@link #populateTrades(MerchantOffers)} 提供，随实体 NBT 持久化（AbstractVillager 自带）。
 */
public abstract class QianxiangNPCBase extends Villager {
    private static final Logger LOGGER = Qianxiang.LOGGER;

    /** 声望折扣封顶（百分比）：好感 +25 → 75 折，-25 → 加价 25%。 */
    private static final int MAX_DISCOUNT_PCT = 25;

    /** 补货间隔：一个 MC 日。 */
    private static final long RESTOCK_INTERVAL_TICKS = 24000L;

    /** 上次补货的游戏时间（不落盘：重载后视为可补货，无害）。 */
    private long lastRestockGameTime = Long.MIN_VALUE;

    protected QianxiangNPCBase(EntityType<? extends Villager> type, Level level) {
        super(type, level);
    }

    /** 千相 NPC 默认属性：以村民为基底，保证 MAX_HEALTH 等核心属性存在。 */
    public static AttributeSupplier.Builder createBaseAttributes() {
        return Villager.createAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.5);
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !isAlive() || isSleeping() || isTrading()) {
            return InteractionResult.PASS;
        }

        // 潜行 + 右键：只对话（旧 MVP 路径），不开交易
        if (player.isSecondaryUseActive()) {
            sendGreeting(serverPlayer);
            return InteractionResult.SUCCESS;
        }

        try {
            sendGreeting(serverPlayer);
            MerchantOffers offers = getOffers();
            if (offers.isEmpty()) {
                populateTrades(offers);
            }
            maybeRestock();
            applyReputationPricing(serverPlayer, offers);
            setTradingPlayer(serverPlayer);
            openTradingScreen(serverPlayer, getDisplayName(), 1);
        } catch (Exception e) {
            LOGGER.error("[Qianxiang] NPC 交易开启失败", e);
        }
        return InteractionResult.SUCCESS;
    }

    /** 完成一笔交易：外交烙印 +1（好感随真实交易增长）+ 相谱铭刻。 */
    @Override
    public void notifyTrade(MerchantOffer offer) {
        super.notifyTrade(offer);
        if (!(getTradingPlayer() instanceof ServerPlayer player)) {
            return;
        }
        try {
            PlayerFactionData before = player.getData(QianxiangAttachments.FACTION_DATA);
            PlayerFactionData after = before.withDiplomacy(1).updateTitle();
            player.setData(QianxiangAttachments.FACTION_DATA, after);

            SagaData saga = player.getData(QianxiangAttachments.SAGA_DATA);
            String npcName = Component.translatable(getType().getDescriptionId()).getString();
            String itemName = offer.getResult().getHoverName().getString();
            player.setData(QianxiangAttachments.SAGA_DATA,
                    saga.withEntry("§7[" + npcName + "] §r成交「" + itemName + "」，好感度 " + after.reputation()));
        } catch (Exception e) {
            LOGGER.error("[Qianxiang] NPC 交易记账失败", e);
        }
    }

    /** 子类填充自己的商品表（首次交互时调用一次，随实体 NBT 持久化）。 */
    protected abstract void populateTrades(MerchantOffers offers);

    /** 子类提供自己的对话 key 前缀，如 {@code qianxiang.npc.wandering_sage}。 */
    protected abstract String getDialogPrefix();

    /** 子类提供交易价格提示 key。 */
    protected abstract String getPriceHintKey();

    /** 按烙印发一句对话 + 价格档提示（不改任何数据）。 */
    private void sendGreeting(ServerPlayer player) {
        try {
            PlayerFactionData data = player.getData(QianxiangAttachments.FACTION_DATA);
            player.sendSystemMessage(Component.translatable(
                    getDialogPrefix() + ".dialog." + data.getDominantBrand()));
            int pct = discountPercent(data);
            if (pct > 0) {
                player.sendSystemMessage(Component.translatable(getPriceHintKey() + ".discount"));
            } else if (pct < 0) {
                player.sendSystemMessage(Component.translatable(getPriceHintKey() + ".premium"));
            } else {
                player.sendSystemMessage(Component.translatable(getPriceHintKey() + ".normal"));
            }
        } catch (Exception e) {
            LOGGER.error("[Qianxiang] NPC 对话失败", e);
        }
    }

    /**
     * 烙印偏好加成（百分比，正 = 额外打折）。子类覆盖以表达价值观：
     * 相师厌恶屠杀，深渊商人欣赏屠夫。默认无偏好。
     */
    protected int brandBias(PlayerFactionData data) {
        return 0;
    }

    /** 声望 + 烙印偏好 → 折扣百分比，clamp 到 ±{@value MAX_DISCOUNT_PCT}。正 = 打折，负 = 加价。 */
    private int discountPercent(PlayerFactionData data) {
        return Math.clamp(data.reputation() + brandBias(data), -MAX_DISCOUNT_PCT, MAX_DISCOUNT_PCT);
    }

    /** 把声望折扣写进每条 offer 的 specialPriceDiff（负值 = 降价）。 */
    private void applyReputationPricing(ServerPlayer player, MerchantOffers offers) {
        int pct;
        try {
            pct = discountPercent(player.getData(QianxiangAttachments.FACTION_DATA));
        } catch (Exception e) {
            pct = 0;
        }
        for (MerchantOffer offer : offers) {
            int base = offer.getBaseCostA().getCount();
            int diff = -Math.round(base * pct / 100.0F);
            // 保证最终价 ≥1（specialPriceDiff 只作用于 costA）
            if (base + diff < 1) {
                diff = 1 - base;
            }
            offer.setSpecialPriceDiff(diff);
        }
    }

    /** 距上次补货超过一个 MC 日则重置所有交易次数。 */
    private void maybeRestock() {
        long now = level().getGameTime();
        if (lastRestockGameTime == Long.MIN_VALUE) {
            lastRestockGameTime = now;
            return;
        }
        if (now - lastRestockGameTime >= RESTOCK_INTERVAL_TICKS) {
            getOffers().forEach(MerchantOffer::resetUses);
            lastRestockGameTime = now;
        }
    }
}
