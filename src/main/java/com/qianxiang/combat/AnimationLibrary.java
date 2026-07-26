package com.qianxiang.combat;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Epic Fight 战斗动画库的语义索引。
 * <p>
 * 硬边界：AI 只能从这里<b>挑选组合</b> EF 已有动画，不能生成新动画。
 * 动画 id 格式 {@code epicfight:biped/combat/<weapon>_<action>}。
 * </p>
 * <h3>用法</h3>
 * <ul>
 *   <li>{@link #byType(AnimType)}：按语义类型检索（连击/突进/空斩/重劈/快攻/双持/拔刀/德剑/骑乘）。</li>
 *   <li>{@link #promptSummary()}：给 LLM 的中文动画清单文本，拼进锻造 prompt 让 AI 从中挑选。</li>
 *   <li>{@link #byId(ResourceLocation)}：反查单条动画（校验 AI 输出是否在库内）。</li>
 * </ul>
 * <p>
 * 一条动画可属于多个语义类型（如 greatsword_auto1 既是 COMBO 也是 HEAVY，
 * longsword_liechtenauer_auto1 既是 LIECHTENAUER 也是 COMBO）：
 * {@link AnimInfo#type()} 是其主类型，{@link #byType} 走交叉索引。
 * </p>
 */
public final class AnimationLibrary {

    private AnimationLibrary() {}

    /** EF 动画 id 的命名空间。 */
    public static final String EF_NAMESPACE = "epicfight";
    /** EF 战斗动画 id 的路径前缀。 */
    public static final String EF_PATH_PREFIX = "biped/combat/";

    /** 语义类型：连击/突进/空斩/重劈/快攻/双持/拔刀/德剑/骑乘。 */
    public enum AnimType {
        COMBO("combo", "连击", "普通攻击连段，多段可依次衔接"),
        DASH("dash", "突进", "向前突进的攻击，自带位移"),
        AIRSLASH("airslash", "空斩", "跳劈/空中斩击，衔接跳跃"),
        HEAVY("heavy", "重劈", "大开大合的重击，慢而重"),
        FAST("fast", "快攻", "快速连刺/连打，轻而密集"),
        DUAL("dual", "双持", "双持武器的攻击动作"),
        SHEATH("sheath", "拔刀", "居合拔刀斩（打刀专属）"),
        LIECHTENAUER("liechtenauer", "德剑", "德式长剑术（Liechtenauer）连段"),
        MOUNT("mount", "骑乘", "骑乘状态下的攻击");

        private final String key;
        private final String zhName;
        private final String desc;

        AnimType(String key, String zhName, String desc) {
            this.key = key;
            this.zhName = zhName;
            this.desc = desc;
        }

        /** 中文语义名（连击/突进/空斩/…）。 */
        public String zhName() { return zhName; }

        /** 类型一句话说明（供 prompt 使用）。 */
        public String desc() { return desc; }

        /** 翻译键：{@code qianxiang.anim_type.<key>}（zh_cn/en_us 均已登记）。 */
        public String langKey() { return "qianxiang.anim_type." + key; }
    }

    /**
     * 一条动画的语义条目。
     *
     * @param id          完整动画 id（{@code epicfight:biped/combat/...}）
     * @param weapon      武器类别（tachi/longsword/sword/dagger/axe/greatsword/fist/uchigatana/trident/spear/tool）
     * @param type        主语义类型（交叉归类的另见 {@link #byType}）
     * @param speed       快慢 1（慢）~3（快）
     * @param power       轻重 1（轻）~3（重）
     * @param displayName 中文语义名（如「太刀连击·一段」）
     */
    public record AnimInfo(ResourceLocation id, String weapon, AnimType type,
                           int speed, int power, String displayName) {}

    // ============================ 索引构建 ============================

    private static final String[] COUNT_WORDS = {"一", "二", "三", "四"};

    /** 按短路径（tachi_auto1）索引。 */
    private static final Map<String, AnimInfo> BY_PATH = new LinkedHashMap<>();
    /** 类型 → 动画列表（含交叉归类）。 */
    private static final Map<AnimType, List<AnimInfo>> BY_TYPE = new EnumMap<>(AnimType.class);

    static {
        // ---- 连击（COMBO）----
        autos("tachi", "tachi", AnimType.COMBO, 3, 2, 2, "太刀连击");
        autos("longsword", "longsword", AnimType.COMBO, 3, 2, 2, "长剑连击");
        autos("sword", "sword", AnimType.COMBO, 4, 2, 2, "剑连击");
        autos("dagger", "dagger", AnimType.COMBO, 3, 3, 1, "匕首连击");
        autos("axe", "axe", AnimType.COMBO, 2, 2, 3, "斧连击");
        autos("greatsword", "greatsword", AnimType.COMBO, 2, 1, 3, "巨剑连击");
        autos("fist", "fist", AnimType.COMBO, 3, 3, 1, "拳套连击");
        autos("uchigatana", "uchigatana", AnimType.COMBO, 3, 3, 2, "打刀连击");
        autos("trident", "trident", AnimType.COMBO, 3, 2, 2, "三叉戟连击");
        // 德式剑术主类型是 LIECHTENAUER，同时交叉归入 COMBO（见下方 crossIndex）
        autos("longsword_liechtenauer", "longsword", AnimType.LIECHTENAUER, 3, 2, 2, "德式长剑连击");

        // ---- 突进（DASH）----
        dash("tachi", "tachi", 2, "太刀突进斩");
        dash("dagger", "dagger", 1, "匕首突进刺");
        dash("sword", "sword", 2, "剑突进斩");
        dash("longsword", "longsword", 2, "长剑突进斩");
        dash("greatsword", "greatsword", 3, "巨剑突进劈");
        dash("axe", "axe", 3, "斧突进劈");
        dash("fist", "fist", 1, "拳套突进击");
        dash("spear", "spear", 2, "长枪突进刺");
        dash("uchigatana", "uchigatana", 2, "打刀突进斩");
        dash("tool", "tool", 2, "工具突进击");

        // ---- 空斩/跳劈（AIRSLASH）----
        airslash("dagger", "dagger", 3, 1, "匕首跳劈");
        airslash("sword", "sword", 2, 2, "剑跳劈");
        airslash("longsword", "longsword", 2, 2, "长剑跳劈");
        airslash("greatsword", "greatsword", 1, 3, "巨剑跳劈");
        airslash("axe", "axe", 2, 3, "斧跳劈");
        airslash("fist", "fist", 3, 1, "拳套跳击");
        airslash("spear_onehand", "spear", 2, 2, "单手枪跳劈");
        airslash("spear_twohand", "spear", 2, 3, "双手枪跳劈");
        airslash("uchigatana", "uchigatana", 3, 2, "打刀跳劈");

        // ---- 双持（DUAL）----
        autos("dagger_dual", "dagger", AnimType.DUAL, 4, 3, 1, "双持匕首连击");
        autos("sword_dual", "sword", AnimType.DUAL, 3, 3, 2, "双持剑连击");
        add("dagger_dual_dash", "dagger", AnimType.DUAL, 3, 1, "双持匕首突进");
        add("sword_dual_dash", "sword", AnimType.DUAL, 3, 2, "双持剑突进");
        add("sword_dual_airslash", "sword", AnimType.DUAL, 3, 2, "双持剑跳劈");

        // ---- 拔刀（SHEATH，打刀居合）----
        add("uchigatana_sheath_auto", "uchigatana", AnimType.SHEATH, 3, 2, "打刀拔刀斩");
        add("uchigatana_sheath_dash", "uchigatana", AnimType.SHEATH, 3, 2, "打刀拔刀突进");
        add("uchigatana_sheath_airslash", "uchigatana", AnimType.SHEATH, 3, 2, "打刀拔刀跳劈");

        // ---- 骑乘（MOUNT）----
        add("sword_mount_attack", "sword", AnimType.MOUNT, 2, 2, "剑骑乘攻击");
        add("spear_mount_attack", "spear", AnimType.MOUNT, 2, 2, "长枪骑乘攻击");

        // ---- 交叉归类（一条动画可属多个语义类型）----
        crossIndex(AnimType.COMBO,
                "longsword_liechtenauer_auto1", "longsword_liechtenauer_auto2", "longsword_liechtenauer_auto3");
        crossIndex(AnimType.HEAVY,
                "greatsword_auto1", "greatsword_auto2", "greatsword_airslash", "greatsword_dash");
        crossIndex(AnimType.FAST,
                "dagger_auto1", "dagger_auto2", "dagger_auto3",
                "fist_auto1", "fist_auto2", "fist_auto3");
    }

    private static void add(String path, String weapon, AnimType type, int speed, int power, String displayName) {
        AnimInfo info = new AnimInfo(fullId(path), weapon, type,
                clamp(speed), clamp(power), displayName);
        BY_PATH.put(path, info);
        BY_TYPE.computeIfAbsent(type, t -> new ArrayList<>()).add(info);
    }

    /** 登记 {@code <prefix>_auto1..count} 的连段，中文名自动加「·N段」。 */
    private static void autos(String pathPrefix, String weapon, AnimType type,
                              int count, int speed, int power, String cnBase) {
        for (int i = 1; i <= count; i++) {
            add(pathPrefix + "_auto" + i, weapon, type, speed, power,
                    cnBase + "·" + COUNT_WORDS[i - 1] + "段");
        }
    }

    /** 登记 {@code <weapon>_dash}：突进统一速度 3（快）。 */
    private static void dash(String weapon, String weaponKey, int power, String name) {
        add(weapon + "_dash", weaponKey, AnimType.DASH, 3, power, name);
    }

    private static void airslash(String pathPrefix, String weapon, int speed, int power, String name) {
        add(pathPrefix + "_airslash", weapon, AnimType.AIRSLASH, speed, power, name);
    }

    /** 交叉归类：把已登记的动画追加进另一个类型的索引（不去重已有项）。 */
    private static void crossIndex(AnimType type, String... paths) {
        List<AnimInfo> list = BY_TYPE.computeIfAbsent(type, t -> new ArrayList<>());
        for (String p : paths) {
            AnimInfo info = BY_PATH.get(p);
            if (info != null && !list.contains(info)) list.add(info);
        }
    }

    private static int clamp(int v) {
        return Math.max(1, Math.min(3, v));
    }

    // ============================ 查询 ============================

    /** 短路径 → 完整动画 id（{@code epicfight:biped/combat/<path>}）。 */
    public static ResourceLocation fullId(String path) {
        return ResourceLocation.fromNamespaceAndPath(EF_NAMESPACE, EF_PATH_PREFIX + path);
    }

    /** 完整动画 id → 短路径；不在本库格式内返回 null。 */
    public static String shortPath(ResourceLocation id) {
        if (id == null || !EF_NAMESPACE.equals(id.getNamespace())) return null;
        String p = id.getPath();
        return p.startsWith(EF_PATH_PREFIX) ? p.substring(EF_PATH_PREFIX.length()) : null;
    }

    /** 按语义类型检索（含交叉归类），顺序与索引登记一致。 */
    public static List<AnimInfo> byType(AnimType type) {
        return Collections.unmodifiableList(BY_TYPE.getOrDefault(type, List.of()));
    }

    /** 按短路径反查（如 {@code "tachi_auto1"}），不在库内返回 null。 */
    public static AnimInfo byPath(String path) {
        return BY_PATH.get(path);
    }

    /** 按完整 id 反查，不在库内（或不是 EF 战斗动画 id）返回 null。 */
    public static AnimInfo byId(ResourceLocation id) {
        String path = shortPath(id);
        return path == null ? null : BY_PATH.get(path);
    }

    /** 全库动画（去重，按登记顺序）。 */
    public static Collection<AnimInfo> all() {
        return Collections.unmodifiableCollection(BY_PATH.values());
    }

    /**
     * 给 LLM 的中文动画清单：按类型分组列出动画名 + 语义 + 速度/力度，
     * 并声明硬边界——只能从中挑选组合，不能编造新动画。
     */
    public static String promptSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("【Epic Fight 可用攻击动画库】只能从下列动画中挑选组合成连击，禁止编造库外动画；")
                .append("完整动画 id = epicfight:biped/combat/<名称>；速度 1慢~3快，力度 1轻~3重。\n");
        for (AnimType type : AnimType.values()) {
            List<AnimInfo> list = byType(type);
            if (list.isEmpty()) continue;
            sb.append('\n').append('[').append(type.zhName()).append(' ').append(type.name()).append(']')
                    .append(type.desc()).append('\n');
            for (AnimInfo info : list) {
                sb.append("- ").append(shortPath(info.id()))
                        .append("（").append(info.displayName())
                        .append("；武器:").append(info.weapon())
                        .append("；速度").append(info.speed())
                        .append("/力度").append(info.power())
                        .append("）\n");
            }
        }
        return sb.toString();
    }
}
