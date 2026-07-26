# 牵响 协作工单队列

> **协议**：本文件由「侦察会话」（找 bug/出方案）维护，「修理会话」（写代码）消费。
> 修理会话领活时把状态从 `[ ]` 改为 `[~] 修理中`，完成后改 `[x] 完成(commit哈希)` 并保留原文。
> 除状态行外不要改动工单正文；有异议在工单末尾加「修理备注：」。
> 背景总纲见 `docs/next-milestone-plan.md`（其中 P0-1/4/5/7/8 已完成于 e3b66f9，勿重做；
> P0-3 调查已定论，只剩文档收尾=本队列 WQ-5）。
> 每单都写了验收标准；完成必须过 `./gradlew compileJava` + `./gradlew runGameTestServer` 全绿。

---

## WQ-1 [ ] 【高】法术 power 上限改为材料预算（"强度靠材料"的法术轨缺口）

**问题**：`phase/ForgeComposer.applyAiSpell` 用 `fromSpellJson(spellJson, maxTier + 1)`——材料
最高档位只决定 power **下限**；上限是 `CustomSpell.MAX_POWER = 10` 全局定死。垃圾材料 +
AI/客户端报 power=10 完全合法，法术强度与材料脱钩，材料经济被击穿。

**修法**：
1. `CustomSpell.fromSpellJson` 增加 maxPower 参数（保留旧签名重载=默认 10，兼容蓝图路径），
   钳制改为 `Mth.clamp(power, minPower, maxPower)`。
2. `applyAiSpell` 里 `maxPower = 2 + 2 * maxTier序数`（COMMON=4, UNCOMMON=6, RARE=8, EPIC=9, LEGENDARY=10；
   具体档位枚举看 `phase/PhaseTier`，若是 4 档制则改为 4/6/8/10）。
3. `ai/PhaseAIRecipeService` 的 prompt 强度段告知 AI 本次预算上限（找 appendFreeEffectSection/
   强度代价段追加一句），让 AI 在预算内塑形。
4. GameTest：COMMON 材料组合 + power=10 的 spellJson 锻造，断言产物 power<=4。

**验收**：新测试通过；现有 `spellJsonClampsOversizedPower`（上限语境=默认 10）仍绿。

## WQ-2 [ ] 【高】AI 提案信任边界重构：客户端只回传索引

**问题**：服务端算 AI → 发客户端 → 客户端回传**spellJson 全文**（`network/SpellJsonReportPayload`，
`movesetJson` 同理）。服务端不留提案副本，无法区分"选了方案二"和"自己编了合法 JSON"——
配合 WQ-1 之前的状态就是自助作弊口。另外 `block/ForgeTableBlockEntity` 的
`lastSpellJson/lastAiMoveset` 是方块级单暂存，多人同用一台锻造台互相串味。

**修法**：
1. 服务端生成 AI 提案时（`ai/ForgeTableAIHandler` 回主线程处），把提案列表存进
   `ForgeTableBlockEntity` 的 `Map<UUID, List<AiProposal>>`（AiProposal=spellJson+movesetJson+customName；
   不落盘，容器关闭/方块卸载清理该玩家条目）。
2. `SpellJsonReportPayload` 改为只携带 `int proposalIndex`（或 -1=不用 AI 结果）；
   handler 按发包玩家 UUID 查表取回真身。`AiResponsePayload`（S2C）需带上索引。
3. 客户端 `client/ClientForgeTableAI` 相应改为记索引。
4. 锻造读取处（`menu/ForgeTableMenu` / `ForgeComposer` 调用点）改为按操作玩家 UUID 取提案。
5. 网络协议变更注意 `network/QianxiangPayloads` 里的 StreamCodec 同步改。

**验收**：GameTest 覆盖"未收到提案的玩家回传索引 0 → 拒绝"；手测单人 AI 锻造全流程无回归。
WQ-1 先做可独立上线，本单动协议建议单独一个 commit。

## WQ-3 [ ] 【高】P0-2：Epic Fight 改真软依赖（现在不装 EF 进不了游戏）

