package com.qianxiang.block;

import com.qianxiang.QianxiangBlockEntities;
import com.qianxiang.menu.AlchemyTableMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 炼金台方块实体：6 材料槽 + 1 卷轴产物槽（实时预览）。
 * <p>
 * 安全不变量与锻造台一致（见 {@link ForgeTableBlockEntity}）：
 * WorldlyContainer 只进不出、产物槽禁放禁抽、产物不落盘、onRemove 只掉材料。
 * </p>
 * <p>
 * AI 提案按玩家 UUID 暂存（WQ-2 信任边界：服务端留真身，客户端只回传索引），
 * 不落盘，随会话有效；炼金台提案只需 spellJson + 自定义名（无 moveset）。
 * </p>
 */
public class AlchemyTableBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider, RitualHost {
    public static final int STATE_IDLE = 0;
    public static final int STATE_PARSING = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_COMPLETE = 4;

    private static final int COMPLETE_DURATION = 30;

    /** PARSING 超时：AI 最长等 5 秒 + 余量，超过即认定回包丢失，自动复位。 */
    private static final int PARSING_TIMEOUT_TICKS = 400;

    private final NonNullList<ItemStack> items =
            NonNullList.withSize(AlchemyTableMenu.RESULT_SLOT + 1, ItemStack.EMPTY); // 0-5 材料槽, 6 结果槽

    private int craftingState = STATE_IDLE;
    private int completeTicks = 0;
    /** PARSING 状态已持续的 tick，用于超时兜底（不落盘）。 */
    private int parsingTicks = 0;

    /** 显示用产物栈（仅 BER 渲染，随 update tag 下发；与产物槽同口径不落盘）。 */
    private ItemStack displayResult = ItemStack.EMPTY;
    /** 客户端同步脏标记：材料/产物变化同 tick 合并，tickServer 末尾统一 sendBlockUpdated。 */
    private boolean clientSyncDirty = false;

    // ---- 合成仪式状态（见 RitualLogic）----
    private RitualState ritualState = RitualState.NONE;
    private int ritualProgress = 0;
    private java.util.UUID ritualOwner = null;
    /** 仪式锁定的材料（不立即销毁，挖台照常掉落；DONE 时才清空=真正消耗）。 */
    private final java.util.List<ItemStack> ritualInputs = new java.util.ArrayList<>();
    /** 仪式产物暂存（FORMING 期间非空，DONE 时转入 displayResult）。 */
    private ItemStack pendingResult = ItemStack.EMPTY;

    // ======================= 按玩家的 AI 提案与选择（信任边界）=======================
    // 与锻造台同一套隔离理由（见 ForgeTableBlockEntity）：服务端留内容真身，
    // 客户端只回传「选了第几条」；按玩家分开存，多人同台不串味。不落盘。

    /** 一条可被选中的 AI 提案（服务端权威副本；炼金台只消费 spellJson + 自定义名）。 */
    public record AiProposal(String spellJson, String customName) {
        public static final AiProposal EMPTY = new AiProposal("", "");
    }

    /** 玩家 UUID → 服务端为其算出的提案列表。 */
    private final java.util.Map<java.util.UUID, java.util.List<AiProposal>> proposalsByPlayer =
            new java.util.HashMap<>();

    /** 玩家 UUID → 该玩家当前选中的提案。 */
    private final java.util.Map<java.util.UUID, AiProposal> selectionByPlayer =
            new java.util.HashMap<>();

    /** 服务端算出提案后登记（覆盖该玩家上一批）。 */
    public void setProposals(java.util.UUID playerId, java.util.List<AiProposal> proposals) {
        if (playerId == null) return;
        if (proposals == null || proposals.isEmpty()) {
            proposalsByPlayer.remove(playerId);
        } else {
            proposalsByPlayer.put(playerId, java.util.List.copyOf(proposals));
        }
    }

    /**
     * 按索引选中该玩家自己的提案。
     *
     * @param index 提案下标；负数或越界 = 清除选择（等价「不用 AI 结果」）
     * @return true = 选中了一条真实提案
     */
    public boolean selectProposal(java.util.UUID playerId, int index) {
        if (playerId == null) return false;
        java.util.List<AiProposal> list = proposalsByPlayer.get(playerId);
        if (list == null || index < 0 || index >= list.size()) {
            selectionByPlayer.remove(playerId);
            return false;
        }
        selectionByPlayer.put(playerId, list.get(index));
        return true;
    }

