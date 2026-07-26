# Epic Fight 21.15.6 适配简报（NeoForge 1.21.1）

> 调研对象：`maven.modrinth:epic-fight:21.15.6-mc1.21.1-neoforge`
> 反编译来源：`~/.gradle/caches/modules-2/files-2.1/maven.modrinth/epic-fight/21.15.6-mc1.21.1-neoforge/962bb811802d8c50f8a63be282067dc0c4611566/epic-fight-21.15.6-mc1.21.1-neoforge.jar`
> 调研日期：2026-07-22

---

## 1. 一句话结论

**能做，最短路径是「零 Java 代码 + 纯数据包」**：在 `src/main/resources/data/qianxiang/capabilities/weapons/<item_registry_name>.json` 里写一个 JSON，把 `qianxiang:ember_blade` 指向某个已注册的 EF 武器类型（如 `epicfight:tachi` / `epicfight:longsword`），即可立刻获得该类型的全套挥砍动作、连击与音效。要自创动作模组，再加一个 `data/qianxiang/capabilities/weapons/types/<custom>.json` 定义新武器类型即可。

EF 用的是 **NeoForge 1.21.1 的 `ItemCapability` 机制**（非旧式 Capability），全局挂载由 EF 自己通过 `RegisterCapabilitiesEvent` 注册，我们只需往数据包里塞 JSON，EF 的 `ItemCapabilityReloadListener` 会自动加载。

---

## 2. 武器注册步骤（确切机制：datapack，非 tag、非代码）

### 2.1 三层数据结构（全部从 jar 反编译确认）

| 层 | 目录（`data/<namespace>/...`） | 作用 | 加载器 |
|---|---|---|---|
| ① **物品→武器类型映射** | `capabilities/weapons/<item_registry_name>.json` | 把某个 item id 绑到一个 weapon type | `ItemCapabilityReloadListener`（`DIRECTORY = "capabilities"`） |
| ② **武器类型定义** | `capabilities/weapons/types/<type_name>.json` | 定义一个新武器类型（动作、连击、collider、自带 skill） | `WeaponTypeReloadListener`（`DIRECTORY = "capabilities/weapons/types"`） |
| ③ **正则兜底映射**（可选） | `capabilities/weapons/item_keyword/<keyword>.json` | 按物品 id 正则批量匹配（如 `".*_longsword"`） | 同①加载器，子目录 `item_keyword` |

> 三个 `DIRECTORY` 常量均由 `javap -constants` 直接读出，**确认无误**。

### 2.2 物品映射 JSON 格式（层①）

对 `iron_tachi.json`、`iron_greatsword.json`、`netherite_tachi.json` 的实测格式：

```json
{
    "attributes": {
        "common": {
            "armor_negation": 10.0,
            "impact": 2.6,
            "max_strikes": 2
        }
    },
    "hit_particle": "blunt",
    "hit_sound": "entity.hit.blunt",
    "type": "epicfight:tachi"
}
```

字段说明：
- `"type"`：**必填**。指向武器类型（层②定义，或 EF 内置 preset）。命名空间很重要——用别人 mod 的类型记得改 mod id。
- `"attributes"`：EF 自己的三项战斗属性（见 §4），可选。
- `"hit_particle"` / `"hit_sound"`：可选，覆盖默认。

### 2.3 内置武器类型 preset（可直接当 `"type"` 用）

`WeaponCapabilityPresets` 的静态字段（javap 确认），已通过 `registerDefaultWeaponTypes()` 注册为 `epicfight:<名字>`：

`axe` `hoe` `pickaxe` `shovel` `sword` `spear` `greatsword` `uchigatana` `tachi` `longsword` `dagger` `fist` `bow` `crossbow` `trident` `shield`

→ 对 `ember_blade`，**最省事**就是 `"type": "epicfight:tachi"` 或 `"epicfight:longsword"`，立即有动作。

### 2.4 正则兜底（批量给产物挂类型）

EF 自带示例 `data/epicfight/capabilities/weapons/item_keyword/longsword.json`：
```json
{ "regexes": [ ".*_longsword" ] }
```
**这对千相意义重大**：若锻造台产物命名遵循后缀约定（如 `qianxiang:xxx_blade`、`qianxiang:xxx_tachi`），可以用一条正则把整类产物一次性挂到 EF 武器类型，无需为每个动态产物写 JSON。

---

## 3. 动作 / 技能 / 动画挂法

