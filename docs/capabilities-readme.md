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

### `weapons/item_keyword/qianxiang_blades.json` —— 正则批量兜底（层③）

EF 自带示例 `data/epicfight/capabilities/weapons/item_keyword/longsword.json` 的格式：`{ "regexes": [ ".*_longsword" ] }`（简报 §2.4 实测）。本文件照抄 schema：

```json
{ "regexes": [ "qianxiang:.*_blade" ] }
```

**覆盖范围**：所有命名空间为 `qianxiang` 且 id 以 `_blade` 结尾的物品，含：
- 当前已注册的 `qianxiang:ember_blade`（与层①映射效果一致，重复无害）。
- 未来锻造台动态产物：`qianxiang:copper_blade`、`qianxiang:iron_blade`、`qianxiang:sunsteel_blade` 等——**一次性挂上 `epicfight:tachi`，无需为每个动态产物写 JSON**。这是适配"动态锻造台产物"的关键技巧，契合千相"锻造台即零件组装"的设计。

> 注意：层③（item_keyword）只为物品挂"默认武器类型"，当某物品已有层①显式映射时，层①优先。所以即便 `ember_blade` 同时被两条规则命中，最终生效的仍是 `ember_blade.json` 的 `tachi`（与正则结果一致，无冲突）。

### `weapons/types/` —— 未创建（schema 未坐实）

简报 §8 未确认项 #1 明确指出：层②（`capabilities/weapons/types/*.json`）的确切 schema **本次未从 jar 抽取**，只有加载器类名和 DIRECTORY 被坐实。**为不瞎编 schema，本版不创建自创武器类型**，全部复用 EF 内置 preset（`epicfight:tachi`）。需要自创专属动作时，再 `unzip <jar> 'data/epicfight/capabilities/weapons/types/*'` 取模板。

---

## v1 已知限制（重要）

**EF capability 可能覆盖千相的 `DataComponents.ATTRIBUTE_MODIFIERS`**，导致 EF 战斗下材料强度差异被抹平。

依据简报 §4：
- EF 的 `CapabilityItem` 内部持有 `Map<Style, Map<Holder<Attribute>, AttributeModifier>> attributeMap`，并在 patch 玩家时通过 `CapabilityItem.getAttributeModifiersAsWeapon(attr, slot, stack, entityPatch)` 把 capability 里的 AttributeModifier 作为"真正生效的武器属性"注入。
- EF **接管**战斗时的属性结算（伤害走 EF 的动画事件 + `attributeMap`），原版 MC 攻击路径在 EF 激活时被旁路。
- 物品自带的 `DataComponents.ATTRIBUTE_MODIFIERS`（千相锻造产物承载"强度靠材料"的核心）不会让 EF 崩，但**可能被 EF 的 capability 属性覆盖/忽略**。
- 简报 §8 未确认项 #2 直言："覆盖"还是"叠加"**未在字节码层面 100% 坐实**，需运行时实测（拿一把高伤 ember_blade 进 EF 模式打怪看伤害）。

**对千相的影响**：违背"强度靠材料"的设计灵魂——玩家在 EF 战斗下可能感觉不到不同材料锻造出的 `_blade` 在攻击力上的差异（因为 EF 统一用 `tachi` preset 的默认属性）。

**v2 待研究方向**（简报 §4.3 / §7.2 建议）：
- 自定义 `WeaponCapabilityPresets`，用代码把材料属性注入 EF capability；
- 或为每个动态产物在生成时同步生成 capability JSON（把锻造台写出的 `ComposedAttributes` 镜像到 EF 的 `attributes.common` 之外、真正的 `attributeMap`）；
- 当前 v1 不写自定义属性，`attributes.common` 仅填 EF 的三项 souls-like 参数（`armor_negation` / `impact` / `max_strikes`），与材料强度无关。

**v1 妥协**：先跑通"纯数据包挂 preset"流程，接受 EF 战斗下强度差异被抹平这一限制；没装 EF 的玩家仍按原版 `ATTRIBUTE_MODIFIERS` 打怪，强度差异完整保留。

---

## 其他

- 命名空间全部用 `qianxiang`（物品映射目录）和 `epicfight`（被引用的 preset），与简报 §2.2 "命名空间很重要"一致。
- `/reload` 坑（简报 §7.2 引 issue #2584）：数据包动态调试时 EF 可能丢属性；正式发布走 mod 内置资源（即本目录），不依赖运行时 reload。
- 无 `mods.toml` 改动、无 Java 代码、无 `build.gradle` 改动。
