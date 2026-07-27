# 牵响 Qianxiang

> 告诉 AI 你想要什么,它帮你找材料,你来锻造。

NeoForge 1.21.1 模组。核心理念:**材料即零件,AI 当配方大脑,强度靠材料**。

## 核心玩法

1. **相锻造** — 锻造台不吃固定配方。25 格（5×5）材料位按「形状」摆料:中心 1 格=核心(青环)、内圈 8 格=辅助(紫环)、外圈 16 格=基底(橙环);原版物品也是零件,产物属性完全由材料的品阶、相性、功能算子决定(超过 10 件的零件强度分按半权重计入,防 25 格线性膨胀)。
2. **AI 配方大脑** — 在锻造台/炼金台里用自然语言说"我要一把会喷火的追踪剑",AI(本地 Ollama 或任意 OpenAI 兼容端点)从材料库挑出配方并生成专属法术;AI 不在线时退关键词兜底配方,**游戏永远可玩**。
3. **自由法术** — 9 元素 × 5 形式 × 5 效果 × 5 修饰的组合法术引擎。法杖/魔法书是**增幅器**(法术伤害 +%、法力上限 +X,主手+副手同时生效、效果叠加),不再承载法术;法术本体来自**炼金台**:摆材料或问 AI 炼出魔法卷轴 → 右键学习进已学列表 → 按住 V 弹出轮盘,指向扇区松开即施放(点按 V 快速施放上次法术)。
4. **万象森罗** — amplified 奇崛地形的独立维度:虚空矿、微光树、流浪 NPC,以及漫游 Boss **森罗守望者**(传奇材料的主要来源)。
5. **NPC 与烙印** — 流浪相师与深渊商人有真实交易和相反的价值观:相师厌恶屠夫,深渊欣赏强者——你的行为(屠杀/外交烙印)直接影响价格。
6. **相谱** — 律二"不可逆铭刻":锻造、击杀、交易全部烙进玩家传记,`/qianxiang saga` 翻阅。**位格**随锻造(+1)与讨伐守望者(+5)提升,进入万象森罗需位格 ≥ 3。

## 快速开始

```bash
./gradlew runClient     # 或双击 start.sh
```

生存流程:拾相材料 → 合成锻造台 → 锻造增幅器(法杖/魔法书) → 合成炼金台 → 摆料或问 AI 炼卷轴 → 右键学习 → 按住 V 轮盘施法 → 集裂隙精髓 → 右键裂隙岩进万象森罗 → 打守望者 → 传奇材料。成就树(进度 → 千相)全程引导。

测试:`/qianxiang kit` 一键领全套;`/qianxiang dim` 快速往返维度。测试链:kit → 锻造增幅器 → 炼金台 AI 卷轴 → 右键学习 → V 轮盘施法。

## AI 配置

`config/qianxiang-ai.json`(首次启动自动生成,游戏内锻造台 AI 设置界面也可改):

```json
{
  "provider": "ollama",              // 或 "openai"(任意兼容端点:LM Studio/DeepSeek/通义…)
  "base_url": "http://localhost:11434",
  "api_key": "",
  "model": "qwen2.5:7b",
  "timeout_seconds": 5
}
```

`/qianxiang ai status` 查看健康度(请求/缓存/失败计数);AI 交互日志在 `logs/qianxiang-ai.jsonl`。

## UGC 生态

不写一行 Java 就能扩展内容,详见 [docs/ugc-ecosystem-guide.md](docs/ugc-ecosystem-guide.md):

- **数据驱动材料** — `data/<ns>/phase_materials/*.json` 给任意物品(含其他 mod)定义相数据,`/reload` 热生效
- **蓝图分享码** — `/qianxiang blueprint export` 生成可粘贴的分享码,好友一键导入
- **服务器工坊** — `/qianxiang workshop publish|list|take` 全服共享蓝图画廊
- **功能/效果 tag** — datapack 或 KubeJS 往 `qianxiang:materials/*` tag 加物品即可参与锻造

## 构建

```bash
./gradlew build         # jar 输出至 build/libs/
./gradlew runServer     # 专用服务端(dev)
```

依赖:Java 21,NeoForge 21.1.219。战斗增强软依赖 [Epic Fight](https://modrinth.com/mod/epic-fight)(纯数据包适配,不装不崩)。

## 文档

- [UGC 与 AI 生态指南](docs/ugc-ecosystem-guide.md)
- [Epic Fight 适配简报](docs/epic-fight-compat-brief.md)
- [整合包兼容性笔记](docs/modpack-compat-notes.md)

## 许可

GPL-3.0