`src/main/templates/META-INF/neoforge.mods.toml` EF 依赖 `type="required"` → `"optional"`；
`Qianxiang.java:37` 附近对 `QianxiangEFCompat::register` 的监听注册加
`ModList.get().isLoaded("epicfight")` 守卫，且 `Qianxiang.java` 不得 import 任何 `yesman.epicfight.*`
类型（EF 引用已隔离在 `compat/QianxiangEFCompat` 一个类里，靠惰性类加载保护）。
`combat/AnimationLibrary`、`compat/` 其他类若有 EF import 一并检查隔离。
**验收**：装 EF 启动 + 临时注释 dev runs 的 EF 依赖再启动，均能进世界、锻造、施法。README 的
"软依赖不装不崩"从此为真。

## WQ-4 [ ] 【中】P0-6：删除 compat/MovesetCompat.java 死脚手架 + 动作集语义统一

231 行反射机制（猜包名/反射取字段/扒泛型签名）服务于"动作集子代理尚未合并"这个已不成立的
前提（`MovesetCompat.java:198` 注释）。删类及全部调用点，统一走 `QianxiangEFCompat` 的类型化
直取。同时统一语义分裂：AI 链路"4 段+去重" vs 编辑器链路"6 段允许重复"
（`network/MovesetApplyHandler:26-29` 注释自认）→ 统一为 **6 段、允许重复**。
**验收**：编译绿；grep 无 MovesetCompat 残留引用；动作编辑器手测能应用 6 段含重复动作。

## WQ-5 [ ] 【中】P0-3 收尾：三处文档/注释更新（纯文档，结论已定，照抄即可）

EF 21.15.6 字节码分析定论：**EF 不覆盖物品 attribute_modifiers，只叠加**。
1. `docs/epic-fight-compat-brief.md` §8 未确认项 #2 替换为以下结论：
   > **2.（已定论：叠加，2026-07 对 21.15.6 字节码核实）** EF 不覆盖物品栈的
   > `minecraft:attribute_modifiers` 组件，材料强度差异在 EF 战斗下完整生效。证据：
   > ① EF 唯一挂进 vanilla 属性管线的钩子 `NeoForgeEntityEvent.epicfight$itemAttributeModifier`
   > 经 `VanillaItemEventHooks.onModifyItemAttribute` 只调用 `ItemAttributeModifierEvent.addModifier`，
   > 全 jar 无 `replaceModifier`/`clearModifiers`；且本版本 `CapabilityItem.getAttributeModifiers(null)`
   > 返回空 multimap，该钩子实为空操作。② `CapabilityItem.getAttributeModifiersAsWeapon` 先读
   > `ItemStack.getAttributeModifiers()`（栈组件）再追加 capability 的 style attributes。
   > ③ EF 主手攻击 `PlayerPatch.attack` 最终 `invokevirtual Player.attack`，伤害仍由玩家
   > `ATTACK_DAMAGE` 属性（含组件修饰符）结算；`setOffhandDamage` 对主手直接 return。
   > ④ 副手双持换入的修饰符列表同样由 `getAttributeModifiersAsWeapon` 生成。
   > 推论：**不要**把 ComposedAttributes 镜像进 EF 的 `addStyleAttibutes`（EF 方法名拼写如此），
   > 否则副手路径与 EF 武器面板双重计数；千相 preset 仅带 impact/armor_negation 的现状即正确形态。
   > 升级 EF 版本后的冒烟验证：/give 两把 attribute_modifiers 差异大的 ember_blade 各打盔甲架对比掉血。
2. `docs/capabilities-readme.md` 48-66 行"EF 接管战斗时原版 MC 攻击路径被旁路"的表述改正
   （攻击最终走 `Player.attack`，未被旁路）。
3. `combat/CombatEffectHandler.java:50` 的 `<h3>Epic Fight 兼容性(TODO,需统筹实测)</h3>`
   javadoc 改为已确认说明（`LivingDamageEvent.Post` 正常触发、attacker 是玩家）。

## WQ-6 [ ] 【高】森罗之核：Boss 独占掉落 + 成就终点闭环

前置：先 `git show a9b964b 467477f` 核对并行改动——Boss 已有冲击波/追踪弹/半血狂化，
成就树已扩三条支线；**但 Boss 仍无独占掉落**。按 `docs/next-milestone-plan.md` §3.2/3.3 实施：
1. 注册物品 `warden_core`（EPIC 稀有度；贴图可先复制 `textures/item/rift_essence.png` 改紫黑色调
   占位并在 commit 信息注明）；模型/中英 lang 键/创造标签齐备（保持资产零缺陷：现中英各 677+ 键一致）。
