package com.qianxiang.menu;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangMenus;
import com.qianxiang.block.AlchemyTableBlockEntity;
import com.qianxiang.cap.SagaData;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.phase.SpellScrollComposer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 炼金台容器菜单。
 * 槽位：0-5 材料（玩家放，2 行 3 列），6 结果（只读），之后是玩家背包。
 * 合成逻辑：材料槽变化时，收集材料栈 → {@link SpellScrollComposer#compose} 组合 → 填结果槽（卷轴预览）。
 * 取出结果时消耗每个材料槽 1 个 + 记相谱；<b>不自动学习</b>——学习靠右键卷轴。
 */
public class AlchemyTableMenu extends AbstractContainerMenu {
    public static final int MATERIAL_SLOTS = 6;
    public static final int RESULT_SLOT = 6;

    /**
     * 投料填充顺序（「填充顺序即布局」）：2×3 网格取索引 2（上行中）为核心位，
     * 其余按序。所有投料路径统一按此序找空槽（与锻造台 SLOT_FILL_ORDER 同一约定）。
     */
    public static final int[] SLOT_FILL_ORDER = {2, 1, 3, 0, 4, 5};

    /** 两次「相谱铭刻 + 位格增长」的最小间隔——防 Shift 连炼一次点击刷满位格（同锻造台）。 */
    private static final long FORGE_SAGA_COOLDOWN_MS = 3_000L;

    /** 上次参与组合的材料 + AI 选择指纹，用于跳过无谓的重算（见 slotsChanged）。 */
    private int lastFingerprint = Integer.MIN_VALUE;

    private final Container container;
    private final Inventory playerInventory;

    public AlchemyTableMenu(int containerId, Inventory playerInventory, Container container) {
        super(QianxiangMenus.ALCHEMY_TABLE.get(), containerId);
        this.container = container;
        this.playerInventory = playerInventory;
        checkContainerSize(container, RESULT_SLOT + 1);
        // 材料槽 0-5：逻辑槽位（compose 只看占用槽不看坐标）。
        // 「去格子化」后坐标挪到屏外——GUI 改文字列表，槽位只作数据模型；
        // quickMoveStack/clicked 按索引工作，不受坐标影响。
        for (int i = 0; i < MATERIAL_SLOTS; i++) {
            addSlot(new Slot(container, i, -2000, -2000));
        }
        // 结果槽：不直接取出——点击/Shift 点击改为触发「合成仪式」（见 clicked/quickMoveStack）。
        addSlot(new Slot(container, RESULT_SLOT, 222, 54) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
        addPlayerInventory(playerInventory);
        slotsChanged(container);
    }

    public AlchemyTableMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(RESULT_SLOT + 1));
    }

    /** 暴露底层容器，供网络包在服务端直接操作材料槽。 */
    public Container getContainer() {
        return container;
    }

    @Override
    public void slotsChanged(Container container) {
        // 收集材料槽内容 → 组合 → 结果槽放卷轴预览（可能为 EMPTY）。
        // 服务端方块实体上按玩家暂存的 AI 选择（spellJson）一并参与组合；
        // 客户端菜单容器是 SimpleContainer，自然走无 AI 覆盖的入口。
        List<ItemStack> materials = new ArrayList<>(MATERIAL_SLOTS);
        for (int i = 0; i < MATERIAL_SLOTS; i++) {
            materials.add(this.container.getItem(i));
        }

        // 指纹短路（同锻造台）：compose 不便宜，且任何一次容器点击都会走到这里。
        // 指纹必须同时覆盖「材料」与「AI 暂存」——SpellJsonReportHandler 先写暂存
        // 再调本方法，材料没变，只按材料算指纹会把 AI 路径整个短路掉。
        int fingerprint = materialsFingerprint(materials) * 31 + aiStateFingerprint();
        if (fingerprint == lastFingerprint) {
            // 产物槽被外部路径（仪式触发/空手取走）清掉但材料指纹未变时，
            // 不能跳过——否则会永久卡在空预览（与锻造台同一修复）。
            boolean hasMaterials = materials.stream().anyMatch(s -> !s.isEmpty());
            if (!this.container.getItem(RESULT_SLOT).isEmpty() || !hasMaterials) {
                return;
            }
        }
        lastFingerprint = fingerprint;

        SpellScrollComposer.Composition composition;
        if (this.container instanceof AlchemyTableBlockEntity be) {
            var sel = be.selectionOf(ownerUuid());
            composition = SpellScrollComposer.compose(materials, sel.spellJson());
        } else {
            composition = SpellScrollComposer.compose(materials);
        }
        this.container.setItem(RESULT_SLOT, composition.result());

        if (this.container instanceof AlchemyTableBlockEntity be) {
            be.updateCraftingState();
        }
    }

    /**
     * 取走产物的后置结算（旧「直接拿」路径遗留，现产物统一走合成仪式——
     * 保留供旧调用点编译；新路径见 {@code RitualLogic#startRitual}）：
     * 记相谱 + 酿造音 + 消耗每个材料槽 1 个 + 重算预览 + 完成态。
     * 产物栈的取出与清槽由调用方完成，本方法不碰产物槽，天然防双计。
     */
    public static void afterTakeResult(Player player, ItemStack resultStack, Container container, Runnable recompute) {
        recordAlchemy(player, resultStack);  // 炼金也进相谱（与锻造同一套传记）
        if (!resultStack.isEmpty() && !player.level().isClientSide()) {
            // 锻成即鸣砧（同锻造台）：炼成一记落锤
            player.level().playSound(null, player.blockPosition(),
                    net.minecraft.sounds.SoundEvents.BREWING_STAND_BREW,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.7f, 1.2f);
        }
        for (int i = 0; i < MATERIAL_SLOTS; i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty()) { s.shrink(1); container.setItem(i, s); }
        }
        recompute.run();

        if (container instanceof AlchemyTableBlockEntity be) {
            be.setComplete();
        }
    }

    /** 把这次炼金记进玩家相谱 + 位格 +1（律二不可逆铭刻，与锻造同一套；仪式 DONE 转态也调本方法）。失败不阻断合成。 */
    public static void recordAlchemy(Player player, ItemStack resultStack) {
        if (resultStack.isEmpty()) return;
        try {
            // Shift 连炼节流（同锻造台 recordForge）：按「产物种类」而非纯时间窗。
            String productKey = BuiltInRegistries.ITEM.getKey(resultStack.getItem()).toString();
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "forge_saga_" + productKey, FORGE_SAGA_COOLDOWN_MS)) {
                return;
            }
            var spell = resultStack.get(com.qianxiang.QianxiangDataComponents.CUSTOM_SPELL.get());
            String spellName = spell != null ? spell.displayName().getString()
                    : resultStack.getHoverName().getString();
            String entry = String.format("于炼金台炼得卷轴「%s」", spellName);
            SagaData saga = player.getData(QianxiangAttachments.SAGA_DATA);
            player.setData(QianxiangAttachments.SAGA_DATA, saga.withEntry(entry).withBumpedPosition(1));
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 记录相谱失败（不阻断炼金）", t);
        }
    }

    /**
     * 材料槽内容指纹：物品 + 数量 + 组件。
     * <p>组件必须计入——同一物品带不同 PhaseData 会炼出不同卷轴。
     */
    private static int materialsFingerprint(List<ItemStack> materials) {
        int hash = 1;
        for (ItemStack s : materials) {
            hash = hash * 31 + (s.isEmpty()
                    ? 0
                    : System.identityHashCode(s.getItem()) * 31
                            + s.getCount() * 7
                            + s.getComponents().hashCode());
        }
        return hash;
    }

    /** 该玩家当前 AI 选择的指纹（客户端 SimpleContainer 恒为 0）。 */
    private int aiStateFingerprint() {
        if (!(this.container instanceof AlchemyTableBlockEntity be)) {
            return 0;
        }
        var sel = be.selectionOf(ownerUuid());
        return sel.spellJson().hashCode() * 31 + sel.customName().hashCode();
    }

    /** 打开本菜单的玩家 UUID（AI 选择按玩家隔离）。 */
    private java.util.UUID ownerUuid() {
        return playerInventory.player == null ? null : playerInventory.player.getUUID();
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

        // 仪式中投料（Shift 从背包进材料槽）一律拒绝
        if (this.container instanceof AlchemyTableBlockEntity be
                && be.ritualState().active() && index != RESULT_SLOT) {
            com.qianxiang.block.RitualLogic.notifyBusy(player);
            return ItemStack.EMPTY;
        }

        int invStart = RESULT_SLOT + 1;      // 背包区起点（含快捷栏）
        int invEnd = invStart + 36;

        ItemStack stack = slot.getItem();
        ItemStack moved = stack.copy();

        if (index == RESULT_SLOT) {
            // Shift 点击产物 = 触发合成仪式（不再直接拿进背包）
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    && this.container instanceof AlchemyTableBlockEntity be) {
                com.qianxiang.block.RitualLogic.startRitual(be, serverPlayer);
            }
            return ItemStack.EMPTY;
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
        // 左键点击产物槽 = 触发合成仪式（产物槽 mayPickup=false，不会被原版拿走）
        if (slotId == RESULT_SLOT && button == 0
                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && this.container instanceof AlchemyTableBlockEntity be
                && !be.getItem(RESULT_SLOT).isEmpty()) {
            com.qianxiang.block.RitualLogic.startRitual(be, serverPlayer);
            return;
        }
        // 方块实体容器不会像 TransientCraftingContainer 那样回调菜单，
        // 手动拖拽/丢弃材料后必须主动重算结果槽（重算是幂等的，多调无害）。
        slotsChanged(this.container);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // 产物槽是实时预览，材料尚未消耗——关闭界面必须清掉，
        // 否则它作为真实容器槽留在方块实体里，可被漏斗抽走（零成本无限炼金）。
        this.container.setItem(RESULT_SLOT, ItemStack.EMPTY);
        // 顺带清掉该玩家在这台炼金台上的 AI 提案与选择（不落盘，随会话有效）
        if (this.container instanceof AlchemyTableBlockEntity be) {
            be.clearPlayerAiState(player.getUUID());
            // 产物槽刚被清空，状态必须跟着刷新。
            be.updateCraftingState();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }
}
