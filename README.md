# ClipMind

ClipMind 是面向深度阅读用户的剪贴 AI 知识库。Phase 1 聚焦 Android + Shizuku：从系统剪贴板安全采集文本，经本地过滤与可靠 Outbox 上传，生成结构化 Markdown 卡片并单向写入 Obsidian。

## Phase 1 能力

- Android 11–16：Kotlin、Jetpack Compose、Shizuku、Room、WorkManager
- Shizuku 权限状态机、Binder 死亡感知与可见降级
- auto / confirm 两种采集模式
- 端侧与服务端双重敏感信息过滤
- 幂等批量采集、AES-256-GCM 原文备份、可重试 AI Pipeline
- 受控一级标签与三段式 AI 解读
- OpenLibrary 真实书籍元数据校验
- 标准 Markdown 卡片与 Obsidian 单向原子写入

Phase 1 不包含向量知识关联、真实文章搜索、双向同步及 iOS/PC 客户端。

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

第二阶段加入本地关键词关联发现，以及选定 2–8 张卡片后的带原文引用归纳、LLM 关系候选和加密主题笔记。仅处理确认范围，不自动发布；当前仍不含向量语义检索。使用方式和隐私边界见 [第二阶段知识能力](docs/phase2-knowledge.md)。
