# MC 1.21.1 状态效果与附魔机制参考

> 面向《千相》mod 用户与开发者的速查文档。
> 所有事实均取自 NeoForge 21.1.219 反编译源码（`~/.gradle/caches/neoformruntime/intermediate_results/decompile_*_output.jar` 中的
> `net.minecraft.world.effect.MobEffects` 与 `net.minecraft.world.item.enchantment.Enchantments`），一手坐实，非二手 wiki。
>
> 核心源码位置：
> - 效果注册表：`MobEffects`（39 个，静态字段为 `Holder<MobEffect>`）
> - 附魔注册表：`Enchantments`（42 个，静态字段为 `ResourceKey<Enchantment>`；1.21 起附魔是**数据驱动注册表**，不再是静态实例）
> - 施加效果 API：`net.minecraft.world.effect.MobEffectInstance`
> - 附魔 API：`ItemStack.enchant(Holder<Enchantment>, int)` → `EnchantmentHelper.updateEnchantments`

---

## 一、状态效果（MobEffect）全表（39 个）

### 1.1 施加效果的标准代码

```java
// Holder<MobEffect> 直接用 MobEffects.XXX 静态字段即可
target.addEffect(new MobEffectInstance(
        MobEffects.MOVEMENT_SLOWDOWN, // Holder<MobEffect>，来自 MobEffects 静态字段
        100,        // duration：持续 tick 数（20 tick = 1 秒）；瞬时效果（瞬间治疗/伤害/饱和）duration 无意义
        1,          // amplifier：药水等级偏移量。0 = I 级，1 = II 级，依此类推
        true,       // ambient：是否环境粒子（更淡）
        false,      // visible：是否显示粒子
        true        // showIcon：是否在 HUD 显示图标
));
```

要点：

- **amplifier = 等级 - 1**。效果描述里写"力量 II"对应 `amplifier=1`。
- 同一效果重复施加时，**取更高 amplifier；同 amplifier 时刷新时长**（不会叠加时长）。
- 只能在**服务端**施加（`!level.isClientSide()`），客户端 `addEffect` 会被网络同步覆盖。
- 瞬时效果（`HealOrHarmMobEffect`、`SaturationMobEffect`）施加瞬间结算一次，duration 无意义。

### 1.2 增益战斗类

| registry id | 中文名 | 效果行为 | 常见来源 |
|---|---|---|---|
| `speed` | 迅捷 | 每级移动速度 +20%（属性乘算） | 迅捷药水、信标 |
| `slowness` | 缓慢 | 每级移动速度 -15% | 迟缓药水、流浪者之箭 |
| `haste` | 急迫 | 每级攻击速度 +10%、挖掘速度 +20% | 信标 |
| `mining_fatigue` | 挖掘疲劳 | 每级攻击速度 -10%、挖掘速度大幅降低 | 远古守卫者诅咒（III 级） |
| `strength` | 力量 | 每级攻击伤害 +3（加算） | 力量药水、信标 |
| `weakness` | 虚弱 | 每级攻击伤害 -4 | 虚弱药水、僵尸感染治愈前置 |
| `regeneration` | 生命恢复 | 每 50/25/12… tick 回 1 血（等级越高越快） | 再生药水、金苹果、信标 |
| `resistance` | 抗性提升 | 每级减伤 20%（V 级 = 100% 免伤，除虚空/kill） | 附魔金苹果、神龟药水 |
| `fire_resistance` | 抗火 | 免疫火焰/熔岩/岩浆块/小火球伤害 | 抗火药水 |
| `health_boost` | 生命提升 | 每级最大生命 +4（2 心），效果结束扣除 | 高阶指令/模组常用 |
| `absorption` | 伤害吸收 | 每级 +4 点吸收心（不可再生护盾） | 金苹果、附魔金苹果 |
| `saturation` | 饱和 | **瞬时**：每级回 1 饥饿 + 2 饱和度 | 谜之炖菜、模组治疗食物 |

### 1.3 瞬时与持续伤害类

