# EF 自定义武器动作（CUSTOM_MOVESET）实现笔记

玩家用自然语言描述攻击动作（「三段连斩」「突进」……），AI 从 Epic Fight 动画库中
**挑选现有动画**组合成连击，产物武器在 EF 战斗模式（按 R）下的普攻就是这套动作。

本文档记录契约 schema、动画/判定盒引用方式与硬限制。涉及代码：

- `com.qianxiang.combat.WeaponMoveset`：契约 record + movesetJson 解析（AI 链路侧提供）
- `com.qianxiang.combat.AnimationLibrary`：EF 动画的语义索引（给 LLM 的挑选清单）
- `com.qianxiang.QianxiangDataComponents#CUSTOM_MOVESET`：`qianxiang:custom_moveset` 组件
- `com.qianxiang.compat.QianxiangEFCompat`：应用侧，运行时把 moveset 变成 EF WeaponCapability

## 1. 组件 schema

`qianxiang:custom_moveset` → `WeaponMoveset(String category, List<ResourceLocation> combos, String colliderPreset)`

```json
{
  "category": "tachi",
  "combos": ["tachi_auto1", "tachi_auto2", "tachi_auto3", "tachi_dash"],
  "collider": "tachi"
}
```

| 字段 | 含义 |
| --- | --- |
| `category` | EF 武器类别名（小写）。决定底座预设（属性、风格切换、握持/跑动动作）与 `WeaponCategory`。 |
| `combos` | 连击动画序列，按顺序对应普攻第 1/2/3… 段。空 = 组件无效，走特征分类。 |
| `collider` | 攻击判定盒。内置预设名或 `custom:` 参数，见 §3。 |

- `WeaponMoveset` 规范构造会把空 category 归一为 `tachi`、空 collider 归一为 category，
  所以应用侧读到的字段永远非空。
- AI 输出的 `movesetJson` 由 `WeaponMoveset.fromJson` 容错解析：裸名 `tachi_auto1`
  与 `epicfight:tachi_auto1` 都自动补全为 `epicfight:biped/combat/tachi_auto1`；
  单条非法 id 跳过，整体无有效动画返回 null（调用方回退默认太刀动作集）。

## 2. 动画引用方式（EF 21.15.6 字节码核实）

- EF 动画 id 形如 `epicfight:biped/combat/<名称>`，动画文件在 EF jar 的
  `assets/epicfight/animmodels/animations/biped/combat/*.json`（500+ 条，其中 combat 动画 100+）。
- 代码解析用 `AnimationManager.byKey(ResourceLocation)` —— 是对 `animationByName`
  的 **map 查找**：动画尚未加载（datapack reload 未完成）或 id 不存在时**返回 null**，不抛异常。
- 应用侧把 `combos` 逐个 `byKey` 解析为 `AnimationAccessor<AttackAnimation>`，
  全部 miss 时不构建、**不做负缓存**（下次 capability 查询重试，首次查询通常已在进世界后，
  动画已就绪）；部分 miss 则跳过坏段、用剩余段构建并缓存。
- 连击通过 `WeaponCapability.Builder.newStyleCombo(Style, accessor...)` 设置——
  已核实该方法是对 `autoAttackMotionMap` 的 `put`，即**同风格整体替换**预设连段。
  应用侧对 `ONE_HAND` / `TWO_HAND` / `COMMON` 三个风格都写入同一序列，
  保证任何持法（单手/副手盾/双手）普攻都是自定义连段；`SHEATH`（居合）、`MOUNT`
  等特殊风格保留底座预设原样。

## 3. category 与 collider

### category

可用作底座预设的类别（`QianxiangEFCompat.CATEGORY_PRESETS`）：
`greatsword / dagger / spear / longsword / tachi / uchigatana / sword / axe / fist`。

- 未知名（或 `trident`/`shield` 这类非 `WeaponCapability` 的类别）→ 底座退回
  特征分类的原型（重型→greatsword 等），不接管时回退整个 moveset 分支。
- `WeaponCategory` 覆盖用内置枚举 `CapabilityItem.WeaponCategories.valueOf`，
  未知名跳过（保留底座的 category），仅影响 EF 的类别图标/技能匹配，不影响动画。

### collider

- 内置预设：直接写名字（`tachi / longsword / greatsword / dagger / spear / sword /
  uchigatana / fist / tools`……），可带 `epicfight:` 命名空间。经
  `ColliderPreset.get(ResourceLocation)` 取实例（EF 内置预设均注册在 `epicfight:` 下）。
- 自定义参数：`custom:<段数>:<宽x,高y,长z>:<中心x,中心y,中心z>`，
  对应 `new MultiOBBCollider(number, sx, sy, sz, cx, cy, cz)`，
  参数含义与 EF datapack collider 的 `number/size/center` 一致。
  例：`custom:3:0.4,0.4,0.6:0,0,-0.1`（即 EF 内置 dagger 的参数）。
- 解析失败（找不到预设/参数非法）→ 警告一次并沿用底座预设的判定盒，不会让武器没动作。

## 4. 行为与回退链

优先级：`CUSTOM_MOVESET` 组件 → 特征分类（重型→greatsword 等）→ EF 静态 JSON。

- 无 moveset 的武器：行为与之前完全一致（特征分类 + 静态 JSON 兜底）。
- moveset 构建抛硬异常：只拉黑**该 moveset**（`MOVESET_BLACKLIST`），
  回退特征分类；不动 `failed` 总开关，其他武器不受影响。
- 缺失动画/非法判定盒/未知 category：`MOVESET_WARNED` 去重，同一问题只警告一次。
- capability 缓存：`MOVESET_CACHE` 以 moveset record（equals/hashCode 稳定）为键，
  同一动作集的多个产物共享同一 capability 实例。

## 5. 硬限制

- **AI 只能组合 EF 已有动画，不能生成新动画。** 动画是 EF 预制的骨骼动画 json
  （`animmodels/animations/**`），mod 不做骨骼动画生成。`AnimationLibrary.promptSummary()`
  给 LLM 的清单已声明此边界；应用侧的 `byKey` 解析是最后防线（库外/编造 id 直接 miss）。
- 连段是「顺序播放的整段动画」，段间衔接手感由 EF 的 combo 机制决定；
  AI 不能调动画内部的时间轴/判定帧（那是动画文件内置的）。
- 突进（dash）/跳劈（airslash）动画放进 combos 后作为普通连段播放；
  EF 原生的「冲刺+攻击自动放 dash 动画」行为由底座预设与动画自身属性决定，不在本链路控制。
- 盾（shield）/三叉戟（trident）无 `WeaponCapability` 武器预设，moveset 对它们不适用
  （盾保持原有格挡动作）。