### 3.1 动画文件位置（从 jar `assets/epicfight/animmodels/` 实测）

```
assets/<namespace>/animmodels/animations/<armature>/<combat|...>/<weapon>_<action>.json
```
EF 自带示例：`assets/epicfight/animmodels/animations/biped/combat/tachi_auto1.json`、`longsword_dash.json`、`dagger_airslash.json` 等。配套的「动作数据」（伤害段、击退段）在同目录 `data/` 子文件夹：`biped/combat/data/tachi_auto1.json`。

### 3.2 武器类型定义 JSON（层②）能挂什么

从 `WeaponCapability$Builder` 的 setter（javap 确认）反推字段：
- `autoAttackMotionMap`：每个 `Style` 的连击动画序列（`newStyleCombo(style, anim1, anim2, ...)`）
- `innateSkillByStyle`：每个姿势绑定的天赋技能（`innateSkill(style, stack -> skill)`）
- `passiveSkill`：被动技能
- `livingMotionModifiers`：待机/行走/等生活动画覆盖
- `swingSound` / `hitSound` / `hitParticle`
- `comboCancel` / `comboCounterHandler`：连击取消与计数
- `reach`：攻击距离
- `canBePlacedOffhand` / `zoomInType`

### 3.3 技能（skill）的注册

技能是**代码注册**的（`EpicFightRegistries.SKILL` 是 NeoForge `Registry<Skill>`）。EF 的技能类在 `yesman.epicfight.skill.*`（`weaponinnate` / `weapon_passive` / `passive` / `dodge` / `guard` / `mover` / `identity`）。技能的**数值参数**走数据包 `data/<ns>/skill_parameters/<skill>.json`（jar 内有 80+ 个示例，如 `battojutsu.json`、`berserker.json`）。

> **结论**：千相若只复用 EF 自带技能（推荐起步），零代码；要自创技能才需要 Java 注册 `Skill`。本简报聚焦武器接入，自创技能不在 v1 范围。

### 3.4 物品贴图 / 模型

和普通 item 一样走 `assets/qianxiang/models/item/<item>.json` + `textures/`。EF 不接管物品贴图。若要让 EF 在战斗姿态下用专属持握模型，另有 `item_skins` 机制（`assets/epicfight/item_skins/`），属进阶，v1 不碰。

---

## 4. 与 MC 原生 `ATTRIBUTE_MODIFIERS` 共存（**重点，直接影响设计**）

### 4.1 EF 的战斗属性 vs MC 的属性是**两套独立系统**

EF 的武器 JSON 里的 `"attributes"`：
- `armor_negation`（护甲穿透）
- `impact`（击退/硬直）
- `max_strikes`（单次挥砍命中数）

这三项**不是** MC 的 `Attributes.ATTACK_DAMAGE` / `ATTACK_SPEED`，是 EF 自己的 souls-like 战斗参数。二者数据上不冲突。

### 4.2 攻击伤害谁说了算

`CapabilityItem` 内部持有：
```java
Map<Style, Map<Holder<Attribute>, AttributeModifier>> attributeMap;
Map<Style, ItemAttributeModifiers> modifiers;
```
并提供静态方法 `CapabilityItem.getAttributeModifiersAsWeapon(attr, slot, stack, entityPatch)`——EF 在 patch 玩家时，**会把 capability 里的这些 AttributeModifier 作为「真正生效的武器属性」注入**。

**关键结论（高置信度，基于代码结构）**：
- EF **接管**战斗时的属性结算（伤害走 EF 的动画事件 + `attributeMap`），普通 MC 攻击路径在 EF 激活时被旁路。
- 物品自带的 `DataComponents.ATTRIBUTE_MODIFIERS`（我们锻造产物的强度属性）**不会让 EF 崩**，且已定论为**叠加而非覆盖**——材料强度在 EF 战斗下完整生效（字节码级证据见 §8 第 2 条）。

### 4.3 对千相设计的影响与建议

1. **把「材料强度」的最终数值，尽量同步写进 EF 的 `attributeMap`**（通过武器类型 JSON 的 `attributes` 或 capability builder），而不仅仅依赖 MC `ATTRIBUTE_MODIFIERS`。否则玩家在 EF 战斗下可能感觉不到强度差异——违背「强度靠材料」的灵魂。
2. **v1 妥协方案**：先用 EF 内置 preset（`tachi`/`longsword`）+ 默认属性跑通流程；材料强度仍由 MC `ATTRIBUTE_MODIFIERS` 承载，**接受 EF 战斗下强度差异被抹平**这一已知限制，待 v2 研究动态注入 capability 属性。
3. **不要**在产物上写「会触发 EF 重算冲突」的自定义属性——保持 `ATTRIBUTE_MODIFIERS` 只用 vanilla 的 `ATTACK_DAMAGE` / `ATTACK_SPEED` / `MAX_HEALTH` 等标准键。

