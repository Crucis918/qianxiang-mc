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
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

public class ForgeTableBlockEntity extends BlockEntity implements Container, MenuProvider {
    public static final int STATE_IDLE = 0;
    public static final int STATE_PARSING = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_FORGING = 3;
    public static final int STATE_COMPLETE = 4;

    private static final int COMPLETE_DURATION = 30;

    private final NonNullList<ItemStack> items = NonNullList.withSize(11, ItemStack.EMPTY); // 0-9 材料槽, 10 结果槽

    private int craftingState = STATE_IDLE;
    private int completeTicks = 0;

    // 最近一次 AI 响应的输出暂存（服务端）：AI 自由法术描述 + 自定义名 + EF 动作定制。
    // 由 SpellJsonReportPayload（C2S）写入，ForgeTableMenu.slotsChanged 组合产物时消费。
    private String lastSpellJson = "";
    private String lastCustomName = "";
    private String lastMovesetJson = "";

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

    // ---- Container ----
    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { for (var s : items) if (!s.isEmpty()) return false; return true; }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int amount) { return ContainerHelper.removeItem(items, slot, amount); }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) { items.set(slot, stack); setChanged(); }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); setChanged(); }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
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