| registry id | 中文名 | 效果行为 | 常见来源 |
|---|---|---|---|
| `instant_health` | 瞬间治疗 | **瞬时**：每级回 4 血；对亡灵反转为伤害 | 治疗药水 |
| `instant_damage` | 瞬间伤害 | **瞬时**：每级造成 6 魔法伤害；对亡灵反转为治疗 | 伤害药水 |
| `poison` | 中毒 | 周期性掉血，最低留 1 血（不致死） | 毒药水、洞穴蜘蛛、河豚 |
| `wither` | 凋零 | 周期性掉血，**可致死**，心条变黑 | 凋零玫瑰、凋零骷髅、凋零之首 |
| `hunger` | 饥饿 | 每级每 tick 饥饿消耗 +0.1 | 生鸡肉、腐肉、尸壳攻击 |

### 1.4 移动与感知类

| registry id | 中文名 | 效果行为 | 常见来源 |
|---|---|---|---|
| `jump_boost` | 跳跃提升 | 每级跳跃高度 +0.5 格，安全掉落距离 +1 | 跳跃药水、信标 |
| `levitation` | 飘浮 | 每级以 0.9 格/秒上升，结束时摔落 | 潜影贝弹 |
| `slow_falling` | 缓降 | 下落减缓、免疫摔落伤害 | 缓降药水 |
| `night_vision` | 夜视 | 全亮度视野 | 夜视药水、潮涌核心（水下） |
| `blindness` | 失明 | 视野浓雾、无法奔跑/暴击 | 幻术师、谜之炖菜 |
| `darkness` | 黑暗 | 视野周期性变暗（22 tick 渐入渐出） | 监守者、幽匿尖啸体 |
| `nausea` | 反胃 | 屏幕扭曲晃动（纯客户端） | 河豚、进入下界传送门 |
| `invisibility` | 隐身 | 实体不可见（盔甲/手持物仍可见，粒子可见） | 隐身药水 |
| `glowing` | 发光 | 高亮描边，隔墙可见 | 光灵箭 |

### 1.5 水下/环境类

| registry id | 中文名 | 效果行为 | 常见来源 |
|---|---|---|---|
| `water_breathing` | 水下呼吸 | 氧气不消耗 | 水肺药水、海龟壳 |
| `conduit_power` | 潮涌能量 | 水下呼吸 + 夜视 + 挖掘加速（水下复合增益） | 激活的潮涌核心 |
| `dolphins_grace` | 海豚的恩惠 | 游泳速度大幅提升 | 海豚附近 |
| `luck` | 幸运 | 每级幸运属性 +1（影响战利品表 quality 权重） | 幸运药水（创造） |
| `unluck` | 霉运 | 每级幸运属性 -1 | 指令 |

### 1.6 不祥之兆系（1.21 重做）

| registry id | 中文名 | 效果行为 | 常见来源 |
|---|---|---|---|
| `bad_omen` | 不祥之兆 | 进入村庄时转化为 `raid_omen`；靠近试炼刷怪笼时转化为 `trial_omen` | 击杀灾厄巡逻队长（不祥之瓶） |
| `raid_omen` | 袭击之兆 | 30 秒倒计时，结束时在村庄触发袭击 | `bad_omen` 入村转化 |
| `trial_omen` | 试炼之兆 | 把附近普通试炼刷怪笼变为不祥试炼刷怪笼（更难、掉不祥试炼钥匙） | `bad_omen` 在试炼密室转化 |
| `hero_of_the_village` | 村庄英雄 | 村民打折、赠送礼物 | 击退袭击后获得 |

### 1.7 1.21 新增「试炼密室」系

| registry id | 中文名 | 效果行为 | 常见来源 |
|---|---|---|---|
| `wind_charged` | 蓄风 | 死亡时爆发风弹（击飞周围实体） | 蓄风药水（旋风棒酿造） |
| `weaving` | 盘丝 | 死亡时在周围生成 2~3 个蜘蛛网，且穿过蛛网不减速 | 盘丝药水 |
| `oozing` | 渗浆 | 死亡时生成 2 个史莱姆 | 渗浆药水（史莱姆块酿造） |
| `infested` | 寄生 | 受伤时 10% 概率生成 1~2 只蠹虫 | 寄生药水（石头+蠹虫方块酿造） |