---

## 5. 关键类 / 方法名清单（从 jar `javap` 直接读取）

### 5.1 capability 核心

| 全限定名 | 作用 |
|---|---|
| `yesman.epicfight.world.capabilities.EpicFightCapabilities` | 入口 facade。`CAPABILITY_ITEM`（`ItemCapability<CapabilityItem, Void>`，RL=`epicfight:item_capability`）；`getItemStackCapability(ItemStack)` / `getItemCapability(ItemStack)` |
| `yesman.epicfight.world.capabilities.provider.CommonItemCapabilityProvider` | `ICapabilityProvider<ItemStack, Void, CapabilityItem>` 的单例（`INSTANCE`）。`getCapability(ItemStack, Void)` 运行时查表；`put(Item, CapabilityItem)` / `get(Item)` / `registerWeaponTypesByClass()` |
| `yesman.epicfight.world.capabilities.item.CapabilityItem` | 基类。静态 `EMPTY`、`getAttributeModifiersAsWeapon(...)`；实例方法 `getAutoAttackMotion`、`getInnateSkill`、`getWeaponCategory`、`getStyle`、`getDamageAttributesInCondition(Style)`、`getAttributeModifiers(entityPatch)`、`getAllAttributeModifiers()` |
| `yesman.epicfight.world.capabilities.item.WeaponCapability` | 武器 capability 实现。持有 `autoAttackMotions`、`innateSkill`、`passiveSkill`、`livingMotionModifiers`、`reach` 等 |
| `yesman.epicfight.world.capabilities.item.WeaponCapability.Builder` | 构造器。关键 setter：`newStyleCombo(style, anim...)`、`innateSkill(style, fn)`、`passiveSkill(skill)`、`livingMotionModifier(...)`、`reach(float)`、`comboCancel(fn)` |
| `yesman.epicfight.world.capabilities.item.WeaponCategory` | 武器类别（`ExtensibleEnum`，可被数据包扩展） |
| `yesman.epicfight.world.capabilities.item.WeaponCapabilityPresets` | 16 个内置 preset（见 §2.3） |

### 5.2 数据加载器

| 全限定名 | DIRECTORY 常量 |
|---|---|
| `yesman.epicfight.api.data.reloader.ItemCapabilityReloadListener` | `"capabilities"`（物品→类型映射，含 `weapons/`、`armors/`、`weapons/item_keyword/` 子目录） |
| `yesman.epicfight.world.capabilities.item.WeaponTypeReloadListener` | `"capabilities/weapons/types"`（武器类型定义） |
| `yesman.epicfight.api.data.reloader.SkillReloadListener` | 技能参数 |

### 5.3 事件（进阶，代码注册自定义类型/图标时用）

- `yesman.epicfight.api.event.types.registry.WeaponCapabilityPresetRegistryEvent`（注册新 preset）
- `yesman.epicfight.api.event.types.registry.RegisterWeaponCategoryIconEvent`
- `yesman.epicfight.api.event.types.registry.SkillBuilderModificationEvent`

### 5.4 实体 patch（战斗运行时）

- `EpicFightCapabilities.getPlayerPatch(Player)` / `getServerPlayerPatch(...)` / `getLocalPlayerPatch(...)`
- `yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch`
- `yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch`

### 5.5 注册表

- `yesman.epicfight.registry.EpicFightRegistries`：`SKILL`、`SKILL_DATA_KEY`、`CONDITION`、`SYNCHED_ANIMATION_VARIABLE` 等 NeoForge `Registry`。
- `yesman.epicfight.registry.entries.EpicFightSkills` / `EpicFightSkillDataKeys`：内置技能实例。

---

## 6. 最小示例：把 `qianxiang:ember_blade` 接成 EF 武器

### 6.1 假设

`ember_blade` 已是千相注册的 Item（registry name `qianxiang:ember_blade`），自带 `DataComponents.ATTRIBUTE_MODIFIERS`（攻击伤害 5、攻速 -2.4 之类）。

### 6.2 零代码方案（推荐起步）——只加 1 个 JSON

