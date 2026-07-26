package com.qianxiang.spell;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 法术书数据：一本 {@link com.qianxiang.item.SpellBookItem} 里装的全部法术 + 当前选中下标。
 * <p>
 * 存于物品组件 {@code qianxiang:spellbook}（persistent + networkSynchronized），
 * 因此 tooltip 在客户端可直接读到法术列表并高亮选中项。
 * 本 record 不可变，切换选中返回新实例。
 */
public record SpellBookData(List<CustomSpell> spells, int selectedIndex) {

    public static final Codec<SpellBookData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CustomSpell.CODEC.listOf().fieldOf("spells").forGetter(SpellBookData::spells),
            Codec.INT.fieldOf("selected_index").forGetter(SpellBookData::selectedIndex)
    ).apply(instance, SpellBookData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpellBookData> STREAM_CODEC = StreamCodec.composite(
            CustomSpell.STREAM_CODEC.apply(ByteBufCodecs.list()), SpellBookData::spells,
            ByteBufCodecs.VAR_INT, SpellBookData::selectedIndex,
            SpellBookData::new
    );

    public SpellBookData {
        spells = List.copyOf(spells);
        if (selectedIndex < 0 || (selectedIndex >= spells.size() && !spells.isEmpty())) {
            selectedIndex = 0;
        }
    }

    /** 新书（创造栏/合成得来）的默认法术：火球 + 自然治愈 + 奥术飞弹。 */
    public static SpellBookData withDefaults() {
        return new SpellBookData(List.of(
                CustomSpell.FIREBALL, CustomSpell.NATURE_HEAL, CustomSpell.ARCANE_MISSILES), 0);
    }

    public boolean isEmpty() {
        return spells.isEmpty();
    }

    /** 当前选中的法术；空书返回 null。 */
    public CustomSpell selected() {
        if (spells.isEmpty()) return null;
        return spells.get(Math.min(selectedIndex, spells.size() - 1));
    }

    /** 选中下一个法术（循环）；空书返回 this。 */
    public SpellBookData cycle() {
        if (spells.size() <= 1) return this;
        return new SpellBookData(spells, (selectedIndex + 1) % spells.size());
    }
}
