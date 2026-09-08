# ClipMind

ClipMind 是面向深度阅读用户的剪贴 AI 知识库。Phase 1 聚焦 Android + Shizuku：从系统剪贴板安全采集文本，经本地过滤与可靠 Outbox 上传，生成结构化 Markdown 卡片并单向写入 Obsidian。

## 当前能力

- Android 11–16：Kotlin、Jetpack Compose、Shizuku、Room、WorkManager
- Shizuku 权限状态机、Binder 死亡感知与可见降级
- auto / confirm 两种采集模式
- 端侧与服务端双重敏感信息过滤
- 幂等批量采集、AES-256-GCM 原文备份、可重试 AI Pipeline
- 固定一级分类、二级标签复用/待确认/合并管理，忠于原文的三段式解读
- OpenLibrary 真实书籍元数据校验
- 标准 Markdown 卡片与 Obsidian 单向原子写入

- 本地向量 Top-5 召回与带原文证据的 LLM 相似/对立/互补关系候选
- 真实文章搜索与正文摘要、已读过滤和低覆盖主题荐书
- 每日 3–5 张间隔复习、高价值思考题、个人批注与 Markdown 周报
- 五页导航、复习小组件、手动 Notion 输出和完整本地知识库 ZIP

向量由远端生成，索引和检索在 Android 本地；不含双向同步及 iOS/PC 客户端。完整功能配置、数据流、限制和验收清单见 [阅读与知识内化闭环](docs/reading-loop.md)。

## 仓库结构

```text
android/   Android + Shizuku 客户端
backend/   Go 模块化单体服务
api/       OpenAPI 接口契约
docs/      安全与工程说明
```

## 快速开始

### 后端

```bash
cd backend
cp .env.example .env
set -a; . ./.env; set +a
go run ./cmd/server
```

详细配置见 [backend/README.md](backend/README.md)。

### Android

```bash
cd android
./gradlew assembleDebug
```

安装应用前需先安装并启动 Shizuku。Android 11+ 可使用无线调试启动；非 Root 设备重启后通常需要重新启动 Shizuku 服务。详细步骤和系统限制见 [android/README.md](android/README.md)。

## 验证

```bash
make test-backend
make vet-backend
make test-android
```

安全基线见 [docs/security.md](docs/security.md)，接口与数据落盘说明见 [docs/api-and-storage.md](docs/api-and-storage.md)，机器可读契约见 [api/openapi.yaml](api/openapi.yaml)。

## 当前状态

Phase 1 核心代码已完成，仍需在目标 Android 机型上执行 Shizuku 真机兼容性验证和端到端联调。

第一阶段体验优化已加入：Android AI 结果加密缓存与离线展示、独立分析/同步状态、内容编辑失效保护、原卡片重新生成、草稿和删除确认。重新生成保留历史版本，需确认后才写入 Obsidian。接口、迁移验证和人工渐进上线流程见 [第一阶段优化说明](docs/phase1-optimization.md)。

第二阶段的关键词发现和多卡归纳仍保留；本轮进一步增加本地向量语义检索及知识内化闭环。旧阶段说明见 [第二阶段知识能力](docs/phase2-knowledge.md)，当前行为以 [阅读闭环说明](docs/reading-loop.md) 为准。真实服务商效果、Android 真机、小组件及 Notion 联调仍需人工授权后验收，代码推送不等于部署或生产验收。