新建文件：
```
src/main/resources/data/qianxiang/capabilities/weapons/ember_blade.json
```
内容：
```json
{
    "attributes": {
        "common": {
            "armor_negation": 0.0,
            "impact": 2.6,
            "max_strikes": 2
        }
    },
    "type": "epicfight:tachi"
}
```
**仅此而已**。启动游戏后 `ember_blade` 就是把太刀，有 `tachi_auto1/2/3` 三段连击 + dash。`epicfight:tachi` 也可换 `epicfight:longsword`、`epicfight:uchigatana` 等。

### 6.3 自创武器类型（进阶，仍零 Java）

若 tachi 动作不够，想给 ember_blade 专属动作模组。新建：
```
src/main/resources/data/qianxiang/capabilities/weapons/types/ember_blade.json
```
（层② JSON，字段对应 `WeaponCapability$Builder` 的 setter，具体 schema 需对 jar 内 `data/epicfight/capabilities/weapons/types/*.json` 取一个做模板——**本次未抽取该层示例，schema 待确认，见 §8**）

再把 6.2 的 `"type"` 改成 `"qianxiang:ember_blade"`。

### 6.4 动画文件（自创动作才需要）

```
src/main/resources/assets/qianxiang/animmodels/animations/biped/combat/ember_blade_auto1.json
src/main/resources/assets/qianxiang/animmodels/animations/biped/combat/data/ember_blade_auto1.json
```
格式需对照 EF 的 `assets/epicfight/animmodels/animations/biped/combat/tachi_auto1.json`（**未抽取，待确认**）。v1 直接复用 EF 自带动画即可，不用写。

### 6.5 批量产物（锻造台动态产出）——正则兜底

```
src/main/resources/data/qianxiang/capabilities/weapons/item_keyword/blade.json
```
```json
{ "regexes": [ "qianxiang:.*_blade" ] }
```
所有 `xxx_blade` 产物（如 `copper_blade`、`iron_blade`）一次性挂上——**这是适配动态锻造台的关键技巧**。

### 6.6 Java 侧：零侵入软依赖（见 §7）

不需要 `@Mod` 条件加载、不需要反射。JSON 文件放在 `data/qianxiang/` 下，没装 EF 时，MC 原版数据包加载器会忽略陌生目录（`capabilities/` 不是原版认的路径），不报错、不崩溃。装了 EF 才被 EF 的 reload listener 读取。**天然软依赖**。

---

## 7. 风险与软依赖降级

### 7.1 软依赖（结论：**天然降级，无需额外代码**）

- EF 的接入完全走数据包目录 `capabilities/weapons/...`，该路径**只被 EF 的 `ItemCapabilityReloadListener` 识别**。
- 没装 EF 时：NeoForge/原版数据包系统看到陌生目录直接忽略，物品仍是普通 MC 武器，按 `ATTRIBUTE_MODIFIERS` 正常打怪。**零退化代码、零反射检查**。
- **不需要** `mods.toml` 里写 EF 为依赖（写成 `optional` 即可，便于未来若要调 EF 的 Java API）。

### 7.2 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **材料强度在 EF 战斗下失效**（§4.3） | 中。违背「强度靠材料」 | v2 研究：自定义 `WeaponCapabilityPresets` 用代码注入材料属性；或每个产物动态生成 capability JSON |
| 21.15.6 的 `attributes` 字段语义可能微调 | 低。字段名（`impact`/`max_strikes`/`armor_negation`）从 jar 内多个 JSON 交叉确认，稳定 | 对照多个版本 JSON |
| 武器类型定义 JSON（层②）schema 未本次抽取 | 中。自创类型受阻 | v1 先用内置 preset；自创类型时再 `jar xf` 取 `data/epicfight/capabilities/weapons/types/` 模板 |
| EF 重算 `/reload` 丢属性（issue #2584） | 低-中。仅影响 datapack 动态调试 | 正式发布走 mod 内置资源，不依赖运行时 reload |
| `epicfight:tachi` 等命名空间依赖 EF mod id | 无。EF 不在场则 JSON 不生效，物品退化普通武器 | 符合软依赖预期 |

### 7.3 版本坑

