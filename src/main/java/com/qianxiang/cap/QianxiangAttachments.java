package com.qianxiang.cap;

import com.qianxiang.blueprint.BlueprintLibrary;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import com.qianxiang.cap.PlayerSpellData;

import static com.qianxiang.Qianxiang.MOD_ID;

/**
 * 《千相》玩家数据 Attachment 注册（NeoForge 1.21.1 新 Attachment 系统）。
 *
 * <p>查证来源：NeoForge 21.1.219 sources（gradle 缓存）——
 * <ul>
 *   <li>注册表：{@link NeoForgeRegistries#ATTACHMENT_TYPES}（Registry 实例）</li>
 *   <li>Builder：{@code AttachmentType.builder(Supplier<T>)} + {@code .serialize(Codec<T>)} + {@code .build()}</li>
 *   <li>读写：{@code IAttachmentHolder#getData(Supplier)} / {@code #setData(Supplier, T)}（Player 实现该接口）</li>
 * </ul>
 *
 * <p>注册时机：mod 总线（{@link net.neoforged.bus.api.IEventBus}），由主类 {@link com.qianxiang.Qianxiang}
 * 调用 {@link #ATTACHMENT_TYPES#register(modEventBus)}。
 */
public final class QianxiangAttachments {
    private QianxiangAttachments() {}

    /** AttachmentType 的 DeferredRegister，挂在 NeoForge 的 attachment_types 注册表上。 */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MOD_ID);

    /**
     * 相谱录 + 位格：玩家不可逆传记。
     *
     * <p>{@code copyOnDeath()}：玩家死亡后相谱保留（律二——做过的事永远烙进相谱，死亡不能洗白）。
     * copyOnDeath 要求先 serialize，已满足（{@link SagaData#CODEC}）。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<SagaData>> SAGA_DATA =
            ATTACHMENT_TYPES.register("saga_data", () -> AttachmentType.builder(SagaData::empty)
                    .serialize(SagaData.CODEC)
                    .copyOnDeath()
                    .build());

    /**
     * 千相蓝图库：玩家保存的成功锻造配方。
     *
     * <p>{@code copyOnDeath()}：死亡后蓝图保留，已铭刻的配方不会丢失。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<BlueprintLibrary>> BLUEPRINT_LIBRARY =
            ATTACHMENT_TYPES.register("blueprint_library", () -> AttachmentType.builder(BlueprintLibrary::empty)
                    .serialize(BlueprintLibrary.CODEC)
                    .copyOnDeath()
                    .build());

    /**
     * 玩家法术数据：当前/最大 mana、已学法术、冷却时间。
     *
     * <p>死亡后保留——法术与位格一样是玩家铭刻的一部分。</p>
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerSpellData>> PLAYER_SPELL_DATA =
            ATTACHMENT_TYPES.register("player_spell_data", () -> AttachmentType.builder(PlayerSpellData::empty)
                    .serialize(PlayerSpellData.CODEC)
                    .copyOnDeath()
                    .build());

    /**
     * 玩家势力/烙印数据：好感度、屠杀/外交计数、称号。
     *
     * <p>{@code copyOnDeath()}：死亡后烙印与声望保留。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerFactionData>> FACTION_DATA =
            ATTACHMENT_TYPES.register("faction_data", () -> AttachmentType.builder(PlayerFactionData::empty)
                    .serialize(PlayerFactionData.CODEC)
                    .copyOnDeath()
                    .build());
}
