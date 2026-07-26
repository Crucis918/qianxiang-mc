package com.qianxiang.menu;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.QianxiangMenus;
import com.qianxiang.block.ForgeTableBlockEntity;
import com.qianxiang.blueprint.BlueprintData;
import com.qianxiang.cap.PlayerSpellData;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.cap.SagaData;
import com.qianxiang.phase.ForgeComposer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义台容器菜单。
 * 槽位：0-9 材料（玩家放，2 行 5 列），10 结果（只读），之后是玩家背包。
 * 合成逻辑：材料槽变化时，收集材料栈 → {@link ForgeComposer#compose} 动态组合 → 填结果槽。
 * 取出结果时消耗每个材料槽 1 个。
 * 动态组合：原版物品（铁锭/煤炭等）也能当零件——万物皆零件，强度靠材料稀有度。
 */
public class ForgeTableMenu extends AbstractContainerMenu {
    public static final int MATERIAL_SLOTS = 10;
    public static final int RESULT_SLOT = 10;

    private final Container container;
    private final Inventory playerInventory;

    public ForgeTableMenu(int containerId, Inventory playerInventory, Container container) {
        super(QianxiangMenus.FORGE_TABLE.get(), containerId);
        this.container = container;
        this.playerInventory = playerInventory;
        checkContainerSize(container, 11);
        // 材料槽 0-9：2 行 5 列，与 forge_table.png 左上凹槽对齐（间距 18）
        //   第 1 行 y=17：x = 8/26/44/62/80；第 2 行 y=35：同 x
        for (int i = 0; i < MATERIAL_SLOTS; i++) {
            addSlot(new Slot(container, i, 8 + (i % 5) * 18, 17 + (i / 5) * 18));
        }
        // 结果槽 10：只读（不可放入），取出时消耗材料 + 记相谱。坐标 (222,54) 对齐纹理右侧 32×32 凹槽中心。
        addSlot(new Slot(container, RESULT_SLOT, 222, 54) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public void onTake(Player player, ItemStack stack) { ForgeTableMenu.this.onTakeResult(player, stack); }
        });
        addPlayerInventory(playerInventory);
        slotsChanged(container);
    }

    public ForgeTableMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(11));
    }

    /** 暴露底层容器，供网络包在服务端直接操作材料槽。 */
    public Container getContainer() {
        return container;
    }

    /**
     * 把蓝图中的材料从玩家背包放入锻造台材料槽（仅放入空槽）。
     *
     * @return 是否所有材料都成功放入
     */
    public boolean applyBlueprint(BlueprintData blueprint) {
        int nextSlot = 0;
        for (String name : blueprint.materials()) {
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id == null) return false;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) return false;

            int placeSlot = -1;
            for (int i = nextSlot; i < MATERIAL_SLOTS; i++) {
                if (container.getItem(i).isEmpty()) {
                    placeSlot = i;
                    nextSlot = i + 1;
                    break;
                }
            }
            if (placeSlot < 0) return false;

            int found = -1;
            for (int i = 0; i < playerInventory.getContainerSize(); i++) {
                ItemStack s = playerInventory.getItem(i);
                if (!s.isEmpty() && s.is(item)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) return false;

            ItemStack taken = playerInventory.getItem(found);
            taken.shrink(1);
            if (taken.isEmpty()) {
                playerInventory.setItem(found, ItemStack.EMPTY);
            } else {
                playerInventory.setItem(found, taken);
            }
            container.setItem(placeSlot, new ItemStack(item, 1));
        }
        // 蓝图中保存的 spellJson/movesetJson 一并重新应用（无对应 JSON 的旧蓝图则清除暂存，
        // 避免上一次 AI 响应的法术/动作串味到蓝图产物）。蓝图名作为自定义名恢复。
        if (container instanceof ForgeTableBlockEntity be) {
            String sj = blueprint.spellJson();
            be.setLastAiSpell(sj == null ? "" : sj, sj == null ? "" : blueprint.name());
            String mj = blueprint.movesetJson();
            be.setLastAiMoveset(mj == null ? "" : mj);
        }
        slotsChanged(container);
        return true;
    }

    @Override
    public void slotsChanged(Container container) {
        // 收集材料槽内容 → 动态组合 → 结果槽放产物栈（可能为 EMPTY）。
        // hover 结果槽时 QianxiangWeaponItem 自动显示 powerScore/攻击力/特殊效果——概念期强度预览免费获得。
        // 服务端方块实体上暂存的最近一次 AI 输出（spellJson/自定义名）一并参与组合；
        // 客户端菜单容器是 SimpleContainer，自然走无 AI 覆盖的旧入口。
        List<ItemStack> materials = new ArrayList<>(MATERIAL_SLOTS);
        for (int i = 0; i < MATERIAL_SLOTS; i++) {
            materials.add(this.container.getItem(i));
        }
        ForgeComposer.Composition composition;
        if (this.container instanceof ForgeTableBlockEntity be) {
            composition = ForgeComposer.compose(materials, be.getLastSpellJson(), be.getLastCustomName(),
                    be.getLastMovesetJson());
        } else {
            composition = ForgeComposer.compose(materials);
        }
        this.container.setItem(RESULT_SLOT, composition.result());

        if (this.container instanceof ForgeTableBlockEntity be) {
            be.updateCraftingState();
        }
    }

    private void onTakeResult(Player player, ItemStack resultStack) {
        recordForge(player, resultStack);  // 律二：锻造即传记，不可逆烙进相谱
        learnSpellFromResult(player, resultStack); // 相杖上的法术自动进入已学列表
        for (int i = 0; i < MATERIAL_SLOTS; i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty()) { s.shrink(1); container.setItem(i, s); }
        }
        slotsChanged(container);

        if (this.container instanceof ForgeTableBlockEntity be) {
            be.setComplete();
        }
    }

    /** 如果产物铭刻了法术，让玩家学会它（不消耗，用于施法环/面板）。 */
    private void learnSpellFromResult(Player player, ItemStack resultStack) {
        if (resultStack.isEmpty()) return;
        ResourceLocation spellId = resultStack.get(QianxiangDataComponents.SPELL.get());
        if (spellId == null) return;
        try {
            PlayerSpellData data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
            player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, data.learn(spellId));
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 记录已学法术失败（不阻断合成）", t);
        }
    }

    /** 把这次锻造记进玩家相谱 + 位格 +1（律二不可逆铭刻）。失败不阻断合成。 */
    private void recordForge(Player player, ItemStack resultStack) {
        if (resultStack.isEmpty()) return;
        try {
            var attr = resultStack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
            double power = attr != null ? attr.powerScore() : 0.0;
            String name = resultStack.getHoverName().getString();
            String entry = String.format("以相之料锻得「%s」，强度 %.1f", name, power);
            SagaData saga = player.getData(QianxiangAttachments.SAGA_DATA);
            player.setData(QianxiangAttachments.SAGA_DATA, saga.withEntry(entry).withBumpedPosition(1));
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 记录相谱失败（不阻断合成）", t);
        }
    }

    private void addPlayerInventory(Inventory inv) {
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++)
                addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 176 + r * 18));
        for (int c = 0; c < 9; c++)
            addSlot(new Slot(inv, c, 8 + c * 18, 234));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        int invStart = RESULT_SLOT + 1;      // 背包区起点（含快捷栏）
        int invEnd = invStart + 36;

        ItemStack stack = slot.getItem();
        ItemStack moved = stack.copy();

        if (index == RESULT_SLOT) {
            // 结果 → 背包。搬完后用完整拷贝触发 onTake（消耗材料 + 相谱 + 学法术）；
            // 原版 QUICK_MOVE 会循环调用本方法，材料够就连续锻造，背包满/材料尽自动停。
            if (!moveItemStackTo(stack, invStart, invEnd, true)) return ItemStack.EMPTY;
            slot.set(ItemStack.EMPTY);
            slot.onTake(player, moved);
            return moved;
        }

        if (index < MATERIAL_SLOTS) {
            // 材料槽 → 背包
            if (!moveItemStackTo(stack, invStart, invEnd, false)) return ItemStack.EMPTY;
        } else {
            // 背包 → 材料槽
            if (!moveItemStackTo(stack, 0, MATERIAL_SLOTS, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == moved.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        slotsChanged(this.container);
        return moved;
    }

    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ClickType clickType, Player player) {
        super.clicked(slotId, button, clickType, player);
        // 方块实体容器不会像 TransientCraftingContainer 那样回调菜单，
        // 手动拖拽/丢弃材料后必须主动重算结果槽（重算是幂等的，多调无害）。
        slotsChanged(this.container);
    }

    @Override
    public void removed(Player player) {
        Qianxiang.LOGGER.info("[Qianxiang] ForgeTableMenu.removed 被调用（容器关闭），containerId={}，玩家={}",
                this.containerId, player.getName().getString(), new Throwable("[Qianxiang] 容器关闭调用栈"));
        super.removed(player);
    }

    private int stillValidFailLogCooldown = 0;

    @Override
    public boolean stillValid(Player player) {
        boolean valid = container.stillValid(player);
        if (!valid && !player.level().isClientSide && stillValidFailLogCooldown-- <= 0) {
            // 服务端每 tick 轮询 stillValid，返回 false 会立即 closeContainer()——记录原因便于定位自动关闭
            stillValidFailLogCooldown = 20;
            String detail = "未知";
            if (container instanceof ForgeTableBlockEntity be) {
                var level = be.getLevel();
                var pos = be.getBlockPos();
                boolean sameBe = level != null && level.getBlockEntity(pos) == be;
                detail = "level=" + (level == null ? "null" : level.dimension().location())
                        + " pos=" + pos
                        + " 方块实体为同一实例=" + sameBe
                        + " 玩家距离²=" + String.format("%.2f", player.distanceToSqr(
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5))
                        + " canInteractWithBlock=" + (level != null && player.canInteractWithBlock(pos, 4.0));
            }
            Qianxiang.LOGGER.info("[Qianxiang] ForgeTableMenu.stillValid 返回 false（服务端将关闭容器）：{}", detail);
        }
        return valid;
    }
}