> 这类"死亡时/受伤时触发"的效果与《千相》PhaseFunction 算子的触发式设计理念一致，可作为自定义效果算子的设计参照。

---

## 二、附魔（Enchantment）全表（42 个）

### 2.1 代码施加方式（1.21.1 数据驱动 API）

1.21 起附魔存于 `DataComponents.ENCHANTMENTS`（`ItemEnchantments`），附魔本身是**注册表数据**（可在数据包中自定义）：

```java
// 从注册表取 Holder（必须在有 Level/RegistryAccess 的上下文，通常是服务端）
Registry<Enchantment> enchReg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
Holder<Enchantment> sharpness = enchReg.getHolderOrThrow(Enchantments.SHARPNESS);

// 直接施加（会覆盖同级并升级）
stack.enchant(sharpness, 5);

// 或批量操作
EnchantmentHelper.updateEnchantments(stack, mutable -> {
    mutable.upgrade(enchReg.getHolderOrThrow(Enchantments.SHARPNESS), 5);
    mutable.set(enchReg.getHolderOrThrow(Enchantments.LOOTING), 3); // set 直接设级
    mutable.removeIf(h -> h.is(Enchantments.KNOCKBACK));            // 移除
});

// 查询
int lvl = EnchantmentHelper.getItemEnchantmentLevel(sharpness, stack);
```

注意：

- `stack.enchant()` **不检查附魔兼容性与可附魔性**，强行添加即可生效（多数机制走 `EnchantmentHelper` 查询，不在乎互斥）。
- 附魔名称渲染走 `enchantment.minecraft.sharpness` 翻译键；自定义附魔需在 `zh_cn.json`/`en_us.json` 同步。
- 诅咒类（绑定/消失）会照常生效，给玩家装备绑定诅咒时注意体验。

### 2.2 武器（近战）附魔

| registry id | 中文名 | 最高级 | 效果机制 |
|---|---|---|---|
| `sharpness` | 锋利 | V | 近战伤害 +0.5×级+0.5（I 级 +1，之后每级 +0.5） |
| `smite` | 亡灵杀手 | V | 对亡灵每级 +2.5 伤害 |
| `bane_of_arthropods` | 节肢杀手 | V | 对节肢动物每级 +2.5 伤害，命中附加缓慢 IV |
| `knockback` | 击退 | II | 每级击退距离 +约 3 格 |
| `fire_aspect` | 火焰附加 | II | 每级点燃目标 4 秒 |
| `looting` | 抢夺 | III | 每级常见掉落 +1 上限、稀有掉落概率 +1% |
| `sweeping_edge` | 横扫之刃 | III | 横扫伤害比例 = 级/(级+1) |

### 2.3 弓/弩/三叉戟/重锤附魔

| registry id | 中文名 | 最高级 | 效果机制 |
|---|---|---|---|
| `power` | 力量 | V | 箭伤害 +25%×(级+1) |
| `punch` | 冲击 | II | 箭击退 +3 格/级 |
| `flame` | 火矢 | I | 箭点燃目标 5 秒 |
| `infinity` | 无限 | I | 普通箭不消耗（与经验修补互斥） |
| `multishot` | 多重射击 | I | 弩一次扇形射 3 支（与穿透互斥） |
| `quick_charge` | 快速装填 | III | 弩装填时间 -0.25 秒/级 |
| `piercing` | 穿透 | IV | 箭穿透 级 个实体 |
| `loyalty` | 忠诚 | III | 三叉戟掷出后返回，每级更快 |
| `impaling` | 穿刺 | V | 对水生生物每级 +2.5 伤害 |
| `riptide` | 激流 | III | 水中/雨中掷出带动玩家冲刺（与忠诚/引雷互斥） |
| `channeling` | 引雷 | I | 雷雨天命中召唤闪电 |
| `density` | 致密 | V | 重锤每下落一格 +0.5 伤害/级 |
| `breach` | 破甲 | IV | 重锤无视 15%×级 护甲 |
| `wind_burst` | 风爆 | III | 重锤猛击命中后弹起并短暂获得风弹蓄力 |

