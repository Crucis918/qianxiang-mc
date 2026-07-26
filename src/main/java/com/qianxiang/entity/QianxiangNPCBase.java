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
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * 千相 NPC 基础类 —— MVP 直接继承原版 {@link Villager}，获得 Villager-like AI 与渲染，
 * 同时覆盖交互逻辑以触发外交烙印、称号与对话。
 */
public abstract class QianxiangNPCBase extends Villager {
    private static final Logger LOGGER = Qianxiang.LOGGER;

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
        // 跳过原版交易 GUI，改为千相对话/外交计数
        if (!level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
            handleInteraction(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }

    /** 子类提供自己的对话 key 前缀，如 {@code qianxiang.npc.wandering_sage}。 */
    protected abstract String getDialogPrefix();

    /** 子类提供交易价格提示 key。 */
    protected abstract String getPriceHintKey();

    protected void handleInteraction(ServerPlayer player) {
        try {
            PlayerFactionData before = player.getData(QianxiangAttachments.FACTION_DATA);
            PlayerFactionData after = before.withDiplomacy(1).updateTitle();
            player.setData(QianxiangAttachments.FACTION_DATA, after);

            // 相谱录追加交易记录
            SagaData saga = player.getData(QianxiangAttachments.SAGA_DATA);
            String npcName = Component.translatable(getType().getDescriptionId()).getString();
            player.setData(QianxiangAttachments.SAGA_DATA,
                    saga.withEntry("§7[" + npcName + "] §r外交烙印 +1，当前好感度 " + after.reputation()));

            // 根据烙印/好感发送不同对话
            String brand = after.getDominantBrand();
            String dialogKey = getDialogPrefix() + ".dialog." + brand;
            player.sendSystemMessage(Component.translatable(dialogKey));

            // 简单价格提示（MVP 仅聊天提示，不涉及真实交易价格修改）
            if (after.reputation() >= 20) {
                player.sendSystemMessage(Component.translatable(getPriceHintKey() + ".discount"));
            } else if (after.reputation() <= -20) {
                player.sendSystemMessage(Component.translatable(getPriceHintKey() + ".premium"));
            } else {
                player.sendSystemMessage(Component.translatable(getPriceHintKey() + ".normal"));
            }
        } catch (Exception e) {
            LOGGER.error("[Qianxiang] NPC interaction failed", e);
        }
    }
}
