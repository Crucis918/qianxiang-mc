package com.qianxiang;

import com.mojang.serialization.Codec;
import com.qianxiang.combat.WeaponMoveset;
import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.phase.PhaseData;
import com.qianxiang.spell.CustomSpell;
import com.qianxiang.spell.SpellBookData;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 自定义 DataComponent 注册。
 * <ul>
 *   <li>{@code phase_data}：材料的相之数据（功能算子 + 强度档 + 相性），AI 检索与合成的依据。</li>
 *   <li>{@code composed_attributes}：锻造台产物的组合属性（由材料的算子+档位翻译而来），
 *       存于相之武器上——强度靠材料，就在这里兑现。</li>
 *   <li>{@code affix}：裂隙试炼词缀 id（见 {@code RiftAffix}）——词缀怪掉落的词缀材料
 *       凭它在 {@code PhaseFunctionResolver} 注入对应功能算子，任何物品都能当零件。</li>
 * </ul>
 */
public final class QianxiangDataComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Qianxiang.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PhaseData>> PHASE_DATA =
            DATA_COMPONENTS.register("phase_data", () -> DataComponentType.<PhaseData>builder()
                    .persistent(PhaseData.CODEC)
                    .build());

    // 产物的组合属性：由 AttributeScheme.compose 产出，存于相之武器/装备上。
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ComposedAttributes>> COMPOSED_ATTRIBUTES =
            DATA_COMPONENTS.register("composed_attributes", () -> DataComponentType.<ComposedAttributes>builder()
                    .persistent(ComposedAttributes.CODEC)
                    .build());

    // 铭刻在相杖（或未来法器）上的法术 id。用 ResourceLocation 指向 Spell 注册表。
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> SPELL =
            DATA_COMPONENTS.register("spell", () -> DataComponentType.<ResourceLocation>builder()
                    .persistent(ResourceLocation.CODEC)
                    .networkSynchronized(ResourceLocation.STREAM_CODEC)
                    .build());

    // 单个自定义法术（元素×形式×效果×修饰 的自由组合，见 CustomSpell）。
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CustomSpell>> CUSTOM_SPELL =
            DATA_COMPONENTS.register("custom_spell", () -> DataComponentType.<CustomSpell>builder()
                    .persistent(CustomSpell.CODEC)
                    .networkSynchronized(CustomSpell.STREAM_CODEC)
                    .build());

    // 法术书内容：法术列表 + 当前选中下标（见 SpellBookItem）。
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SpellBookData>> SPELLBOOK =
            DATA_COMPONENTS.register("spellbook", () -> DataComponentType.<SpellBookData>builder()
                    .persistent(SpellBookData.CODEC)
                    .networkSynchronized(SpellBookData.STREAM_CODEC)
                    .build());

    // 裂隙试炼词缀 id（如 "ember"）。词缀材料凭此组件在 PhaseFunctionResolver.get 注入算子。
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> AFFIX =
            DATA_COMPONENTS.register("affix", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    // Epic Fight 自定义武器动作：AI 从动画库挑选的连击组合（契约字段见 WeaponMoveset）。
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<WeaponMoveset>> CUSTOM_MOVESET =
            DATA_COMPONENTS.register("custom_moveset", () -> DataComponentType.<WeaponMoveset>builder()
                    .persistent(WeaponMoveset.CODEC)
                    .networkSynchronized(WeaponMoveset.STREAM_CODEC)
                    .build());
}
