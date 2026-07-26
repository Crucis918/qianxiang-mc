package com.qianxiang.block;

import com.qianxiang.QianxiangBlockEntities;
import com.qianxiang.menu.ForgeTableMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.RandomSource;
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
import org.joml.Vector3f;

public class ForgeTableBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {
    public static final int STATE_IDLE = 0;
    public static final int STATE_PARSING = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_FORGING = 3;
    public static final int STATE_COMPLETE = 4;

    private static final int COMPLETE_DURATION = 30;

    /** PARSING 超时：AI 最长等 5 秒 + 余量，超过即认定回包丢失，自动复位。 */
    private static final int PARSING_TIMEOUT_TICKS = 400;

    private final NonNullList<ItemStack> items = NonNullList.withSize(11, ItemStack.EMPTY); // 0-9 材料槽, 10 结果槽

    private int craftingState = STATE_IDLE;
    private int completeTicks = 0;
    /** PARSING 状态已持续的 tick，用于超时兜底（不落盘）。 */
    private int parsingTicks = 0;

    // 【已废弃】方块级单份 AI 暂存。保留仅为读取旧存档 NBT 不报错；
    // 现行链路一律走下面的 per-player 提案表（多人同台不再串味）。
    private String lastSpellJson = "";
    private String lastCustomName = "";
    private String lastMovesetJson = "";

    // ======================= 按玩家的 AI 提案与选择（信任边界）=======================
    // 为什么要按玩家分开存：
    //  ① 信任边界——服务端算完提案后必须留一份副本，客户端只回传「选了第几条」。
    //     此前客户端回传的是 spellJson 全文，服务端无从区分「选了方案二」和
    //     「自己编了一段合法 JSON」，等于把法术内容的决定权交给了客户端。
    //  ② 多人同台——lastSpellJson 是方块级单份暂存，两个玩家用同一台锻造台时
    //     A 的 AI 法术会串味到 B 的产物，B 取走即白嫖 A 的成果。
    // 不落盘：提案随会话有效，重进世界重新问 AI 即可。

    /** 一条可被选中的 AI 提案（服务端权威副本）。 */
    public record AiProposal(String spellJson, String customName, String movesetJson) {
        public static final AiProposal EMPTY = new AiProposal("", "", "");
    }

    /** 玩家 UUID → 服务端为其算出的提案列表。 */
    private final java.util.Map<java.util.UUID, java.util.List<AiProposal>> proposalsByPlayer =
            new java.util.HashMap<>();

    /** 玩家 UUID → 该玩家当前选中的提案（含蓝图路径写入的等效选择）。 */
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

    /** 直接写入一份选择（蓝图路径：蓝图自带 spellJson/movesetJson，不经 AI 提案表）。 */
    public void setSelection(java.util.UUID playerId, AiProposal selection) {
        if (playerId == null) return;
        if (selection == null) {
            selectionByPlayer.remove(playerId);
        } else {
            selectionByPlayer.put(playerId, selection);
        }
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

    public ForgeTableBlockEntity(BlockPos pos, BlockState state) {
        super(QianxiangBlockEntities.FORGE_TABLE.get(), pos, state);
    }

    public int getCraftingState() {
        return craftingState;
    }

    public NonNullList<ItemStack> getItems() { return items; }

    /** 最近一次 AI 响应附带的 spellJson（无则空串）。 */
    public String getLastSpellJson() { return lastSpellJson; }

    /** 最近一次 AI 响应附带的自定义名（无则空串）。 */
    public String getLastCustomName() { return lastCustomName; }

    /** 最近一次 AI 响应附带的 movesetJson（EF 动作定制，无则空串）。 */
    public String getLastMovesetJson() { return lastMovesetJson; }

    /**
     * 暂存最近一次 AI 响应的输出（仅服务端调用）。空串/null 表示清除。
     * 蓝图使用时也会写入（蓝图中保存的 spellJson 重新应用）。
     */
    public void setLastAiSpell(String spellJson, String customName) {
        this.lastSpellJson = spellJson == null ? "" : spellJson;
        this.lastCustomName = customName == null ? "" : customName;
        setChanged();
    }

    /**
     * 暂存最近一次 AI 响应附带的 EF 动作定制描述（仅服务端调用）。空串/null 表示清除。
     * 蓝图使用时也会写入（蓝图中保存的 movesetJson 重新应用）。
     */
    public void setLastAiMoveset(String movesetJson) {
        this.lastMovesetJson = movesetJson == null ? "" : movesetJson;
        setChanged();
    }

    /**
     * 由服务端调用：设置锻造台工作状态，并同步到客户端。
     */
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

    /**
     * 根据当前材料槽与结果槽内容，自动切换到 idle 或 ready。
     */
    public void updateCraftingState() {
        if (level == null || level.isClientSide) {
            return;
        }
        boolean hasResult = !getItem(ForgeTableMenu.RESULT_SLOT).isEmpty();
        boolean hasMaterial = false;
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            if (!getItem(i).isEmpty()) {
                hasMaterial = true;
                break;
            }
        }
        setCraftingState(hasResult && hasMaterial ? STATE_READY : STATE_IDLE);
    }