2. `data/qianxiang/phase_materials/warden_core.json`：LEGENDARY 档，算子 MANA+RESISTANCE+一个
   攻击向算子（schema 抄同目录现有文件）。
3. `loot_table/entities/myriad_warden.json` 加第 4 池：`warden_core` ×1、100%、**不受抢夺加成**。
   全游戏唯一来源——禁止加进任何配方/商人条目。
4. 核对 `advancement/story/legendary_material.json` 现条件；改为「持有 warden_core」。
5. 新增终点成就 `forge_legendary`（parent=legendary_material，challenge，100xp）：锻造材料含
   warden_core 时服务端主动授予——在 `ForgeComposer` 锻造成功处检测，授予写法抄
   `handler/MyriadWildsPortalHandler`（并行会话 a2095f9 已在里面加过授予逻辑，或看
   `QianxiangAdvancements.java`）。
6. `FallbackRecipes` 的 `case LEGENDARY` 8 处全映射 rift_essence——把攻击/法术向的 4 处改为
   材料库存在 warden_core 时优先选它。
**验收**：GameTest：击杀守望者掉落表包含 warden_core；含核锻造授予 forge_legendary。
手测全进程链成就顺序正确（新世界→kit→锻造→进维度→杀 Boss→才弹 legendary_material→用核锻造弹终点）。

## WQ-7 [ ] 【中】组件与分享码加版本号 + 迁移注册表

`CUSTOM_SPELL`/`CUSTOM_MOVESET`/`SPELLBOOK` 组件和蓝图分享码（`blueprint/BlueprintShareCodes`）
均无版本字段；schema 已发生过一次演化（spellJson 旧词折算）。趁字段还少：
1. 分享码 payload 加 `"v": 1`；解码时缺省视为 v0 走现有旧词折算，未知高版本给出
   「请更新模组」的聊天提示而不是静默失败。
2. 组件 codec 加 optional int version（缺省 0），并建一个 `SpellJsonMigrations` 静态注册表
   （v0→v1 就是现有 normalizeLegacyWords 逻辑的搬家）。
**验收**：旧格式分享码（不带 v 字段）仍能导入；GameTest 覆盖 v0 码 roundtrip。

## WQ-8 [ ] 【低·快赢】heal/buff 打怪物：扣蓝无反馈

`spell/SpellEffectEngine.resolveHit`（约 212-223 行）要求 isAlly，heal/buff 的 projectile/beam/touch
形式对怪物命中时什么都不发生，但法力已扣、冷却已进、无任何提示。修法：命中非友方时
退回法力（或干脆不进冷却）+ actionbar 提示「该法术只能作用于友方」（lang 键中英各 1）。
**验收**：手测 heal 弹体打僵尸出提示且蓝退回。

## WQ-9 [ ] 【低】法术冷却同步 + HUD 显示

`SpellCastHandler.java:215` 的 `SpellDataSyncPayload` 只同步 mana 不同步 cooldown，
`client/SpellHudRenderer` 只画一行"法力: cur/max"文字。修法：payload 加当前法术剩余冷却 tick；
HUD 在法力行下加一条 80×4 像素冷却条（灰底白条，随剩余时间缩短），满冷却时不画。
不做花哨美术，先解决"玩家不知道何时能再施法"。
**验收**：手测施法后 HUD 出现冷却条并倒数消失；维度切换后 HUD 数据仍正确
（顺手检查 `SpellTickHandler:260-272` 初始同步是否漏了 PlayerChangedDimensionEvent，漏了就补）。

---

## 已完成（勿重做）
- P0-1 法术上行白名单+钳制、P0-4 调试栈打印、P0-5 en_us 中文污染、P0-7 AI 熔断、
  P0-8 防具映射（e3b66f9，侦察会话）
- Boss 三技能、成就三支线、位格门控、锻造/传送门音效、NPC 补货落盘、分享码 productType
  归一（2f443f0..be66c1a，修理会话）
- P0-3 调查（结论见 WQ-5，代码无需改动）
