package com.qianxiang.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qianxiang.QianxiangItems;
import com.qianxiang.phase.ForgeComposer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 「千相蓝图」——记住一次成功锻造的配方。
 * <p>
 * 仅保存材料的 registry name 列表 + 产物类型 + 强度 + 名称，
 * 不保存具体属性对象；需要时可以通过 {@link ForgeComposer#compose} 重新生成结果。
 * <p>
 * {@code spellJson}（可空）：锻造时最近一次 AI 响应附带的自由法术描述，
 * 随蓝图一并保存；使用蓝图时重新暂存到锻造台方块实体，产物重新应用 AI 法术/名称。
 * <p>
 * {@code movesetJson}（可空）：锻造时最近一次 AI 响应附带的 EF 动作定制描述
 * （契约：category/combos/collider），随蓝图一并保存；使用蓝图时重新暂存，
 * 产物重新写入 CUSTOM_MOVESET 组件。
 * 序列化用 optionalFieldOf 兼容——旧存档/旧网络的蓝图没有这两个字段，读出来为 null。
 */
public record BlueprintData(List<String> materials, String productType, double power, String name,
                            String spellJson, String movesetJson) {

    /** 兼容旧四参构造：无 spellJson/movesetJson。 */
    public BlueprintData(List<String> materials, String productType, double power, String name) {
        this(materials, productType, power, name, null);
    }

    /** 兼容旧五参构造：无 movesetJson。 */
    public BlueprintData(List<String> materials, String productType, double power, String name, String spellJson) {
        this(materials, productType, power, name, spellJson, null);
    }

    public static final Codec<BlueprintData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().fieldOf("materials").forGetter(BlueprintData::materials),
            Codec.STRING.fieldOf("product_type").forGetter(BlueprintData::productType),
            Codec.DOUBLE.fieldOf("power").forGetter(BlueprintData::power),
            Codec.STRING.fieldOf("name").forGetter(BlueprintData::name),
            // 可空字段：缺省空串；null ↔ "" 互转，旧蓝图无此键也能解码。
            Codec.STRING.optionalFieldOf("spell_json", "").forGetter(d -> d.spellJson() == null ? "" : d.spellJson()),
            Codec.STRING.optionalFieldOf("moveset_json", "").forGetter(d -> d.movesetJson() == null ? "" : d.movesetJson())
    ).apply(instance, (materials, productType, power, name, spellJson, movesetJson) ->
            new BlueprintData(materials, productType, power, name,
                    spellJson.isEmpty() ? null : spellJson,
                    movesetJson.isEmpty() ? null : movesetJson)));

    public static final StreamCodec<FriendlyByteBuf, BlueprintData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), BlueprintData::materials,
            ByteBufCodecs.STRING_UTF8, BlueprintData::productType,
            ByteBufCodecs.DOUBLE, BlueprintData::power,
            ByteBufCodecs.STRING_UTF8, BlueprintData::name,
            ByteBufCodecs.STRING_UTF8, d -> d.spellJson() == null ? "" : d.spellJson(),
            ByteBufCodecs.STRING_UTF8, d -> d.movesetJson() == null ? "" : d.movesetJson(),
            (materials, productType, power, name, spellJson, movesetJson) ->
                    new BlueprintData(materials, productType, power, name,
                            spellJson.isEmpty() ? null : spellJson,
                            movesetJson.isEmpty() ? null : movesetJson));

    /** 根据当前锻造结果生成一份蓝图（无 spellJson 的旧入口）。 */
    public static BlueprintData fromComposition(ForgeComposer.Composition composition, List<ItemStack> materialStacks) {
        return fromComposition(composition, materialStacks, null);
    }

    /**
     * 根据当前锻造结果生成一份蓝图（无 movesetJson 的旧入口）。
     *
     * @param spellJson 锻造时暂存在锻造台上的 AI spellJson（可空），随蓝图保存
     */
    public static BlueprintData fromComposition(ForgeComposer.Composition composition, List<ItemStack> materialStacks,
                                                String spellJson) {
        return fromComposition(composition, materialStacks, spellJson, null);
    }

    /**
     * 根据当前锻造结果生成一份蓝图。
     *
     * @param spellJson   锻造时暂存在锻造台上的 AI spellJson（可空），随蓝图保存
     * @param movesetJson 锻造时暂存在锻造台上的 AI movesetJson（可空），随蓝图保存
     */
    public static BlueprintData fromComposition(ForgeComposer.Composition composition, List<ItemStack> materialStacks,
                                                String spellJson, String movesetJson) {
        List<String> names = new ArrayList<>();
        for (ItemStack stack : materialStacks) {
            if (stack == null || stack.isEmpty()) continue;
            names.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        String productType = inferProductType(composition.result());
        double power = composition.attributes() != null ? composition.attributes().powerScore() : 0.0;
        String name = composition.result().getHoverName().getString();
        return new BlueprintData(names, productType, power, name,
                spellJson == null || spellJson.isBlank() ? null : spellJson,
                movesetJson == null || movesetJson.isBlank() ? null : movesetJson);
    }

    private static String inferProductType(ItemStack result) {
        if (result == null || result.isEmpty()) return "weapon";
        Item item = result.getItem();
        if (item == QianxiangItems.PHASE_STAFF.get()) return "magic";
        if (item == QianxiangItems.PHASE_SHIELD.get()) return "armor";
        if (item == QianxiangItems.EMBER_BLADE.get() || item == QianxiangItems.BONE_BLADE.get()) return "weapon";
        return "tool";
    }
}