    /**
     * 玩家取出产物后调用：进入 complete 状态，客户端会播放光柱与火花。
     */
    public void setComplete() {
        if (level == null || level.isClientSide) {
            return;
        }
        setCraftingState(STATE_COMPLETE);
    }

    // ---- Ticking ----

    public static void tick(Level level, BlockPos pos, BlockState state, ForgeTableBlockEntity be) {
        if (level.isClientSide) {
            be.tickClientParticles(level, pos);
        } else {
            be.tickServer();
        }
    }

    private void tickServer() {
        if (craftingState == STATE_COMPLETE) {
            completeTicks--;
            if (completeTicks <= 0) {
                updateCraftingState();
            }
            return;
        }
        // PARSING 的复位此前完全依赖「AI 回包时玩家仍开着这个菜单」——
        // 玩家中途关 GUI 或下线，回包直接 return，方块就永久停在 PARSING
        // （粒子长明，且状态还会落盘，重进存档依旧）。这里加一道超时兜底。
        if (craftingState == STATE_PARSING) {
            parsingTicks++;
            if (parsingTicks > PARSING_TIMEOUT_TICKS) {
                parsingTicks = 0;
                updateCraftingState();
            }
        } else {
            parsingTicks = 0;
        }
    }

    private void tickClientParticles(Level level, BlockPos pos) {
        RandomSource rand = level.random;
        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;

        switch (craftingState) {
            case STATE_IDLE -> {
                if (rand.nextInt(20) == 0) {
                    level.addParticle(ParticleTypes.WITCH,
                            x + (rand.nextDouble() - 0.5) * 0.6,
                            y + 1.1 + rand.nextDouble() * 0.3,
                            z + (rand.nextDouble() - 0.5) * 0.6,
                            (rand.nextDouble() - 0.5) * 0.02,
                            0.03,
                            (rand.nextDouble() - 0.5) * 0.02);
                }
            }
            case STATE_PARSING -> {
                for (int i = 0; i < 2; i++) {
                    level.addParticle(ParticleTypes.END_ROD,
                            x + (rand.nextDouble() - 0.5) * 0.5,
                            y + 1.0 + rand.nextDouble() * 0.4,
                            z + (rand.nextDouble() - 0.5) * 0.5,
                            (rand.nextDouble() - 0.5) * 0.02,
                            0.04 + rand.nextDouble() * 0.02,
                            (rand.nextDouble() - 0.5) * 0.02);
                }
            }
            case STATE_READY -> {
                if (rand.nextInt(4) == 0) {
                    level.addParticle(ParticleTypes.HAPPY_VILLAGER,
                            x + (rand.nextDouble() - 0.5) * 0.8,
                            y + 0.8 + rand.nextDouble() * 0.6,
                            z + (rand.nextDouble() - 0.5) * 0.8,
                            (rand.nextDouble() - 0.5) * 0.02,
                            0.05,
                            (rand.nextDouble() - 0.5) * 0.02);
                }
            }
            case STATE_COMPLETE -> {
                // 短细金色光柱：沿 y 轴向上发射的染色尘埃
                int steps = 8;
                for (int i = 0; i < steps; i++) {
                    double by = y + 1.0 + i * (2.0 / steps);
                    double spread = 0.08 * (1.0 - (double) i / steps);
                    level.addParticle(new DustParticleOptions(new Vector3f(1.0f, 0.85f, 0.2f), 0.5f),
                            x + (rand.nextDouble() - 0.5) * spread,
                            by,
                            z + (rand.nextDouble() - 0.5) * spread,
                            0.0,
                            0.12,
                            0.0);
                }
                if (rand.nextInt(3) == 0) {
                    level.addParticle(ParticleTypes.FIREWORK,
                            x + (rand.nextDouble() - 0.5) * 0.6,
                            y + 1.2,
                            z + (rand.nextDouble() - 0.5) * 0.6,
                            (rand.nextDouble() - 0.5) * 0.1,
                            0.1 + rand.nextDouble() * 0.1,
                            (rand.nextDouble() - 0.5) * 0.1);
                }
            }
            default -> {
                // 无粒子
            }
        }
    }