    /** 该玩家当前选中的提案；没有则返回 {@link AiProposal#EMPTY}。 */
    public AiProposal selectionOf(java.util.UUID playerId) {
        if (playerId == null) return AiProposal.EMPTY;
        return selectionByPlayer.getOrDefault(playerId, AiProposal.EMPTY);
    }

    /** 玩家关闭容器/离开时清理其提案与选择，避免长期占用。 */
    public void clearPlayerAiState(java.util.UUID playerId) {
        if (playerId == null) return;
        proposalsByPlayer.remove(playerId);
        selectionByPlayer.remove(playerId);
    }

    public AlchemyTableBlockEntity(BlockPos pos, BlockState state) {
        super(QianxiangBlockEntities.ALCHEMY_TABLE.get(), pos, state);
    }

    public int getCraftingState() {
        return craftingState;
    }

    public NonNullList<ItemStack> getItems() { return items; }

    /** 由服务端调用：设置炼金台工作状态，并同步到客户端。 */
    public void setCraftingState(int state) {
        if (this.craftingState == state && state != STATE_COMPLETE) {
            return;
        }
        this.craftingState = state;
        if (state == STATE_COMPLETE) {
            this.completeTicks = COMPLETE_DURATION;
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** 根据当前材料槽与结果槽内容，自动切换到 idle 或 ready。 */
    public void updateCraftingState() {
        if (level == null || level.isClientSide) {
            return;
        }
        boolean hasResult = !getItem(AlchemyTableMenu.RESULT_SLOT).isEmpty();
        boolean hasMaterial = false;
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            if (!getItem(i).isEmpty()) {
                hasMaterial = true;
                break;
            }
        }
        setCraftingState(hasResult && hasMaterial ? STATE_READY : STATE_IDLE);
    }

    /** 玩家取出产物后调用：进入 complete 状态。 */
    public void setComplete() {
        if (level == null || level.isClientSide) {
            return;
        }
        setCraftingState(STATE_COMPLETE);
    }

    // ---- Ticking ----

    public static void tick(Level level, BlockPos pos, BlockState state, AlchemyTableBlockEntity be) {
        if (!level.isClientSide) {
            be.tickServer();
        }
    }

    private void tickServer() {
        if (craftingState == STATE_COMPLETE) {
            completeTicks--;
            if (completeTicks <= 0) {
                updateCraftingState();
            }
        } else if (craftingState == STATE_PARSING) {
            // PARSING 超时兜底（与锻造台同理）：玩家中途关 GUI/下线时回包可能打空，
            // 不能让方块永久停在 PARSING。
            parsingTicks++;
            if (parsingTicks > PARSING_TIMEOUT_TICKS) {
                parsingTicks = 0;
                updateCraftingState();
            }
        } else {
            parsingTicks = 0;
        }

        // 投入式交互：每 5 tick 吸收台面上方的掉落物（满槽不吸；仪式中不吸——投料一律拒绝）
        if (level != null && level.getGameTime() % 5 == 0 && !ritualState.active()
                && TableInteractions.absorbAbove(this, AlchemyTableMenu.SLOT_FILL_ORDER, level, worldPosition) > 0) {
            recomputeResult(null);
        }

        // 合成仪式状态机推进
        RitualLogic.tick(this);

        // 客户端同步：同 tick 的材料/产物变化合并成一次 sendBlockUpdated
        if (clientSyncDirty && level != null) {
            clientSyncDirty = false;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * 菜单外（投料/取料/吸收/空手取产物）改动材料后重算预览产物。
     * <p>{@code viewer} 的 AI 选择参与组合，可空（空 = 纯材料模板路径）。</p>
     */
    public void recomputeResult(@javax.annotation.Nullable java.util.UUID viewer) {
        if (level == null || level.isClientSide) return;
        java.util.List<ItemStack> materials = new java.util.ArrayList<>(AlchemyTableMenu.MATERIAL_SLOTS);
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            materials.add(items.get(i));
        }
        var sel = selectionOf(viewer);
        var composition = com.qianxiang.phase.SpellScrollComposer.compose(materials, sel.spellJson());
        setItem(AlchemyTableMenu.RESULT_SLOT, composition.result());
        updateCraftingState();
    }

    /**
     * 破坏方块时掉落材料槽内容 + 仪式锁定材料（{@link AlchemyTableBlock#onRemove} 调用）。
     * <p><b>只掉材料槽与 ritualInputs</b>：产物槽是实时预览、pendingResult 是暂存（仪式中挖台：
     * ritualInputs 掉落，pendingResult 作废，材料不丢；DONE 后 ritualInputs 已清空，无残留）。
     */
    public void dropContentsOnRemove(Level level, BlockPos pos) {
        NonNullList<ItemStack> materialsOnly =
                NonNullList.withSize(AlchemyTableMenu.MATERIAL_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            materialsOnly.set(i, items.get(i));
        }
        net.minecraft.world.Containers.dropContents(level, pos, materialsOnly);
        if (!ritualInputs.isEmpty()) {
            for (ItemStack s : ritualInputs) {
                net.minecraft.world.Containers.dropItemStack(level,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s);
            }
            ritualInputs.clear();
        }
        pendingResult = ItemStack.EMPTY;
        ritualState = RitualState.NONE;
        // 双重职责（同锻造台）：防重复掉落 + 封死「A 开着界面、B 炸掉台子」的抢跑窗口。
        items.clear();
    }

    // ---- Container ----
    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { for (var s : items) if (!s.isEmpty()) return false; return true; }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int amount) { return ContainerHelper.removeItem(items, slot, amount); }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        // 产物槽变化同步 displayResult（BER 渲染数据源），材料/产物变化都标客户端同步脏
        if (slot == AlchemyTableMenu.RESULT_SLOT) {
            displayResult = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        }
        clientSyncDirty = true;
        setChanged();
    }
    /** 产物槽不接受任何放入（同锻造台：封死 Container 默认恒 true 的口子）。 */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot < AlchemyTableMenu.MATERIAL_SLOTS;
    }

    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); setChanged(); }

    // ---- WorldlyContainer：自动化只能碰材料槽，产物槽对漏斗完全不可见 ----
    // 否则「开一次 GUI 生成预览产物 → 关 GUI → 漏斗抽走材料再抽走产物」= 零成本无限炼金。

    /** 只暴露材料槽；产物槽不在其中，漏斗既抽不到也塞不进。 */
    private static final int[] MATERIAL_SLOT_INDICES =
            java.util.stream.IntStream.range(0, AlchemyTableMenu.MATERIAL_SLOTS).toArray();

    @Override
    public int[] getSlotsForFace(Direction side) {
        return MATERIAL_SLOT_INDICES;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @javax.annotation.Nullable Direction side) {
        return slot < AlchemyTableMenu.MATERIAL_SLOTS;
    }

    /** 自动化<b>只进不出</b>（理由同锻造台：防产物白抽与「界面开着换料」脱钩利用）。 */
    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 产物槽是实时预览（材料未消耗），不落盘——否则重进存档白留一张卷轴。
        NonNullList<ItemStack> persisted = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            persisted.set(i, items.get(i));
        }
        ContainerHelper.saveAllItems(tag, persisted, registries);
        tag.putInt("CraftingState", craftingState);
        // 仪式状态落盘：防仪式中退出吞料（读回时非 NONE 统一归 NONE 并退回材料，见 loadAdditional）
        saveRitual(tag, registries);
    }

    /** 仪式字段序列化（saveAdditional 与 getUpdateTag 共用）。 */
    private void saveRitual(CompoundTag tag, HolderLookup.Provider registries) {
        if (!ritualInputs.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (ItemStack s : ritualInputs) {
                list.add(s.save(registries));
            }
            tag.put("RitualInputs", list);
        }
        if (!pendingResult.isEmpty()) {
            tag.put("PendingResult", pendingResult.save(registries));
        }
        tag.putInt("RitualState", ritualState.ordinal());
        tag.putInt("RitualProgress", ritualProgress);
        if (ritualOwner != null) {
            tag.putUUID("RitualOwner", ritualOwner);
        }
    }

    /** 仪式字段反序列化（loadAdditional 与 handleUpdateTag 共用）。 */
    private void loadRitual(CompoundTag tag, HolderLookup.Provider registries) {
        ritualInputs.clear();
        for (var entry : tag.getList("RitualInputs", 10)) {
            if (entry instanceof CompoundTag stackTag) {
                ItemStack.parse(registries, stackTag).ifPresent(ritualInputs::add);
            }
        }
        pendingResult = tag.contains("PendingResult")
                ? ItemStack.parse(registries, tag.getCompound("PendingResult")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        int ord = tag.getInt("RitualState");
        ritualState = ord >= 0 && ord < RitualState.values().length
                ? RitualState.values()[ord] : RitualState.NONE;
        ritualProgress = Math.max(0, tag.getInt("RitualProgress"));
        ritualOwner = tag.hasUUID("RitualOwner") ? tag.getUUID("RitualOwner") : null;
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ContainerHelper.loadAllItems(tag, items, registries);
        craftingState = tag.getInt("CraftingState");
        // 落盘的 PARSING 一律归一为 IDLE（那次 AI 请求早已随上次会话消失）。
        if (craftingState == STATE_PARSING) {
            craftingState = STATE_IDLE;
        }
        loadRitual(tag, registries);
        // 仪式状态不跨存档恢复（VFX 进度无意义）：非 NONE 统一归 NONE，ritualInputs 退回材料槽。
        if (ritualState != RitualState.NONE) {
            ritualState = RitualState.NONE;
            ritualProgress = 0;
            ritualOwner = null;
            pendingResult = ItemStack.EMPTY;
            java.util.List<ItemStack> refund = new java.util.ArrayList<>(ritualInputs);
            ritualInputs.clear();
            for (ItemStack s : refund) {
                TableInteractions.insert(this, AlchemyTableMenu.SLOT_FILL_ORDER, s, true);
            }
        }
    }

    // ---- Sync to client ----

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("CraftingState", craftingState);
        // BER 漂浮虚影数据源：材料槽（产物槽与落盘口径一致排除）+ 显示用产物栈
        NonNullList<ItemStack> materialsOnly = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            materialsOnly.set(i, items.get(i));
        }
        ContainerHelper.saveAllItems(tag, materialsOnly, registries);
        if (!displayResult.isEmpty()) {
            tag.put("DisplayResult", displayResult.save(registries));
        }
        // 仪式 VFX 数据源（BER 用）
        saveRitual(tag, registries);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        craftingState = tag.getInt("CraftingState");
        ContainerHelper.loadAllItems(tag, items, registries);
        displayResult = tag.contains("DisplayResult")
                ? ItemStack.parse(registries, tag.getCompound("DisplayResult")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        loadRitual(tag, registries);
    }

    // ---- MenuProvider ----
    @Override public Component getDisplayName() { return Component.translatable("block.qianxiang.alchemy_table"); }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new AlchemyTableMenu(id, inv, this);
    }

    // ---- FloatingTableView（BER 读取视图）----

    @Override
    public int materialSlotCount() {
        return AlchemyTableMenu.MATERIAL_SLOTS;
    }

    @Override
    public ItemStack getDisplayResult() {
        return displayResult;
    }

    // ---- RitualHost（合成仪式，状态机见 RitualLogic）----

    @Override
    public RitualState ritualState() {
        return ritualState;
    }

    @Override
    public int ritualProgress() {
        return ritualProgress;
    }

    @Override
    public java.util.UUID ritualOwner() {
        return ritualOwner;
    }

    @Override
    public java.util.List<ItemStack> ritualInputs() {
        return ritualInputs;
    }

    @Override
    public ItemStack pendingResult() {
        return pendingResult;
    }

    @Override
    public void setRitualState(RitualState state) {
        this.ritualState = state;
    }

    @Override
    public void setRitualProgress(int progress) {
        this.ritualProgress = progress;
    }

    @Override
    public void setRitualOwner(java.util.UUID owner) {
        this.ritualOwner = owner;
    }

    @Override
    public void setPendingResult(ItemStack stack) {
        this.pendingResult = stack == null ? ItemStack.EMPTY : stack;
    }

    @Override
    public ItemStack composedResult() {
        return getItem(AlchemyTableMenu.RESULT_SLOT);
    }

    @Override
    public void collectInputsToRitual() {
        ritualInputs.clear();
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            ItemStack s = items.get(i);
            if (!s.isEmpty()) {
                ritualInputs.add(s.copy());
            }
        }
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            setItem(i, ItemStack.EMPTY);
        }
    }

    @Override
    public void clearComposedResult() {
        setItem(AlchemyTableMenu.RESULT_SLOT, ItemStack.EMPTY);
    }

    @Override
    public void setDisplayResultFromRitual(ItemStack stack) {
        this.displayResult = stack == null ? ItemStack.EMPTY : stack;
        clientSyncDirty = true;
        setChanged();
    }

    @Override
    public void clearRitualState() {
        this.ritualState = RitualState.NONE;
        this.ritualProgress = 0;
        this.ritualOwner = null;
        clientSyncDirty = true;
        setChanged();
    }

    @Override
    public void markRitualDirty() {
        clientSyncDirty = true;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void recordRitualCompleted(net.minecraft.server.level.ServerPlayer owner, ItemStack result) {
        // 仪式完成即算创作成功：记相谱 + 位格（与 menu.onTake 同一口径）；owner 下线则跳过。
        if (owner != null) {
            AlchemyTableMenu.recordAlchemy(owner, result);
        }
    }

    @Override
    public Level ritualLevel() {
        return level;
    }

    @Override
    public BlockPos ritualPos() {
        return worldPosition;
    }
}
