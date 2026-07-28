package com.qianxiang.blueprint;

import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.cap.SagaData;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.BlueprintListRequestPayload;
import com.qianxiang.network.BlueprintSavePayload;
import com.qianxiang.network.BlueprintSyncPayload;
import com.qianxiang.network.BlueprintUsePayload;
import com.qianxiang.phase.ForgeComposer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端处理蓝图相关网络包。
 */
public final class BlueprintServerHandler {

    private BlueprintServerHandler() {}

    /** 蓝图操作的最小间隔：每次都要整库序列化回包，是廉价的放大面。 */
    private static final long BLUEPRINT_COOLDOWN_MS = 500L;

    public static void handleSave(BlueprintSavePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player.containerMenu instanceof ForgeTableMenu menu)) return;
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "blueprint_save", BLUEPRINT_COOLDOWN_MS)) return;

            List<ItemStack> materials = new ArrayList<>(ForgeTableMenu.MATERIAL_SLOTS);
            for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
                materials.add(menu.getSlot(i).getItem());
            }
            ForgeComposer.Composition composition = ForgeComposer.compose(materials);
            if (!composition.valid()) {
                player.sendSystemMessage(Component.translatable("qianxiang.blueprint.save.invalid"));
                context.reply(syncPayload(player));
                return;
            }

            // 蓝图保存时把锻造台上暂存的 AI spellJson/movesetJson 一并存入（使用蓝图时重新应用）。
            String spellJson = null;
            String movesetJson = null;
            if (menu.getContainer() instanceof com.qianxiang.block.ForgeTableBlockEntity be) {
                // 读「这个玩家自己的」选择而非方块级单份暂存——
                // 后者在多人同用一台锻造台时会把别人的 AI 法术存进自己的蓝图。
                var sel = be.selectionOf(player.getUUID());
                spellJson = sel.spellJson();
                movesetJson = sel.movesetJson();
            }
            BlueprintData data = BlueprintData.fromComposition(composition, materials, spellJson, movesetJson);
            BlueprintLibrary library = player.getData(QianxiangAttachments.BLUEPRINT_LIBRARY);
            // 蓝图保存位：基础 8 + lore 节点 +4（满则拒存）
            int maxSlots = com.qianxiang.cap.ProficiencyHelper.BASE_BLUEPRINT_SLOTS
                    + com.qianxiang.cap.ProficiencyHelper.blueprintBonusSlots(
                            (net.minecraft.server.level.ServerPlayer) player);
            if (library.blueprints().size() >= maxSlots) {
                player.sendSystemMessage(Component.translatable("qianxiang.blueprint.save.full"));
                context.reply(syncPayload(player));
                return;
            }
            player.setData(QianxiangAttachments.BLUEPRINT_LIBRARY, library.withAdded(data));

            SagaData saga = player.getData(QianxiangAttachments.SAGA_DATA);
            String entry = String.format("铭刻千相蓝图「%s」，强度 %.1f，材料 %s",
                    data.name(), data.power(), String.join(", ", data.materials()));
            player.setData(QianxiangAttachments.SAGA_DATA, saga.withEntry(entry));

            player.sendSystemMessage(Component.translatable("qianxiang.blueprint.save.success", data.name()));
            context.reply(syncPayload(player));
        });
    }

    public static void handleUse(BlueprintUsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            BlueprintLibrary library = player.getData(QianxiangAttachments.BLUEPRINT_LIBRARY);
            if (payload.index() < 0 || payload.index() >= library.blueprints().size()) {
                context.reply(syncPayload(player));
                return;
            }
            BlueprintData data = library.blueprints().get(payload.index());
            if (!(player.containerMenu instanceof ForgeTableMenu menu)) {
                context.reply(syncPayload(player));
                return;
            }
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "blueprint_use", BLUEPRINT_COOLDOWN_MS)) {
                return;
            }

            boolean ok = menu.applyBlueprint(data);

            if (ok) {
                // 只有真正铺料成功才写相谱——此前无条件写，缺材料连点「使用」
                // 会用垃圾条目把 500 条上限挤满，真历史被挤进不可恢复的 forgotten。
                SagaData saga = player.getData(QianxiangAttachments.SAGA_DATA);
                String entry = String.format("循蓝图「%s」重铸相器", data.name());
                player.setData(QianxiangAttachments.SAGA_DATA, saga.withEntry(entry));
                player.sendSystemMessage(Component.translatable("qianxiang.blueprint.use.success", data.name()));
            } else {
                player.sendSystemMessage(Component.translatable("qianxiang.blueprint.use.missing", data.name()));
            }
            context.reply(syncPayload(player));
        });
    }

    public static void handleListRequest(BlueprintListRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> context.reply(syncPayload(context.player())));
    }

    private static BlueprintSyncPayload syncPayload(Player player) {
        return new BlueprintSyncPayload(player.getData(QianxiangAttachments.BLUEPRINT_LIBRARY).blueprints());
    }
}
