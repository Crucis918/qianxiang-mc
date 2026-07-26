# 千相 UGC 与 AI 生态指南

千相的内容扩展完全不需要写 Java。本文面向三类人:**数据包/整合包作者**、**想分享配方的玩家**、**服主**。

---

## 一、数据驱动材料库(整合包作者)

在 `data/<你的命名空间>/phase_materials/*.json` 里给**任意物品**(原版/千相/其他 mod)定义完整相数据:

```json
{
  "item": "minecraft:nether_star",
  "tier": "legendary",
  "phases": ["order", "transcend"],
  "functions": ["mana", "strength", "regeneration"],
  "effects": {"minecraft:regeneration": 3}
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `item` / `items` | 二选一 | 单个物品 id,或 `"items": [...]` 批量绑定同一份数据 |
| `tier` | 否 | `common` / `rare` / `epic` / `legendary`,大小写不敏感,缺省 common |
| `phases` | 否 | 相性(见 `Phase` 枚举:order/life/time/knowledge/abyss/transcend/conflict/neutral/chaos/fire/frost/shadow/light),缺省按 functions 自动推导 |
| `functions` | functions 与 effects 至少一个 | 功能算子(见 `PhaseFunction`:base_metal/ignite/edge/mana/...) |
| `effects` | 同上 | 状态效果 id → 等级(1-10),支持任意 mod 的效果 |

要点:

- **热更新**:`/reload` 即生效,同时自动整表同步到所有在线客户端(锻造台预览一致)。
- **优先级**:Java 注册的 PhaseData component > **本注册表** > 功能 tag > 概念推导。
- **AI 感知**:定义的材料带真实品阶进入 AI 材料库,AI 会按 legendary 材料对待你的 nether_star。
- 对应 mod 未安装时该条目自动跳过并 WARN,不会崩。
- 本 mod 自带两个示例:`data/qianxiang/phase_materials/nether_star.json`、`heart_of_the_sea.json`。

旧的两条扩展路径依然可用(与本注册表叠加):

- 功能 tag:`data/qianxiang/tags/item/materials/<function>.json`
- 效果 tag:`data/qianxiang/tags/item/materials/effect/<effect_path>.json`(模组效果用 `effect/<namespace>/<path>` 子目录)

## 二、蓝图分享码(玩家)

把自己的锻造配方(含 AI 法术/动作定制)分享给任何人:

```
/qianxiang blueprint list            # 看自己的蓝图和序号
/qianxiang blueprint export 2        # 把 #2 编成分享码,点击聊天里的 [点击复制分享码]
/qianxiang blueprint import QXBP1.…  # 粘贴别人的码,一键入库
```

- 码是 `QXBP1.` 前缀 + 压缩 NBT 的 Base64,可以贴在群/论坛/朋友圈任何地方。
- 导入侧有完整消毒:大小上限 64KB、材料数 ≤10、名称/JSON 字段截断;spellJson 再经法术白名单二次校验。
- 蓝图里引用了你没装的 mod 材料时会提示,仍可导入,凑齐材料就能用。

## 三、服务器工坊(服主/多人服)

全服共享的蓝图画廊,存在存档 `data/qianxiang_workshop.dat`:

```
/qianxiang workshop publish 2     # 把自己的 #2 蓝图发布到全服
/qianxiang workshop list [页码]   # 浏览,每条带 [取用] 按钮
/qianxiang workshop take 5        # 把工坊 #5 复制到自己库里(计数+1)
/qianxiang workshop remove 5      # 下架(本人或 OP)
```

- 容量 200 条,单作者最多 20 条;同作者同名重复发布会覆盖旧版本并保留取用计数。
- 取用次数公开显示,好配方自然浮出。

## 四、AI 层(所有人)

- 配置:`config/qianxiang-ai.json`(Ollama 或任意 OpenAI 兼容端点),游戏内锻造台 AI 设置界面也可改。
- `/qianxiang ai status` — 查看 provider/模型/请求计数/缓存命中/失败情况(不显示 apiKey)。
- `/qianxiang ai clearcache` — 清空响应缓存(换模型、调 prompt 后用)。
- 相同请求 10 分钟内直接回缓存;失败自动重试一次;AI 完全不可用时退关键词兜底配方,游戏始终可玩。
- 每次 AI 交互记录一行 JSON 到 `logs/qianxiang-ai.jsonl`(时间/模型/是否缓存/耗时/输入摘要),超 5MB 自动轮转。
