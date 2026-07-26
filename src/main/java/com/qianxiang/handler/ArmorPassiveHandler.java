package com.qianxiang.handler;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.item.QianxiangArmorItem;
import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.phase.ReverseEffectTable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 穿戴效果处理器：让千相防具的被动效果（夜视/迅捷/跳跃/抗性/抗火/水下呼吸/再生）
 * 在玩家身上真正生效。
 * <p>
 * 玩家说「夜视头盔」「疾行之靴」——AI 拼出带对应功能算子的材料，
 * 锻造出的防具在 {@link ComposedAttributes#effects()} 里就带了等级，
 * 这里负责把全身四件千相防具的等级汇总后兑现成药水面效果。
 * </p>
 *
 * <h3>机制</h3>
 * <ul>
 *   <li>每 {@value #REFRESH_INTERVAL_TICKS} tick 在服务端扫一次玩家四个护甲槽。</li>
 *   <li>全身千相防具的同种效果等级<b>相加</b>得总等级，药水等级 = 总等级 - 1
 *       （封顶 IV 级，防止多件套叠出无敌抗性）。</li>
 *   <li>续杯式：效果时长 {@value #EFFECT_DURATION_TICKS} tick（11 秒），
 *       远长于刷新间隔，脱掉防具后最多 11 秒自然消退；
 *       夜视 &gt; 10 秒也不会出现末段频闪。</li>
 *   <li>反转材料（产物带 {@link ComposedAttributes#isReversed()} 反转标志）：
 *       grantedEffects / igniteLevel 命中 {@link ReverseEffectTable} 的攻击效果
 *       不再施加给玩家自己，改为免疫（移除该负面效果）+ 反制（续杯正面效果）。
 *       <b>无</b>反转标志的防具：携带效果不施加给玩家，由
 *       {@link com.qianxiang.combat.ArmorEffectHandler} 兑现为「接触反伤」。</li>
 * </ul>
 *
 * <h3>防御原则</h3>
 * 全程 try-catch 吞异常 + 仅服务端执行（{@code !level.isClientSide()}），
 * 绝不让穿戴效果拖垮玩家 tick。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class ArmorPassiveHandler {

    /** 刷新间隔：每秒重算一次全身效果等级。 */
    private static final int REFRESH_INTERVAL_TICKS = 20;
    /** 续杯时长：11 秒（> 200 tick，夜视不频闪）。 */
    private static final int EFFECT_DURATION_TICKS = 220;
    /** 药水等级封顶：IV 级（amplifier 3）。 */
    private static final int MAX_AMPLIFIER = 3;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private ArmorPassiveHandler() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        try {
            Player player = event.getEntity();
            Level level = player.level();
            if (level.isClientSide()) {
                return;
            }
            if (level.getGameTime() % REFRESH_INTERVAL_TICKS != 0) {
                return;
            }

            // 汇总全身千相防具的效果等级
            int nightVision = 0;
            int speedBoost = 0;
            int jumpBoost = 0;
            int resistance = 0;
            int fireResist = 0;
            int waterBreath = 0;
            int regeneration = 0;
            // 反转材料：点燃等级（武器=灼烧 → 反转防具=抗火），同效果跨件相加（仅反转件计入）
            int ignite = 0;
            // 通用自由效果（grantedEffects）：effect tag 材料贡献，同效果跨件相加（仅反转件计入）
            Map<ResourceLocation, Integer> grantedTotals = new HashMap<>();

            for (EquipmentSlot slot : ARMOR_SLOTS) {
                ItemStack stack = player.getItemBySlot(slot);
                if (!(stack.getItem() instanceof QianxiangArmorItem)) {
                    continue;
                }
                ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
                if (attr == null) {
                    continue;
                }
                ComposedAttributes.EffectLevels effects = attr.effects();
                if (effects != null) {
                    nightVision += effects.nightVision();
                    speedBoost += effects.speedBoost();
                    jumpBoost += effects.jumpBoost();
                    resistance += effects.resistance();
                    fireResist += effects.fireResist();
                    waterBreath += effects.waterBreath();
                    regeneration += effects.regeneration();
                }
                // 反转门控：只有带反转标志的防具，grantedEffects/ignite 才走「抗性/免疫」兑现；
                // 无反转防具的携带效果不施加给玩家自己——由 ArmorEffectHandler 兑现为
                // 「接触反伤」（被攻击时攻击者中这些效果）。
                if (attr.isReversed()) {
                    ignite += attr.igniteLevel();
                    Map<ResourceLocation, Integer> granted = attr.grantedEffects();
                    if (granted != null) {
                        granted.forEach((id, lv) -> {
                            if (lv != null && lv > 0) grantedTotals.merge(id, lv, Integer::sum);
                        });
                    }
                }
            }

            // 续杯式给药（ambient、无粒子，避免常驻粒子刷屏）
            applyEffect(player, MobEffects.NIGHT_VISION, nightVision);
            applyEffect(player, MobEffects.MOVEMENT_SPEED, speedBoost);
            applyEffect(player, MobEffects.JUMP, jumpBoost);
            applyEffect(player, MobEffects.DAMAGE_RESISTANCE, resistance);
            applyEffect(player, MobEffects.FIRE_RESISTANCE, fireResist);
            applyEffect(player, MobEffects.WATER_BREATHING, waterBreath);
            applyEffect(player, MobEffects.REGENERATION, regeneration);

            // 通用自由效果续杯：未注册的效果 id 安全跳过。
            // 反转材料（ReverseEffectTable 命中的攻击效果）：不再把负面效果施加给玩家自己，
            // 改为兑现防具抗性——免疫（每周期移除该负面效果）+ 反制（续杯对应正面效果）。
            Map<ResourceLocation, Integer> immunityTotals = new HashMap<>();
            Map<ResourceLocation, Integer> counterTotals = new HashMap<>();
            for (var entry : grantedTotals.entrySet()) {
                ReverseEffectTable.Reversal reversal = ReverseEffectTable.get(entry.getKey());
                if (reversal != null) {
                    if (reversal.immunity()) {
                        immunityTotals.put(entry.getKey(), entry.getValue());
                    }
                    if (reversal.counterEffectId() != null) {
                        counterTotals.merge(reversal.counterEffectId(), entry.getValue(), Integer::sum);
                    }
                    continue;
                }
                try {
                    var holder = BuiltInRegistries.MOB_EFFECT.getHolder(entry.getKey());
                    holder.ifPresent(h -> applyEffect(player, h, entry.getValue()));
                } catch (Throwable t) {
                    Qianxiang.LOGGER.warn("[Qianxiang] 穿戴自由效果处理异常（已吞） id={}: {}",
                            entry.getKey(), t.toString());
                }
            }

            // 反转·点燃：防具上的灼烧等级转成抗火续杯（烈焰粉做防具 = 抗火）
            if (ignite > 0) {
                counterTotals.merge(ReverseEffectTable.IGNITE_COUNTER, ignite, Integer::sum);
            }
            // 反转·反制正面效果续杯（缓降/抗火等）
            for (var entry : counterTotals.entrySet()) {
                try {
                    var holder = BuiltInRegistries.MOB_EFFECT.getHolder(entry.getKey());
                    holder.ifPresent(h -> applyEffect(player, h, entry.getValue()));
                } catch (Throwable t) {
                    Qianxiang.LOGGER.warn("[Qianxiang] 反转反制效果处理异常（已吞） id={}: {}",
                            entry.getKey(), t.toString());
                }
            }
            // 反转·免疫：主动移除玩家身上被免疫的负面效果（凋零玫瑰做防具 = 防凋零）
            for (ResourceLocation id : immunityTotals.keySet()) {
                try {
                    var holder = BuiltInRegistries.MOB_EFFECT.getHolder(id);
                    holder.ifPresent(player::removeEffect);
                } catch (Throwable t) {
                    Qianxiang.LOGGER.warn("[Qianxiang] 反转免疫处理异常（已吞） id={}: {}",
                            id, t.toString());
                }
            }
        } catch (Throwable t) {
            // 吞掉一切异常：穿戴效果是增益，绝不能崩掉玩家 tick。
            try {
                Qianxiang.LOGGER.warn("[Qianxiang] 穿戴效果处理异常（已吞）: {}", t.toString());
            } catch (Throwable ignored) {
                // 日志也不允许再抛
            }
        }
    }

    /** 总等级 ≤0 跳过；否则续杯给药，药水等级 = 总等级 - 1（封顶 IV 级）。 */
    private static void applyEffect(Player player, net.minecraft.core.Holder<MobEffect> effect, int totalLevel) {
        if (totalLevel <= 0) {
            return;
        }
        int amplifier = Math.min(totalLevel - 1, MAX_AMPLIFIER);
        player.addEffect(new MobEffectInstance(effect, EFFECT_DURATION_TICKS, amplifier, true, false, true));
    }
}
