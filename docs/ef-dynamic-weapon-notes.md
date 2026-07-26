# Epic Fight 动态武器动作适配笔记

> 代码：`src/main/java/com/qianxiang/compat/QianxiangEFCompat.java`
> 目标：让武器动作真正匹配武器特征（重/轻/长柄/杖/盾），不再只是给固定物品挂固定 EF 类型 JSON。

## 结论：EF 21.15.6 支持「代码级动态指定」，无需 NBT 变体物品

读 EF jar 字节码核实的关键事实：

1. **EF 的挂载点**：`EpicFightMod.registerCapabilities` 在 `RegisterCapabilitiesEvent` 里
   遍历 `BuiltInRegistries.ITEM`，给**所有物品**注册同一个
   `CommonItemCapabilityProvider`（`EpicFightCapabilities.ITEM_CAPABILITY_PROVIDER`）。
2. **EF provider 未命中时返回 null**：其 `getCapability(stack, ctx)` 只在自己
   `capabilities` map（由 datapack `capabilities/weapons/*.json` 在 reload 时填充）
   含有的物品上返回非 null；否则返回 null。另有 `RuntimeCapability` 机制
   （条件 → 变体 capability），但需写入会被 reload 清空的内部 map，不适合外部 mod。
3. **NeoForge 按注册顺序取首个非 null**：`ItemCapability.getCapability` 遍历该物品的
   provider 列表，返回第一个非 null 结果。mod-bus 事件按 mod 依赖排序派发，
   所以 `neoforge.mods.toml` 里把对 `epicfight` 的 `ordering` 从 `AFTER` 改成 `BEFORE` 后，
   千相的 provider 先于 EF 注册，对千相产物**优先生效**。

因此方案是：千相自己注册一个 `ICapabilityProvider<ItemStack, Void, CapabilityItem>`，
按堆叠上的 `ComposedAttributes` 组件实时分类，返回用 EF 官方
`WeaponCapabilityPresets` 预建的 capability（GREATSWORD / DAGGER / SPEAR /
LONGSWORD / TACHI / SHIELD），并按原型缓存复用。

## 特征 → 动作映射（阈值在 QianxiangEFCompat 顶部可调）

| 特征 | 判定 | EF 动作 |
|---|---|---|
| 重型 | attackDamage ≥ 6.0 且攻速加成 ≤ 0.6 | greatsword |
| 轻型 | 攻速加成 ≥ 0.8，或外观键 `bone`（骨刃） | dagger |
| 长柄 | `QianxiangToolItem`（相锄等） | spear |
| 法系相杖 | phase_staff（EF 无杖类） | dagger 快速动作 |
| 盾 | phase_shield | shield |
| 标准金属刃兜底 | 其余 QianxiangWeaponItem | tachi（与旧静态 JSON 一致） |

阈值与 `AttributeScheme` 基底值配套：EDGE 每件 +3.0 攻（两件 6.0 → 重型），
BASE_METAL 每件 +0.4 攻速（两件 0.8 → 轻型，一件 0.4 → 标准）。

## 覆盖的物品

ember_blade、bone_blade、phase_staff、phase_shield、phase_hoe。
水壶不作为武器，未注册。

## 兼容性与兜底

- **旧 4 个静态 JSON 全部保留**（`data/qianxiang/capabilities/weapons/`）：
  它们在动态 provider 返回 null（被禁用/异常/未覆盖物品）时继续生效。
- 动态适配整体开关：`QianxiangEFCompat.ENABLED`；任何异常 try-catch 吞掉并记一次
  warn，自动回退静态 JSON 行为，不影响游戏。
- 若 `ordering = "BEFORE"` 排序在某种环境下失效，EF provider 先命中静态 JSON，
  行为 = 旧版，不会坏（优雅降级）。
- 旧存档/旧产物：capability 不进存档，纯运行时解析；空壳（无组件）走兜底分支。

## 实测方式

1. `./gradlew build` 通过。
2. `./gradlew runClient` 进世界，日志应出现
   `[Qianxiang] EpicFight 动态武器动作适配已注册`，且**无** EF capability 相关 warn。
3. 进 EF 战斗模式（默认按 `R` 切 mining/combat）实测：
   - 金属+金属+锋锐（高攻速）产物 → 匕首快连段；
   - 金属+双锋锐（攻 ≥ 6）产物 → 大剑慢重击；
   - 骨刃 → 匕首；相杖 → 快速连击；相盾 → 举盾格挡；相锄 → 长枪突刺。
4. 验证兜底：把 `QianxiangEFCompat.ENABLED` 改 false，重新进游戏，
   动作应回到静态 JSON 的 tachi/dagger/shield 固定映射。

## 限制

- 动态优先级依赖 mod 排序 → 事件派发顺序这一 NeoForge 行为；这是唯一一处
  非显式 API 的假设，失效时退化为旧行为。
- 用的是 EF 官方预设，动作组合与原版 EF 武器一致；要做「自定义连段」仍需
  datapack 自定义 type（现有 `types/qianxiang_blades.json` 即一例），二者可并存。
- preset 的 innate skill（如大剑的固有技能）随预设一起生效——这通常是特性，
  但若后续要「无固有技能的纯净动作」，需要改用 `WeaponCapability.builder()` 自建。
