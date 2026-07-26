package com.qianxiang.phase;

import com.qianxiang.Qianxiang;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用效果材料解析器：扫描物品上的「效果 tag」，把任意 MC 状态效果引入材料体系。
 * <p>
 * 与 {@link PhaseFunctionResolver} 的固定 25 算子并存：
 * 固定算子走 {@code qianxiang:materials/<function>}，本解析器走
 * {@code qianxiang:materials/effect/<effect_path>}（如 {@code qianxiang:materials/effect/wither}）。
 * <p>
 * tag path 的 {@code <effect_path>} 段对应 MobEffect 的 registry path（原版效果即
 * {@code minecraft:<effect_path>}，如 wither/invisibility/conduit_power）。
 * tag 是数据驱动的，整合包作者可用 KubeJS / datapack 给任何物品挂任意原版状态效果，
 * 无需改代码。未注册的效果 id 会在施加时被安全跳过。
 */
public final class EffectMaterialResolver {

    /** 效果 tag 的 path 前缀：qianxiang:materials/effect/&lt;effect_path&gt;。 */
    public static final String TAG_PREFIX = "materials/effect/";

    private EffectMaterialResolver() {}

    /** 效果 tag：qianxiang:materials/effect/&lt;effect_path&gt;（如 qianxiang:materials/effect/poison）。 */
    public static TagKey<Item> tag(String effectPath) {
        return TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, TAG_PREFIX + effectPath));
    }

    /**
     * 扫描栈上的全部效果 tag，返回「MobEffect registry id → 等级」。
     * <p>
     * 等级 = 材料档位 ordinal + 1（COMMON=1 / RARE=2 / EPIC=3 / LEGENDARY=4），
     * 与「强度靠材料稀有度」原则一致。同栈多 tag 各自独立；同效果取大由调用方合并。
     *
     * @return 空 Map = 无效果 tag
     */
    public static Map<ResourceLocation, Integer> get(ItemStack stack, PhaseTier tier) {
        if (stack == null || stack.isEmpty()) return Map.of();
        int level = (tier == null ? PhaseTier.COMMON : tier).ordinal() + 1;
        Map<ResourceLocation, Integer> out = null;
        var it = stack.getTags().iterator();
        while (it.hasNext()) {
            ResourceLocation loc = it.next().location();
            if (!Qianxiang.MOD_ID.equals(loc.getNamespace())) continue;
            String path = loc.getPath();
            if (!path.startsWith(TAG_PREFIX)) continue;
            String effectPath = path.substring(TAG_PREFIX.length());
            // tag path 不能含 ':'，modded 效果以 <namespace>_<path> 形式暂不支持，仅原版命名空间。
            if (effectPath.isEmpty() || !ResourceLocation.isValidPath(effectPath)) continue;
            if (out == null) out = new HashMap<>();
            out.merge(ResourceLocation.withDefaultNamespace(effectPath), level, Math::max);
        }
        return out == null ? Map.of() : out;
    }

    /** 物品是否带任一效果 tag（供 {@link com.qianxiang.ai.MaterialLibrary} 批量扫描用）。 */
    public static boolean hasEffectTag(ItemStack stack) {
        return !get(stack, PhaseTier.COMMON).isEmpty();
    }
}
