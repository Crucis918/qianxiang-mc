package com.qianxiang.network;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.combat.AnimationLibrary;
import com.qianxiang.combat.WeaponMoveset;
import com.qianxiang.item.QianxiangWeaponItem;
import com.qianxiang.menu.ForgeTableMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端处理「连击编辑器应用动作」请求。
 * <p>
 * 目标选择：玩家当前打开锻造台且结果槽有产物 → 写结果槽产物；
 * 否则 → 主手的相之武器（或已带 CUSTOM_MOVESET 的物品）。两处都没有则回执提示。
 * </p>
 * <p>
 * 安全边界：combos 逐个过 {@link AnimationLibrary#byId} 白名单（编辑器只能发库内动画，
 * 但网络包不可信），上限与编辑器一致为 6 段；解析/过滤后为空 → 拒绝。
 * 直接构造 {@link WeaponMoveset} 写组件。AI 锻造链路
 * （{@code ForgeComposer.applyAiMoveset}）已统一为同一套语义：
 * <b>逐段拼接、允许重复、上限 {@link #MAX_SEGMENTS} 段</b>。
 * </p>
 */
public final class MovesetApplyHandler {

    /** 与 MovesetEditorScreen 的段数上限一致。 */
    public static final int MAX_SEGMENTS = 6;

    private MovesetApplyHandler() {}

    public static void handle(MovesetApplyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player == null) return;

            WeaponMoveset parsed;
            try {
                parsed = WeaponMoveset.fromJson(payload.movesetJson());
            } catch (Throwable t) {
                parsed = null;
            }
            if (parsed == null) {
                player.displayClientMessage(
                        Component.translatable("qianxiang.moveset_editor.msg.invalid"), true);
                return;
            }

            // 白名单过滤：只保留动画库内的 id，维持顺序、允许重复，上限 6 段
            List<ResourceLocation> combos = new ArrayList<>();
            for (ResourceLocation id : parsed.combos()) {
                if (AnimationLibrary.byId(id) == null) continue;
                combos.add(id);
                if (combos.size() >= MAX_SEGMENTS) break;
            }
            if (combos.isEmpty()) {
                player.displayClientMessage(
                        Component.translatable("qianxiang.moveset_editor.msg.invalid"), true);
                return;
            }
            WeaponMoveset moveset = new WeaponMoveset(parsed.category(), combos, parsed.colliderPreset());

            // 1) 锻造台结果槽产物优先
            AbstractContainerMenu menu = player.containerMenu;
            if (menu instanceof ForgeTableMenu forgeMenu) {
                ItemStack result = forgeMenu.getContainer().getItem(ForgeTableMenu.RESULT_SLOT);
                if (!result.isEmpty()) {
                    result.set(QianxiangDataComponents.CUSTOM_MOVESET.get(), moveset);
                    forgeMenu.getContainer().setItem(ForgeTableMenu.RESULT_SLOT, result);
                    forgeMenu.getContainer().setChanged();
                    player.displayClientMessage(
                            Component.translatable("qianxiang.moveset_editor.msg.applied"), true);
                    return;
                }
            }

            // 2) 主手：相之武器或已带动作组件的物品
            ItemStack hand = player.getMainHandItem();
            if (!hand.isEmpty() && (hand.getItem() instanceof QianxiangWeaponItem
                    || hand.has(QianxiangDataComponents.CUSTOM_MOVESET.get()))) {
                hand.set(QianxiangDataComponents.CUSTOM_MOVESET.get(), moveset);
                player.getInventory().setChanged();
                player.displayClientMessage(
                        Component.translatable("qianxiang.moveset_editor.msg.applied"), true);
                return;
            }

            player.displayClientMessage(
                    Component.translatable("qianxiang.moveset_editor.msg.no_target"), true);
        });
    }
}
