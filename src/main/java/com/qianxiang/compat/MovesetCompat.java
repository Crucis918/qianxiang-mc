package com.qianxiang.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * EF 动作集（WeaponMoveset）反射桥接。
 * <p>
 * 契约类型 {@code AnimationLibrary}（动画语义库）、{@code WeaponMoveset}
 * （record: String category, List&lt;ResourceLocation&gt; combos, String colliderPreset）
 * 与 {@code QianxiangDataComponents#CUSTOM_MOVESET} 组件由动作集子代理提供。
 * 为了让两侧代码可独立编译/合并，本类全部走反射接入：
 * <ul>
 *   <li>对方落地前：{@link #promptSummary()} 用内置动画表（已对照 EF 21.15.6 jar 核实），
 *       {@link #applyMoveset} 静默返回 false（产物不写字段，不报错）。</li>
 *   <li>对方落地后：自动改用 AnimationLibrary.promptSummary()，
 *       applyMoveset 把 WeaponMoveset 写入产物 CUSTOM_MOVESET 组件。</li>
 * </ul>
 * 任何一步失败都吞异常记日志——动作定制是锦上添花，不能拖垮锻造。
 */
public final class MovesetCompat {

    private MovesetCompat() {}

    /** AnimationLibrary 候选类名（动作集子代理可能放置的包）。 */
    private static final String[] LIBRARY_CLASS_CANDIDATES = {
            "com.qianxiang.combat.AnimationLibrary",
            "com.qianxiang.compat.AnimationLibrary",
            "com.qianxiang.phase.AnimationLibrary",
            "com.qianxiang.ai.AnimationLibrary"
    };

    /**
     * 内置动画语义表：与 EF 21.15.6 jar 内
     * {@code assets/epicfight/animmodels/animations/biped/combat/*} 逐一核实。
     * AnimationLibrary 未落地时供 AI prompt 使用；分组语义与契约 AnimType 一致
     * （COMBO/DASH/AIRSLASH/HEAVY/FAST/DUAL/SHEATH/LIECHTENAUER/MOUNT）。
     */
    private static final String BUILTIN_PROMPT_SUMMARY = """
            - COMBO 连斩序列：tachi_auto1/tachi_auto2/tachi_auto3、sword_auto1~sword_auto4、dagger_auto1~dagger_auto3、longsword_auto1~longsword_auto3、greatsword_auto1/greatsword_auto2、axe_auto1/axe_auto2、spear_onehand_auto、spear_twohand_auto1/spear_twohand_auto2、uchigatana_auto1~uchigatana_auto3
            - DASH 突刺/冲刺：dagger_dash、longsword_dash、sword_dash、tachi_dash、axe_dash、spear_dash、greatsword_dash、uchigatana_dash、dagger_dual_dash、sword_dual_dash
            - AIRSLASH 跳劈/空斩：longsword_airslash、greatsword_airslash、dagger_airslash、sword_airslash、axe_airslash、spear_onehand_airslash、spear_twohand_airslash、uchigatana_airslash
            - HEAVY 重劈：greatsword_auto1/greatsword_auto2、axe_auto1/axe_auto2
            - FAST 快攻：dagger_auto1~dagger_auto3、fist_auto1~fist_auto3
            - DUAL 双刀：dagger_dual_auto1~dagger_dual_auto4、sword_dual_auto1~sword_dual_auto3
            - SHEATH 拔刀/居合：uchigatana_sheath_auto、uchigatana_sheath_dash、uchigatana_sheath_airslash
            - LIECHTENAUER 德式剑术：longsword_liechtenauer_auto1~longsword_liechtenauer_auto3
            - MOUNT 骑乘攻击：sword_mount_attack、spear_mount_attack
            （以上均为 epicfight:biped/combat/ 下的动画 id，如 tachi_auto1 = epicfight:biped/combat/tachi_auto1）""";

    // ===================== prompt 动画库摘要 =====================

    /**
     * 动画库摘要：优先反射调用动作集子代理的 {@code AnimationLibrary.promptSummary()}，
     * 类不存在/调用失败时退内置表（内置表已与 EF jar 核实，永远可用）。
     */
    public static String promptSummary() {
        for (String className : LIBRARY_CLASS_CANDIDATES) {
            try {
                Class<?> cls = Class.forName(className);
                Method m = cls.getDeclaredMethod("promptSummary");
                if (!Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                Object result = m.invoke(null);
                if (result instanceof String s && !s.isBlank()) {
                    return s;
                }
            } catch (ClassNotFoundException e) {
                // 该候选包没有 AnimationLibrary，试下一个
            } catch (Throwable t) {
                Qianxiang.LOGGER.warn("[Qianxiang] 反射调用 AnimationLibrary.promptSummary 失败，退内置动画表：{}",
                        t.toString());
                break;
            }
        }
        return BUILTIN_PROMPT_SUMMARY;
    }

    // ===================== movesetJson → 产物 CUSTOM_MOVESET =====================

    /** 解析结果缓存：null=未解析；EMPTY=解析失败（环境无动作集支持）。 */
    private static volatile MovesetTarget resolvedTarget = null;
    private static volatile boolean resolveFailed = false;

    /** 反射解析出的写入目标：CUSTOM_MOVESET 组件类型 + WeaponMoveset 构造器。 */
    private record MovesetTarget(DataComponentType<Object> componentType, Constructor<?> constructor) {}

    /**
     * 把 movesetJson（{"category","combos","collider"}）解析为 WeaponMoveset 写入产物
     * CUSTOM_MOVESET 组件。任意产物类型都可调用。
     *
     * @return true=已写入；false=JSON 无效或运行环境尚无动作集支持（静默降级）
     */
    public static boolean applyMoveset(ItemStack stack, String movesetJson) {
        if (stack == null || stack.isEmpty() || movesetJson == null || movesetJson.isBlank()) {
            return false;
        }
        try {
            WeaponMovesetSpec spec = parse(movesetJson);
            if (spec == null) return false;
            MovesetTarget target = resolveTarget();
            if (target == null) return false;  // 动作集子代理未落地：静默降级
            Object moveset = target.constructor().newInstance(
                    spec.category(), spec.combos(), spec.collider());
            stack.set(target.componentType(), moveset);
            return true;
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 写入 CUSTOM_MOVESET 失败（不影响产物）：{}", t.toString());
            return false;
        }
    }

    /** movesetJson 的已校验形态。 */
    private record WeaponMovesetSpec(String category, List<ResourceLocation> combos, String collider) {}

    /**
     * 解析并校验 movesetJson：combos 逐个过 {@link ResourceLocation#tryParse}，
     * 去重、上限 4 个；collider 缺省取 category。任一步不合法 → null。
     */
    private static WeaponMovesetSpec parse(String movesetJson) {
        JsonObject obj = JsonParser.parseString(movesetJson).getAsJsonObject();
        String category = obj.has("category") && obj.get("category").isJsonPrimitive()
                ? obj.get("category").getAsString().trim() : "";
        if (category.isEmpty()) return null;

        LinkedHashSet<ResourceLocation> combos = new LinkedHashSet<>();
        if (obj.has("combos") && obj.get("combos").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("combos")) {
                if (!el.isJsonPrimitive()) continue;
                ResourceLocation id = ResourceLocation.tryParse(el.getAsString().trim());
                if (id != null) combos.add(id);
                if (combos.size() >= 4) break;
            }
        }
        if (combos.isEmpty()) return null;

        String collider = obj.has("collider") && obj.get("collider").isJsonPrimitive()
                ? obj.get("collider").getAsString().trim() : "";
        if (collider.isEmpty()) collider = category;

        return new WeaponMovesetSpec(category, List.copyOf(combos), collider);
    }

    /**
     * 反射解析写入目标（只解析一次）：
     * <ol>
     *   <li>取 {@code QianxiangDataComponents.CUSTOM_MOVESET} 字段值（DeferredHolder/Supplier）
     *       → DataComponentType。</li>
     *   <li>从字段泛型签名里捞出 WeaponMoveset 的实际 Class（与所在包无关），
     *       取其规范构造器 (String, List, String)。</li>
     * </ol>
     * 字段不存在（动作集子代理未合并）→ 返回 null 并记住失败，不再重复反射。
     */
    @SuppressWarnings("unchecked")
    private static MovesetTarget resolveTarget() {
        if (resolveFailed) return null;
        MovesetTarget cached = resolvedTarget;
        if (cached != null) return cached;
        synchronized (MovesetCompat.class) {
            if (resolvedTarget != null) return resolvedTarget;
            if (resolveFailed) return null;
            try {
                Field field = QianxiangDataComponents.class.getField("CUSTOM_MOVESET");
                Object holder = field.get(null);
                Object componentType = holder instanceof Supplier<?> supplier ? supplier.get() : holder;
                if (!(componentType instanceof DataComponentType<?> type)) {
                    throw new IllegalStateException("CUSTOM_MOVESET 不是 DataComponentType");
                }
                Class<?> movesetClass = findWeaponMovesetClass(field.getGenericType());
                if (movesetClass == null) {
                    throw new ClassNotFoundException("无法从 CUSTOM_MOVESET 泛型签名定位 WeaponMoveset");
                }
                Constructor<?> ctor = movesetClass.getDeclaredConstructor(String.class, List.class, String.class);
                ctor.setAccessible(true);
                resolvedTarget = new MovesetTarget((DataComponentType<Object>) type, ctor);
                Qianxiang.LOGGER.info("[Qianxiang] EF 动作集支持已接入：{} → CUSTOM_MOVESET",
                        movesetClass.getName());
                return resolvedTarget;
            } catch (NoSuchFieldException e) {
                // 动作集子代理尚未合并：正常降级路径，不刷屏
                resolveFailed = true;
                return null;
            } catch (Throwable t) {
                resolveFailed = true;
                Qianxiang.LOGGER.warn("[Qianxiang] 解析 CUSTOM_MOVESET 反射目标失败，动作定制不可用：{}",
                        t.toString());
                return null;
            }
        }
    }

    /** 在泛型签名树里递归找简单名为 WeaponMoveset 的 Class。 */
    private static Class<?> findWeaponMovesetClass(Type type) {
        if (type instanceof Class<?> cls) {
            return "WeaponMoveset".equals(cls.getSimpleName()) ? cls : null;
        }
        if (type instanceof ParameterizedType pt) {
            Class<?> found = findWeaponMovesetClass(pt.getRawType());
            if (found != null) return found;
            for (Type arg : pt.getActualTypeArguments()) {
                found = findWeaponMovesetClass(arg);
                if (found != null) return found;
            }
        }
        if (type instanceof WildcardType wt) {
            for (Type bound : wt.getUpperBounds()) {
                Class<?> found = findWeaponMovesetClass(bound);
                if (found != null) return found;
            }
        }
        return null;
    }
}