### 2.4 防具附魔

| registry id | 中文名 | 最高级 | 效果机制 |
|---|---|---|---|
| `protection` | 保护 | IV | 每级 +4% 减伤（EPF 机制，全套封顶 80%），与其他保护系互斥 |
| `fire_protection` | 火焰保护 | IV | 每级 +8% 火焰减伤、燃烧时间 -15% |
| `blast_protection` | 爆炸保护 | IV | 每级 +8% 爆炸减伤、爆炸击退 -15% |
| `projectile_protection` | 弹射物保护 | IV | 每级 +8% 弹射物减伤 |
| `feather_falling` | 摔落缓冲 | IV | 每级摔落伤害 -12%（仅靴子） |
| `thorns` | 荆棘 | III | 受击时按级概率反伤攻击者 1~4，额外耗耐久 |
| `depth_strider` | 深海探索者 | III | 水下移速惩罚每级 -1/3（III 级=陆地速度，仅靴子） |
| `frost_walker` | 冰霜行者 | II | 行走水面结冰（霜冰，仅靴子） |
| `soul_speed` | 灵魂疾行 | III | 灵魂沙/土上加速 30%+10.5%×级（仅靴子） |
| `swift_sneak` | 迅捷潜行 | III | 潜行速度每级 +15%（仅护腿，远古城市战利品） |
| `aqua_affinity` | 水下速掘 | I | 水下挖掘不减速（仅头盔） |
| `respiration` | 水下呼吸 | III | 每级氧气 +15 秒、概率免扣氧（仅头盔） |

### 2.5 工具附魔

| registry id | 中文名 | 最高级 | 效果机制 |
|---|---|---|---|
| `efficiency` | 效率 | V | 挖掘速度 +级²+1（正确工具时） |
| `silk_touch` | 精准采集 | I | 方块掉落自身（与时运互斥） |
| `fortune` | 时运 | III | 矿物等掉落数量按级加成（与精准采集互斥） |

### 2.6 钓鱼竿附魔

| registry id | 中文名 | 最高级 | 效果机制 |
|---|---|---|---|
| `luck_of_the_sea` | 海之眷顾 | III | 每级宝藏权重 +2%、垃圾 -2% |
| `lure` | 饵钓 | III | 每级咬钩等待 -5 秒 |

### 2.7 通用附魔

| registry id | 中文名 | 最高级 | 效果机制 |
|---|---|---|---|
| `unbreaking` | 耐久 | III | 工具每级 1/(级+1) 概率耗耐久（护甲为 (60+40/(级+1))% 概率） |
| `mending` | 经验修补 | I | 拾取经验球时优先修复主/副手及装备中此附魔物品（2 耐久/经验，与无限互斥） |

### 2.8 诅咒

| registry id | 中文名 | 最高级 | 效果机制 |
|---|---|---|---|
| `binding_curse` | 绑定诅咒 | I | 穿上后无法脱下（创造/死亡除外） |
| `vanishing_curse` | 消失诅咒 | I | 死亡时物品直接消失而非掉落 |

---

## 三、《千相》管线可借鉴的三种触发模式

下表对照项目现有实现，是把"效果挂到物品上"落地的三条成熟路径。新算子/新效果优先复用其一，不要发明第四种。