    /**
     * 破坏方块时掉落材料槽内容（{@link ForgeTableBlock#onRemove} 调用）。
     * <p><b>只掉 0-9 材料槽</b>：槽 10 是实时预览产物，材料尚未消耗，掉出去等于白送成品。
     * 掉落后清空全部槽位，防 super.onRemove 之外的路径重复掉落。
     */
    public void dropContentsOnRemove(Level level, BlockPos pos) {
        NonNullList<ItemStack> materialsOnly =
                NonNullList.withSize(ForgeTableMenu.MATERIAL_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            materialsOnly.set(i, items.get(i));
        }
        net.minecraft.world.Containers.dropContents(level, pos, materialsOnly);
        // 这行有双重职责，勿删：①防止 super.onRemove 之外的路径重复掉落；
        // ②封死「A 开着界面、B 炸掉台子」的抢跑窗口——清空后 A 那边的
        // slotsChanged 再算也是空组合，拿不到产物。
        items.clear();
    }

    // ---- Container ----
    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { for (var s : items) if (!s.isEmpty()) return false; return true; }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int amount) { return ContainerHelper.removeItem(items, slot, amount); }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) { items.set(slot, stack); setChanged(); }
    /**
     * 产物槽不接受任何放入。
     * <p>{@link Container} 的默认实现恒返回 true，是产物槽最后一个敞着的口子——
     * 当前没有注册 item handler capability 所以外部利用不到，但任何一处
     * （管道 mod、未来的 capability 暴露）都可能把它变成可利用面。
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot < ForgeTableMenu.MATERIAL_SLOTS;
    }

    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); setChanged(); }

    // ---- WorldlyContainer：自动化只能碰材料槽，产物槽对漏斗完全不可见 ----
    // 否则「开一次 GUI 生成预览产物 → 关 GUI → 漏斗抽走材料再抽走产物」= 零成本无限锻造。

    /** 只暴露 0-9 材料槽；产物槽（10）不在其中，漏斗既抽不到也塞不进。 */
    private static final int[] MATERIAL_SLOT_INDICES =
            java.util.stream.IntStream.range(0, ForgeTableMenu.MATERIAL_SLOTS).toArray();

    @Override
    public int[] getSlotsForFace(Direction side) {
        return MATERIAL_SLOT_INDICES;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @javax.annotation.Nullable Direction side) {
        return slot < ForgeTableMenu.MATERIAL_SLOTS;
    }

    /**
     * 自动化<b>只进不出</b>。
     * <p>产物槽不可抽是防「零成本无限锻造」；材料槽同样不可抽，是防另一条脱钩利用：
     * 界面开着时漏斗把传奇材料换成圆石，而 {@code slotsChanged} 只由菜单驱动、
     * 不会重算，玩家就能用圆石领走此前生成的高级产物。
     */
    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 产物槽是实时预览（材料未消耗），不落盘——否则重进存档后槽 10 白留一件成品。
        // 拷贝一份把槽 10 清掉再存，不动内存中的实时预览。
        NonNullList<ItemStack> persisted = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            persisted.set(i, items.get(i));
        }
        ContainerHelper.saveAllItems(tag, persisted, registries);
        tag.putInt("CraftingState", craftingState);
        // AI 输出暂存随方块实体持久化：重进存档后产物组合仍能复现 AI 法术/名称/动作定制。
        if (!lastSpellJson.isEmpty()) tag.putString("LastSpellJson", lastSpellJson);
        if (!lastCustomName.isEmpty()) tag.putString("LastCustomName", lastCustomName);
        if (!lastMovesetJson.isEmpty()) tag.putString("LastMovesetJson", lastMovesetJson);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ContainerHelper.loadAllItems(tag, items, registries);
        craftingState = tag.getInt("CraftingState");
        // 落盘的 PARSING 一律归一为 IDLE：那次 AI 请求早已随上次会话消失，
        // 不归一的话重开存档会看到一台永远在「解析中」的锻造台。
        if (craftingState == STATE_PARSING) {
            craftingState = STATE_IDLE;
        }
        // 旧存档无这两个键，getString 缺省返回空串，天然兼容。
        lastSpellJson = tag.getString("LastSpellJson");
        lastCustomName = tag.getString("LastCustomName");
        lastMovesetJson = tag.getString("LastMovesetJson");
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
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        craftingState = tag.getInt("CraftingState");
    }

    // ---- MenuProvider ----
    @Override public Component getDisplayName() { return Component.translatable("block.qianxiang.forge_table"); }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new ForgeTableMenu(id, inv, this);
    }
}
