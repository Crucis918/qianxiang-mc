package com.qianxiang.client;

import com.qianxiang.ai.MaterialLibrary;
import com.qianxiang.phase.EffectMaterialResolver;
import com.qianxiang.phase.ItemConceptResolver;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseFunctionResolver;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「材料筛选」客户端状态：玩家勾选「想让 AI 使用」的材料。
 * <p>
 * 列表来自概念引擎全量快照（{@link MaterialLibrary#snapshot()}：万物皆零件，
 * 自定义相材料 + 原版/数据包物品的概念推导），<b>不只列背包已有的</b>——
 * 玩家可以先勾选「想用」的材料设计蓝图，再按 AI 方案去采集。
 * 每条材料实时标注拥有状态（背包统计数量；未拥有 = 灰色「未获得」）。
 * <p>
 * 勾选状态只存客户端：内部记录<b>未勾选</b>集合（默认空 = 默认全选），
 * 本次游戏会话内跨 GUI 打开保留。
 * <p>
 * 发 {@link com.qianxiang.network.AiRequestPayload} 时用 {@link #buildWhitelist}
 * 生成白名单；空列表 = 不限制（服务端用全部材料）。
 */
public final class ClientMaterialFilter {

    private ClientMaterialFilter() {}

    /**
     * 一条可筛选材料：物品 id + 图标 + 核心效果（功能算子/自由状态效果的本地化短名）
     * + 背包拥有数量（0 = 未获得，仅作规划勾选）。
     */
    public record Entry(ResourceLocation id, ItemStack icon, List<Component> effects, int owned) {
        /** 兼容旧三参构造（owned = 0）。 */
        public Entry(ResourceLocation id, ItemStack icon, List<Component> effects) {
            this(id, icon, effects, 0);
        }

        public String name() {
            return icon.getHoverName().getString();
        }

        /** 背包里是否拥有该材料。 */
        public boolean isOwned() {
            return owned > 0;
        }
    }

    /** 未勾选集合（默认空 = 全选）。仅客户端内存，不进存档。 */
    private static final Set<ResourceLocation> UNCHECKED = new LinkedHashSet<>();

    /**
     * 切换世界/断线时清空勾选状态（登记在 {@link ClientStateReset#resetAll}，WQ-79①）——
     * 否则旧世界的白名单会在新世界继续悄悄裁剪 AI 可用材料。
     */
    public static void resetForWorldChange() {
        UNCHECKED.clear();
    }

    public static boolean isChecked(ResourceLocation id) {
        return id != null && !UNCHECKED.contains(id);
    }

    public static void setChecked(ResourceLocation id, boolean checked) {
        if (id == null) return;
        if (checked) {
            UNCHECKED.remove(id);
        } else {
            UNCHECKED.add(id);
        }
    }

    /** 全选/全不选（只影响当前这批条目）。 */
    public static void setAll(List<Entry> entries, boolean checked) {
        if (entries == null) return;
        for (Entry e : entries) {
            setChecked(e.id(), checked);
        }
    }

    /**
     * 全量材料列表（概念引擎快照里的全部材料）+ 背包拥有数量实时统计。
     * <p>
     * 已拥有的排前面（彩色高亮 + 数量），未拥有的排后面（灰色「未获得」），
     * 同组内按显示名排序。任何异常条目单独跳过，不拖垮整个扫描。
     */
    public static List<Entry> scanAll(Inventory inv) {
        Map<ResourceLocation, Integer> ownedCounts = countInventory(inv);
        List<Entry> out = new ArrayList<>();
        for (MaterialLibrary.MaterialEntry me : safeSnapshot()) {
            try {
                ResourceLocation id = ResourceLocation.tryParse(me.registryName());
                if (id == null) continue;
                var item = BuiltInRegistries.ITEM.get(id);
                if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
                ItemStack icon = new ItemStack(item);
                int owned = ownedCounts.getOrDefault(id, 0);
                out.add(new Entry(id, icon, effectParts(icon), owned));
            } catch (Throwable t) {
                // 单个材料构建失败：跳过，不影响整表
            }
        }
        out.sort(Comparator.comparing((Entry e) -> !e.isOwned())
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** 材料库快照（异常时退化为空表，绝不让筛选界面打不开）。 */
    private static List<MaterialLibrary.MaterialEntry> safeSnapshot() {
        try {
            return MaterialLibrary.snapshot();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 统计背包（含装备/副手）里每种物品的总数量。 */
    private static Map<ResourceLocation, Integer> countInventory(Inventory inv) {
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        if (inv == null) return counts;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s;
            try {
                s = inv.getItem(i);
            } catch (Throwable t) {
                continue;
            }
            if (s == null || s.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (id == null) continue;
            counts.merge(id, s.getCount(), Integer::sum);
        }
        return counts;
    }

    /**
     * 扫描背包里的有意义材料（按显示名排序）。任何异常条目单独跳过，不拖垮整个扫描。
     * <p>旧版「只列背包已有」入口，保留向后兼容；筛选界面已改用 {@link #scanAll}。</p>
     */
    public static List<Entry> scanInventory(Inventory inv) {
        if (inv == null) return List.of();
        Map<ResourceLocation, ItemStack> found = new LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s;
            try {
                s = inv.getItem(i);
            } catch (Throwable t) {
                continue;
            }
            if (s == null || s.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (id == null || found.containsKey(id)) continue;
            if (!isMeaningful(s)) continue;
            found.put(id, s.copyWithCount(1));
        }
        List<Entry> out = new ArrayList<>();
        for (var e : found.entrySet()) {
            out.add(new Entry(e.getKey(), e.getValue(), effectParts(e.getValue())));
        }
        out.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** 「有意义」判定：功能算子非空 或 概念非空。 */
    private static boolean isMeaningful(ItemStack stack) {
        try {
            if (!PhaseFunctionResolver.get(stack).isEmpty()) return true;
            return !ItemConceptResolver.resolve(stack).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 核心效果短名：功能算子走 qianxiang.phasefn.* 键，自由状态效果走 MobEffect 显示名。 */
    private static List<Component> effectParts(ItemStack stack) {
        List<Component> out = new ArrayList<>();
        try {
            for (PhaseFunction fn : PhaseFunctionResolver.get(stack)) {
                out.add(Component.translatableWithFallback(
                        "qianxiang.phasefn." + fn.name().toLowerCase(java.util.Locale.ROOT), fn.name()));
            }
            for (ResourceLocation effectId : EffectMaterialResolver.get(stack, PhaseTier.COMMON).keySet()) {
                BuiltInRegistries.MOB_EFFECT.getHolder(effectId)
                        .ifPresent(h -> out.add(h.value().getDisplayName()));
            }
        } catch (Throwable t) {
            // 效果解析失败：该行只显示图标+名称
        }
        return out;
    }

    /**
     * 构建发给服务端的材料白名单：全量材料中被勾选的材料 registry 名。
     * <p>
     * 返回空列表 = 不限制，两种情况：①玩家一个都没取消勾选（默认状态，
     * 此时不发送全量大表以省带宽）；②玩家把全部取消勾选了——服务端按
     * 全部材料处理（与旧版语义一致，见 FallbackRecipes 的保底逻辑）。
     */
    public static List<String> buildWhitelist(Inventory inv) {
        if (UNCHECKED.isEmpty()) {
            return List.of(); // 默认全选 = 不限制（也避免发送整张全量表）
        }
        List<String> out = new ArrayList<>();
        for (Entry e : scanAll(inv)) {
            if (isChecked(e.id())) {
                out.add(e.id().toString());
            }
        }
        return out;
    }
}