| 模式 | 触发时机 | 项目实现 | 关键 API | 适用场景 |
|---|---|---|---|---|
| **攻击触发** | 我方武器命中造成最终伤害后 | `combat/CombatEffectHandler.java` | `LivingDamageEvent.Post` 事件 | 点燃、减速、吸血、中毒、霜冻、漂浮等"打上去生效"的效果 |
| **穿戴触发** | 玩家穿着对应装备持续生效 / 被打时反击 | `handler/ArmorPassiveHandler.java`（常驻续杯）+ `combat/ArmorEffectHandler.java`（被击反伤） | 玩家 tick 扫描装备 / `LivingDamageEvent.Post` | 夜视、速度、跳跃、抗性、抗火、水下呼吸、生命恢复等常驻增益；荆棘反伤 |
| **使用触发** | 玩家右键方块/空气 | `item/QianxiangToolItem.java#useOn` / `use` | `InteractionResult` 返回值 | 范围耕地、范围催熟等"主动使用"的工具效果 |

### 3.1 攻击触发（CombatEffectHandler 模式）

- 事件选 `LivingDamageEvent.Post`：此时 `getNewDamage()` 是**最终真实掉血**，吸血量、伤害浮字才准确。1.21 已无 `LivingHurtEvent`，别再用旧名。
- 从攻击者主手物品读 `QianxiangDataComponents.COMPOSED_ATTRIBUTES` 组件取效果等级，然后 `target.addEffect(new MobEffectInstance(...))` 或 `target.igniteForSeconds(...)`。
- 副作用（点火/减速）在 Post 阶段施加最稳妥——目标可能已死，先判 `target.isAlive()`。
- 已知风险：Epic Fight 接管近战管线时该事件可能不触发或攻击者识别异常，见 `docs/epic-fight-compat-brief.md`。

### 3.2 穿戴触发（ArmorPassiveHandler 模式）

- 常驻效果用「**续杯式给药**」：每 tick（或隔若干 tick）扫全身装备槽，把各部件效果等级求和，然后
  `player.addEffect(new MobEffectInstance(effect, EFFECT_DURATION_TICKS, amplifier, true, false, true))`
  ——时长只给一小段（项目用短时长 + 每 tick 续），脱装备后自然消退，无需手动清除。
- `amplifier = 总等级 - 1`，项目封顶 IV 级（`MAX_AMPLIFIER = 3`）；`ambient=true, visible=false` 避免常驻粒子刷屏。
- 被击反伤（ArmorEffectHandler）是穿戴触发的变体：被攻击者侧监听 `LivingDamageEvent.Post`，**必须跳过 MAGIC/THORNS 伤害源防止两名反伤甲玩家互相弹到死**。

### 3.3 使用触发（工具 useOn 模式）

- 覆盖 `Item#useOn(UseOnContext)`（点中方块）与 `Item#use(Level, Player, InteractionHand)`（右键空气，如水壶以脚下为中心催熟）。
- 客户端直接返回 `InteractionResult.SUCCESS`，逻辑全在服务端跑；服务端至少成功处理一格才扣耐久（`hurtAndBreak(1, player, slot)`）并播音效。
- 返回值语义：`SUCCESS` = 已处理且挥臂；`PASS` = 让位给后续交互（如方块自身右键）；异常路径一律 `PASS`，绝不吞掉玩家的正常交互。

### 3.4 三种模式共用的防御纪律（项目约定）

1. 事件/效果代码全程 try-catch 吞异常并记日志（`Qianxiang.LOGGER.warn`），绝不让战斗部崩溃拖垮对局；日志本身也再包一层 try-catch。
2. 一切世界修改只在服务端：`if (level.isClientSide()) return;` 或反向提前 return。
3. 效果强度全部来自材料档位（`ComposedAttributes` 里的 EffectLevels），**代码只负责兑现，不夹带私货数值**——新增效果时 tick 基数定义在 Handler 顶部常量区。
4. 新机制一律「追加不修改既有逻辑」，旧存档/旧产物不能坏。

---

## 附：与《千相》现有 13 效果的映射

《千相》EffectLevels 的 13 个效果槽位已按上表机制实现，新增的"附魔化"扩展（如给锻造产物直接挂原版附魔）应走 §2.1 的 `stack.enchant()` 路径在锻造产物生成时写入 `ENCHANTMENTS` 组件——这是纯数据组件写入，天然兼容旧产物（旧物品没有该组件，行为不变）。