- 21.15.x 是 1.21.1 的新数据包编辑器时代（jar 内 `yesman/epicfight/client/gui/datapack/screen/` 有完整可视化编辑器 `DatapackEditScreen`）。与旧版（1.20.x）「WeaponType」代码注册机制**已不同**——网上 1.20 的教程（`WeaponCategory.register()`、`RegisterWeaponType` 事件）**过时**，别照抄。一切以数据包为准。
- EF 依赖 NeoForge `[21.1.219,)`，与千相同栈。

---

## 8. 未确认项

1. ~~武器类型定义 JSON 的确切 schema~~ **（已解决）** schema 已抽取并落地为 `data/qianxiang/capabilities/weapons/types/qianxiang_blades.json`（category + collider + combos）。
2. **（已定论：叠加，2026-07 对 21.15.6 字节码核实）** EF 不覆盖物品栈的
   `minecraft:attribute_modifiers` 组件，材料强度差异在 EF 战斗下完整生效。证据：
   ① EF 唯一挂进 vanilla 属性管线的钩子 `NeoForgeEntityEvent.epicfight$itemAttributeModifier`
   经 `VanillaItemEventHooks.onModifyItemAttribute` 只调用 `ItemAttributeModifierEvent.addModifier`，
   全 jar 无 `replaceModifier`/`clearModifiers`；且本版本 `CapabilityItem.getAttributeModifiers(null)`
   返回空 multimap，该钩子实为空操作。② `CapabilityItem.getAttributeModifiersAsWeapon` 先读
   `ItemStack.getAttributeModifiers()`（栈组件）再追加 capability 的 style attributes。
   ③ EF 主手攻击 `PlayerPatch.attack` 最终 `invokevirtual Player.attack`，伤害仍由玩家
   `ATTACK_DAMAGE` 属性（含组件修饰符）结算；`setOffhandDamage` 对主手直接 return。
   ④ 副手双持换入的修饰符列表同样由 `getAttributeModifiersAsWeapon` 生成。
   推论：**不要**把 ComposedAttributes 镜像进 EF 的 `addStyleAttibutes`（EF 方法名拼写如此），
   否则副手路径与 EF 武器面板双重计数；千相 preset 仅带 impact/armor_negation 的现状即正确形态。
   升级 EF 版本后的冒烟验证：/give 两把 attribute_modifiers 差异大的 ember_blade 各打盔甲架对比掉血。
3. **动画 JSON 格式细节**（`animmodels/animations/biped/combat/*.json` 与 `data/*.json` 的字段）。v1 复用 EF 动画不需要，自创时再查。
4. 官方文档（readthedocs）页面内容因 web-reader 配额受限，本次未抓取正文，仅从搜索摘要确认目录与主题。标题与 URL 可信，正文细节建议后续用浏览器直查。

---

## 9. 来源

### 反编译（一手，高置信度）
- 本地 jar：`~/.gradle/caches/modules-2/files-2.1/maven.modrinth/epic-fight/21.15.6-mc1.21.1-neoforge/962bb811802d8c50f8a63be282067dc0c4611566/epic-fight-21.15.6-mc1.21.1-neoforge.jar`
- 手段：`unzip -l` 列目录 / `unzip` 抽 JSON / `javap -p` `-constants` 读签名 / `strings` 读常量

### 官方文档（URL 已核实，正文未抓取）
- Epic Fight Wiki 首页：https://epicfight-docs.readthedocs.io/
- Item Capability（武器映射）：https://epicfight-docs.readthedocs.io/Guides/Weapons/page1/
- Weapon Type Editor（武器类型定义）：https://epicfight-docs.readthedocs.io/Guides/Weapons/page2/
- Custom Trails：https://epicfight-docs.readthedocs.io/Guides/Weapons/page3/
- Asset Importing：https://epicfight-docs.readthedocs.io/Guides/page3/
- GitHub 1.21.1 分支：https://github.com/Epic-Fight/epicfight/blob/1.21.1/README.md

### 已知 issue
- Datapack weapon attributes lost on `/reload`：https://github.com/Antikythera-Studios/epicfight/issues/2584
- Datapack editor combo crash：https://github.com/Antikythera-Studios/epicfight/issues/1978

### 社区参考（数据包 compat 实践）
- Epic Fight & Other Mods Weapons Compat（Modrinth）：https://modrinth.com/mod/epic-fight-weapons-compat
- CompatLink – Epic Fight Weapons Compat（CurseForge）：https://www.curseforge.com/minecraft/mc-mods/compatlink-epic-fight-weapons-compat
- Epic Fight - EDP (Extended Datapacks)：https://www.curseforge.com/minecraft/mc-mods/epic-fight-edp
