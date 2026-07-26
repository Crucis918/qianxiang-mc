package com.qianxiang.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 千相蓝图分享码 —— UGC 分享的最小载体。
 * <p>
 * 把 {@link BlueprintData} 编成一段可粘贴的文本码（{@code QXBP1.} 前缀 + gzip NBT 的
 * URL-safe Base64），玩家可在聊天、论坛、群里互相分享锻造配方，
 * 对方 {@code /qianxiang blueprint import <码>} 一键入库。
 * <p>
 * 安全边界（导入侧全部强制）：
 * <ul>
 *   <li>解码字节数与 NBT 大小上限 {@value MAX_DECODED_BYTES} —— 拒绝恶意超大码。</li>
 *   <li>{@link #sanitize}：材料数 ≤ 10、名称截断 64 字符、spellJson/movesetJson
 *       截断 {@value MAX_JSON_CHARS} 字符——spellJson 本身再经 CustomSpell 白名单解析，双层防线。</li>
 *   <li>任何格式错误抛 {@link IllegalArgumentException}，调用方转为聊天报错，不崩服。</li>
 * </ul>
 * 前缀带版本号（QXBP<b>1</b>），未来格式升级可识别旧码。
 */
public final class BlueprintShareCodes {

    /** 分享码前缀（含版本）。 */
    public static final String PREFIX = "QXBP1.";

    /** 解码后字节数上限（64KB，远超正常蓝图的几百字节）。 */
    public static final int MAX_DECODED_BYTES = 64 * 1024;

    /** spellJson / movesetJson 字符数上限。 */
    public static final int MAX_JSON_CHARS = 8192;

    /** 蓝图材料数上限（锻造台就 10 个槽）。 */
    public static final int MAX_MATERIALS = 10;

    private BlueprintShareCodes() {}

    /** 蓝图 → 分享码。编码失败抛 IllegalStateException（正常数据不会发生）。 */
    public static String encode(BlueprintData data) {
        try {
            Tag tag = BlueprintData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
            CompoundTag root = new CompoundTag();
            root.put("bp", tag);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, baos);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("蓝图编码失败: " + e.getMessage(), e);
        }
    }

    /**
     * 分享码 → 蓝图（已消毒）。格式错误/超限抛 {@link IllegalArgumentException}，
     * 调用方转为聊天提示。
     */
    public static BlueprintData decode(String code) {
        String body = code == null ? "" : code.trim();
        if (!body.startsWith(PREFIX)) {
            throw new IllegalArgumentException("不是千相蓝图码（应以 " + PREFIX + " 开头）");
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(body.substring(PREFIX.length()));
        } catch (Exception e) {
            throw new IllegalArgumentException("蓝图码 Base64 解码失败");
        }
        if (bytes.length > MAX_DECODED_BYTES) {
            throw new IllegalArgumentException("蓝图码过大");
        }
        try {
            CompoundTag root = NbtIo.readCompressed(
                    new ByteArrayInputStream(bytes), NbtAccounter.create(MAX_DECODED_BYTES));
            BlueprintData data = BlueprintData.CODEC
                    .parse(NbtOps.INSTANCE, root.get("bp")).getOrThrow();
            return sanitize(data);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("蓝图码内容无效: " + e.getMessage());
        }
    }

    /** 导入消毒：截断超限字段，防外来数据撑爆存档/聊天。 */
    static BlueprintData sanitize(BlueprintData d) {
        List<String> materials = new ArrayList<>();
        for (String m : d.materials()) {
            if (m == null || m.isBlank()) continue;
            materials.add(m.length() > 256 ? m.substring(0, 256) : m);
            if (materials.size() >= MAX_MATERIALS) break;
        }
        String name = d.name() == null ? "未命名蓝图" : d.name();
        if (name.length() > 64) name = name.substring(0, 64);
        String spellJson = truncate(d.spellJson());
        String movesetJson = truncate(d.movesetJson());
        double power = Double.isFinite(d.power()) ? Math.clamp(d.power(), 0.0, 10000.0) : 0.0;
        String productType = d.productType() == null ? "weapon" : d.productType();
        return new BlueprintData(List.copyOf(materials), productType, power, name, spellJson, movesetJson);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_JSON_CHARS ? s.substring(0, MAX_JSON_CHARS) : s;
    }
}
