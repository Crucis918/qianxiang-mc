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

    /** 两次「相谱铭刻 + 位格增长」的最小间隔——防 Shift 连锻一次点击刷满位格。 */
    private static final long FORGE_SAGA_COOLDOWN_MS = 3_000L;

    /**
     * 「传奇产物」的强度门槛（forge_legendary 成就用）。
     * <p>森罗之核单件在 LEGENDARY 档就贡献可观强度，配任意基底都能轻松越过；
     * 但拿核配一堆空气/纯辅料做出的弱产物达不到，与成就文案保持一致。
     */
    private static final double LEGENDARY_POWER_THRESHOLD = 12.0;

    /** 上次参与组合的材料指纹，用于跳过无谓的重算（见 slotsChanged）。 */
    private int lastMaterialsFingerprint = Integer.MIN_VALUE;

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
        // 中途失败要整体回滚：否则前几件材料已经进了台子，玩家看到「材料不足」
        // 却发现背包少了东西、台上多了半套料，还得手动搬回去。
        java.util.List<Integer> placedSlots = new ArrayList<>();
        for (String name : blueprint.materials()) {
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id == null) return rollbackBlueprint(placedSlots);
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) return rollbackBlueprint(placedSlots);

            int placeSlot = -1;
            for (int i = nextSlot; i < MATERIAL_SLOTS; i++) {
                if (container.getItem(i).isEmpty()) {
                    placeSlot = i;
                    nextSlot = i + 1;
                    break;
                }
            }
            if (placeSlot < 0) return rollbackBlueprint(placedSlots);

            // 只扫主背包 36 格（0..INVENTORY_SIZE-1）——getContainerSize() 是 41，
            // 会把身上穿的 4 件盔甲和副手也当材料扒走。
            // 且必须挑「最不值钱的同 id 那件」：取首个命中会把玩家的经验修补附魔镐
            // 直接吃掉（同 id 的垃圾镐就在后面），而蓝图作者存的是白板方案，
            // 用附魔件去配还会让产物属性与蓝图预览对不上。
            int found = findCheapestMatch(item);
            if (found < 0) return rollbackBlueprint(placedSlots);

            // 搬运原栈而非 new ItemStack(item, 1)：后者是出厂新品，
            // 会把残耐久/附魔/自定义名洗成白板（等于免费修复+洗附魔）。
            ItemStack moved = playerInventory.getItem(found).split(1);
            container.setItem(placeSlot, moved);
            placedSlots.add(placeSlot);
        }
        // 蓝图中保存的 spellJson/movesetJson 一并重新应用（无对应 JSON 的旧蓝图则清除暂存，
        // 避免上一次 AI 响应的法术/动作串味到蓝图产物）。蓝图名作为自定义名恢复。
        if (container instanceof ForgeTableBlockEntity be) {
            String sj = blueprint.spellJson();
            String mj = blueprint.movesetJson();
            // 蓝图自带的法术/动作也按玩家隔离写入（与 AI 提案同一条通道）
            be.setSelection(ownerUuid(), new ForgeTableBlockEntity.AiProposal(
                    sj == null ? "" : sj,
                    sj == null ? "" : blueprint.name(),
                    mj == null ? "" : mj));
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

        // 任意一次容器点击（含与材料无关的背包格）都会走到这里，而 compose 是
        // 相当重的计算（解析每条材料 + 属性合成 + 外观/代价推导），还会 setItem
        // 新产物栈触发一次同步。先按指纹短路：输入没变就不重算。
        //
        // 指纹必须同时覆盖「材料」与「AI 暂存」两部分：SpellJsonReportHandler 与
        // applyBlueprint 都是先写 AI 暂存再调本方法，材料并未变化——只按材料算指纹
        // 会把这两条路径整个短路掉，AI 法术/动作永远进不了预览。
        int fingerprint = materialsFingerprint(materials) * 31 + aiStateFingerprint();
        if (fingerprint == lastMaterialsFingerprint) {
            return;
        }
        lastMaterialsFingerprint = fingerprint;

        ForgeComposer.Composition composition;
        if (this.container instanceof ForgeTableBlockEntity be) {
            // 按「打开这个菜单的玩家」取 AI 选择，而不是方块级单份暂存——
            // 后者会让多人同用一台锻造台时互相串味（A 的 AI 法术出现在 B 的产物上）。
            var sel = be.selectionOf(ownerUuid());
            composition = ForgeComposer.compose(materials,
                    sel.spellJson(), sel.customName(), sel.movesetJson());
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
        if (!resultStack.isEmpty() && !player.level().isClientSide()) {
            // 锻成即鸣砧：动态锻造没有固定配方音，这里统一给成品一记落锤
            player.level().playSound(null, player.blockPosition(),
                    net.minecraft.sounds.SoundEvents.ANVIL_USE,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.7f, 1.1f);
        }
        // 进程终点「以核铸相」：材料含森罗之核（Boss 独占掉落）**且产物确实够传奇**。
        // 必须在扣料之前检测——扣完就看不到核了。
        // 只看材料有核是不够的：成就文案写的是「锻造出一件传奇相器」，
        // 拿核配一堆垃圾料做出个弱产物也算达成的话，文案与实际就对不上了。
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            boolean usedCore = false;
            for (int i = 0; i < MATERIAL_SLOTS; i++) {
                if (container.getItem(i).is(com.qianxiang.QianxiangItems.WARDEN_CORE.get())) {
                    usedCore = true;
                    break;
                }
            }
            var attrs = resultStack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
            boolean legendaryGrade = attrs != null && attrs.powerScore() >= LEGENDARY_POWER_THRESHOLD;
            if (usedCore && legendaryGrade) {
                com.qianxiang.QianxiangAdvancements.grant(
                        serverPlayer, com.qianxiang.QianxiangAdvancements.FORGE_LEGENDARY);
            }
        }

        for (int i = 0; i < MATERIAL_SLOTS; i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty()) { s.shrink(1); container.setItem(i, s); }
        }
        slotsChanged(container);

        if (this.container instanceof ForgeTableBlockEntity be) {
            be.setComplete();
        }
    }

    /** 如果产物铭刻了法术，让玩家学会它（不消耗，用于施法环/面板）。覆盖三种载体：
     *  相杖 CUSTOM_SPELL、法术书 SPELLBOOK（全部法术）、旧存档物品的 SPELL。 */
    private void learnSpellFromResult(Player player, ItemStack resultStack) {
        if (resultStack.isEmpty()) return;
        try {
            java.util.List<ResourceLocation> ids = new java.util.ArrayList<>();
            var custom = resultStack.get(QianxiangDataComponents.CUSTOM_SPELL.get());
            if (custom != null) ids.add(custom.id());
            var book = resultStack.get(QianxiangDataComponents.SPELLBOOK.get());
            if (book != null) {
                for (var s : book.spells()) ids.add(s.id());
            }
            ResourceLocation legacy = resultStack.get(QianxiangDataComponents.SPELL.get());
            if (legacy != null) ids.add(legacy);
            if (ids.isEmpty()) return;

            PlayerSpellData data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
            for (ResourceLocation id : ids) {
                data = data.learn(id);
            }
            player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, data);
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 记录已学法术失败（不阻断合成）", t);
        }
    }

    /** 把这次锻造记进玩家相谱 + 位格 +1（律二不可逆铭刻）。失败不阻断合成。 */
    private void recordForge(Player player, ItemStack resultStack) {
        if (resultStack.isEmpty()) return;
        try {
            // Shift 点击结果槽会走原版 QUICK_MOVE 的 while 循环连续锻造：
            // 材料槽各放 64 个，一次点击就能连锻 64 次。若每次都写一条相谱并 +1 位格，
            // 相谱会被同一批产物灌满 64 条，位格两次点击即触顶 100，
            // 直接架空 WILDS_POSITION_THRESHOLD 的维度门控设计。
            //
            // 节流按「产物种类」而非纯时间窗：连锻同一件只记一次，
            // 但 3 秒内手动锻出两件**不同**产物应当各记一条——那是两次真实创造，
            // 纯时间窗会把第二件的相谱与位格一起吞掉。
            String productKey = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(resultStack.getItem()).toString();
            if (!com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                    player, "forge_saga_" + productKey, FORGE_SAGA_COOLDOWN_MS)) {
                return;
            }
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

    /**
     * 材料槽内容指纹：物品 + 数量 + 组件。
     * <p>组件必须计入——同一物品带不同 ComposedAttributes 会锻出不同产物。
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

    /** 把已放入台子的材料原样退回玩家背包；恒返回 false（供失败分支直接 return）。 */
    private boolean rollbackBlueprint(java.util.List<Integer> placedSlots) {
        for (int slot : placedSlots) {
            ItemStack back = container.getItem(slot);
            if (back.isEmpty()) continue;
            container.setItem(slot, ItemStack.EMPTY);
            if (!playerInventory.add(back)) {
                // 背包在此期间被填满：掉在脚下总好过凭空消失
                playerInventory.player.drop(back, false);
            }
        }
        return false;
    }

    /**
     * 在主背包里挑「最不值钱」的同 id 物品：无附魔 &gt; 无自定义名 &gt; 损伤最大（越旧越先用）。
     * <p>取首个命中会把玩家的经验修补附魔镐直接吃掉（同 id 的垃圾镐就排在后面），
     * 而蓝图作者存的是白板方案，用附魔件去配还会让产物属性与蓝图预览对不上。
     * <p>找不到返回 -1。
     */
    private int findCheapestMatch(Item item) {
        int best = -1;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = playerInventory.getItem(i);
            if (s.isEmpty() || !s.is(item)) continue;
            long score = valueScore(s);
            if (score < bestScore) {
                bestScore = score;
                best = i;
                if (score == 0) break;   // 已是纯白板且最旧，不可能更便宜
            }
        }
        return best;
    }

    /** 价值粗评：数值越小越「不值钱」，优先被蓝图取用。 */
    private static long valueScore(ItemStack stack) {
        long score = 0;
        var ench = stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
        if (ench != null && !ench.isEmpty()) {
            score += 1_000_000L + ench.size() * 1000L;
        }
        var stored = stack.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS);
        if (stored != null && !stored.isEmpty()) {
            score += 1_000_000L + stored.size() * 1000L;
        }
        if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
            score += 500_000L;
        }
        if (stack.has(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get())) {
            score += 2_000_000L;    // 千相锻造产物本身很贵，最后才考虑
        }
        // 同等条件下优先消耗损伤大的（剩余耐久越少越先用掉）
        if (stack.isDamageableItem()) {
            score += Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
        }
        return score;
    }

    /** 该玩家当前 AI 选择的指纹（客户端 SimpleContainer 恒为 0）。 */
    private int aiStateFingerprint() {
        if (!(this.container instanceof ForgeTableBlockEntity be)) {
            return 0;
        }
        var sel = be.selectionOf(ownerUuid());
        return sel.spellJson().hashCode() * 31
                + sel.customName().hashCode() * 7
                + sel.movesetJson().hashCode();
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

        int invStart = RESULT_SLOT + 1;      // 背包区起点（含快捷栏）
        int invEnd = invStart + 36;

        ItemStack stack = slot.getItem();
        ItemStack moved = stack.copy();

        if (index == RESULT_SLOT) {
            // 结果 → 背包。搬完后用完整拷贝触发 onTake（消耗材料 + 相谱 + 学法术）；
            // 原版 QUICK_MOVE 会循环调用本方法，材料够就连续锻造，背包满/材料尽自动停。
            if (!moveItemStackTo(stack, invStart, invEnd, true)) return ItemStack.EMPTY;
            // 只在真的搬空时才清槽：moveItemStackTo 部分成功也返回 true，
            // 无条件 set(EMPTY) 会吞掉余量。当前产物恒为单个栈不可触发，
            // 但产物一旦支持堆叠这就是吞物品 bug——一行防御。
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            }
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
        super.removed(player);
        // 产物槽是实时预览，材料尚未消耗——关闭界面必须清掉，
        // 否则它作为真实容器槽留在方块实体里，可被漏斗抽走（零成本无限锻造）。
        this.container.setItem(RESULT_SLOT, ItemStack.EMPTY);
        // 顺带清掉该玩家在这台锻造台上的 AI 提案与选择（不落盘，随会话有效）
        if (this.container instanceof ForgeTableBlockEntity be) {
            be.clearPlayerAiState(player.getUUID());
            // 产物槽刚被清空，状态必须跟着刷新——否则方块滞留 STATE_READY，
            // 玩家关掉界面后还能看到「可锻造」的粒子在冒。
            be.updateCraftingState();
        }
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
