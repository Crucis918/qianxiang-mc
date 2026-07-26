# 千相 × Epic Fight 适配数据包

本目录把千相的武器接入 Epic Fight（EF）21.15.6，**纯数据包，零 Java**。所有内容依据 `docs/epic-fight-compat-brief.md`（同部门侦察简报，反编译一手坐实）。未装 EF 时这些文件被原版数据包系统忽略（`capabilities/` 非原版路径），天然软依赖，不会崩溃。

加载器：EF 的 `ItemCapabilityReloadListener`（`DIRECTORY = "capabilities"`，javap 确认），游戏启动时自动扫描本目录。

---

## 文件清单

### `weapons/ember_blade.json` —— 物品→武器类型映射（层①）

把 `qianxiang:ember_blade` 绑到 EF 内置 preset `epicfight:tachi`（简报 §2.3 列出的 16 个 preset 之一，由 `WeaponCapabilityPresets.registerDefaultWeaponTypes()` 注册）。

选 `tachi` 的理由：
- 简报 §6.2 直接以 `qianxiang:ember_blade` + `epicfight:tachi` 作为最小示例，是 brief 坐实的最短路径。
- `ember_blade`（余烬之刃）语义上是一把单手带弧的刀，太刀的 `tachi_auto1/2/3` 三段连击 + dash 动作与"刃"的定位最契合。
- 一行 `"type"` 即可获得全套动作 / 连击 / 音效，零动画文件。

字段 schema（简报 §2.2 实测格式，来自 jar 内 `iron_tachi.json` 等）：
- `"type"`：必填，指向 EF 武器类型（这里用内置 preset `epicfight:tachi`）。
- `"attributes.common"`：EF 自己的三项战斗属性（非 MC 的 `ATTACK_DAMAGE`），可选。
  - `armor_negation`：护甲穿透，v1 取 `0.0`（默认）。
  - `impact`：击退/硬直，取简报示例值 `2.6`。
  - `max_strikes`：单次挥砍命中数，取 `2`。
- `"hit_particle"` / `"hit_sound"`：未写，沿用 EF 默认。

### `weapons/item_keyword/` —— 已删除（WQ-36）

曾放过一个正则兜底文件 `{ "regexes": [ "qianxiang:.*_blade" ] }`，现已删除，原因：

- 正则 `qianxiang:.*_blade` 实际只能命中 `ember_blade` / `bone_blade` 两把**已有显式映射**
  的刀——锻造台产物是复用这两个物品 id + 组件，不会产生新的 `*_blade` 物品 id，
  当初设想的"为动态产物批量挂类型"从未成立。
- 更糟的是它把 `bone_blade` 也导向 tachi 连段，而 Java 侧动态分类
  （`QianxiangEFCompat`）判定骨刃为 **dagger**，两者打架。

动态产物的动作类型由 `QianxiangEFCompat` 在运行时按 `ComposedAttributes` 特征选择，
不需要也不应该用正则兜底。

### `weapons/types/` —— 已创建（`types/qianxiang_blades.json`）

层②（`capabilities/weapons/types/*.json`）schema 已从 jar 抽取并坐实，本目录下
`qianxiang_blades.json` 定义了千相刃类武器类型（category=tachi + 自定义 collider + combos）。
当前 combos 仍复用 EF 自带的 `tachi_auto*` 动画（自创动画 JSON 格式尚未抽取，见简报 §6.4）。

---

## 材料强度与 EF 的关系（已定论：叠加，不被抹平）

早期版本这里写的是"EF capability 可能覆盖千相的 `ATTRIBUTE_MODIFIERS`、强度差异被抹平"。
**该表述已被 2026-07 对 EF 21.15.6 的字节码核实推翻**，现更正如下：

**结论：EF 不覆盖物品栈的 `minecraft:attribute_modifiers`，只叠加。材料强度差异在 EF 战斗下完整生效。**

依据（详见简报 §8 第 2 条的四条字节码证据）：

- EF 唯一挂进 vanilla 属性管线的钩子只调用 `ItemAttributeModifierEvent.addModifier`，
  全 jar 无 `replaceModifier` / `clearModifiers`；且该版本的
  `CapabilityItem.getAttributeModifiers(null)` 返回空 multimap，钩子实为空操作。
- `CapabilityItem.getAttributeModifiersAsWeapon` 是**先读栈组件、再追加** capability 的
  style attributes。
- **原版攻击路径并未被旁路**：EF 主手攻击 `PlayerPatch.attack` 最终仍
  `invokevirtual Player.attack`，伤害由玩家 `ATTACK_DAMAGE` 属性（含组件修饰符）结算。

**因此 v1 的现状就是正确形态**，不是妥协：`attributes.common` 只填 EF 的三项 souls-like
参数（`armor_negation` / `impact` / `max_strikes`），材料强度完全交给栈组件。

**⚠️ 反面警告**：不要把 `ComposedAttributes` 镜像进 EF 的 `addStyleAttibutes`
（EF 的方法名拼写就是这样，少一个 r）。那样会让副手双持路径与 EF 武器面板**双重计数**，
把本来正确的数值搞错。早期文档里"v2 把材料属性注入 capability"的方向是基于错误前提的，已作废。

**升级 EF 版本后的冒烟验证**：`/give` 两把 `attribute_modifiers` 差异极大的 `ember_blade`，
各打盔甲架对比掉血；有差异即说明叠加语义仍成立。

---

## 其他

- 命名空间全部用 `qianxiang`（物品映射目录）和 `epicfight`（被引用的 preset），与简报 §2.2 "命名空间很重要"一致。
- `/reload` 坑（简报 §7.2 引 issue #2584）：数据包动态调试时 EF 可能丢属性；正式发布走 mod 内置资源（即本目录），不依赖运行时 reload。
- 无 `mods.toml` 改动、无 Java 代码、无 `build.gradle` 改动。
