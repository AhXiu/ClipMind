# ClipMind Phase 1 Backend

Go 1.20+ 标准库优先的模块化单体，实现 Android 批量采集、安全过滤、加密原文备份、异步 AI 卡片流水线，以及 Obsidian 单向同步。

## 快速开始

```bash
cp .env.example .env
set -a; . ./.env; set +a
go run ./cmd/server
```

也可执行 `docker compose up --build`。默认监听 `:8080`，默认使用 Ark；启动前必须通过环境 Secret 注入 `ARK_API_KEY`。测试或无需联网的本地开发可显式设置 `CLIPMIND_LLM_PROVIDER=deterministic`。数据和 Vault 分别持久化到配置目录。开发模式未提供备份密钥时，会使用固定的**仅限本地开发**密钥；生产模式必须显式配置密钥和鉴权。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `CLIPMIND_ENV` | `development` | 设为 `production` 启用启动期强校验 |
| `CLIPMIND_ADDR` | `:8080` | HTTP 地址 |
| `CLIPMIND_DATA_DIR` | `./data` | 原子 JSON repository 与加密备份目录 |
| `CLIPMIND_VAULT_DIR` | `./vault` | Obsidian Vault 输出目录 |
| `CLIPMIND_AUTH_DISABLED` | 开发为 `true` | 仅本地开发可关闭鉴权 |
| `CLIPMIND_AUTH_TOKEN` | 空 | Bearer token；生产必填 |
| `CLIPMIND_BACKUP_KEY` | 空 | 32 字节密钥的 Base64；生产必填 |
| `CLIPMIND_LLM_PROVIDER` | `ark` | `ark`、`openrouter`、兼容旧 `openai`；`deterministic` 仅供测试 |
| `ARK_BASE_URL` | `https://ark.cn-beijing.volces.com/api/v3` | Ark Chat Completions base URL |
| `ARK_API_KEY` | 空 | Ark 服务端密钥，所选 provider 为 Ark 时必填 |
| `ARK_MODEL` | `ep-20260306164116-j9fgc` | Ark model/endpoint ID |
| `OPENROUTER_API_KEY` | 空 | OpenRouter 服务端密钥 |
| `OPENROUTER_MODEL` | 空 | OpenRouter 模型 ID，选择 OpenRouter 时必填 |
| `OPENROUTER_HTTP_REFERER` / `OPENROUTER_X_TITLE` | 空 | 可选固定归因请求头，不作为运行依赖 |
| `OPENAI_BASE_URL` / `OPENAI_API_KEY` / `OPENAI_MODEL` | OpenAI 兼容默认值 | 旧 `openai` provider 兼容配置 |
| `CLIPMIND_EMBEDDING_MODEL` | 空 | 与 `OPENAI_API_KEY` 同时配置才启用向量生成；必须与响应的精确模型身份一致，固定调用 OpenAI 官方 embeddings 地址，不使用 `OPENAI_BASE_URL` |
| `BRAVE_SEARCH_API_KEY` | 空 | 启用真实文章检索；缺失时显式返回未配置提示，不编造链接 |

生产密钥示例：`openssl rand -base64 32`。Ark/OpenRouter/OpenAI key 必须由部署平台 Secret 注入，不得写入文件、日志或响应。OpenRouter base URL 固定为 `https://openrouter.ai/api/v1`，不接受客户端 URL，以阻断 SSRF。服务不会记录 `raw_text`；原文备份为 AES-256-GCM 文件。`RawBackup.Delete` 是明确的删除策略扩展点，不作“不可删除”承诺。详见 [SECURITY.md](SECURITY.md)。

## API

阅读闭环增加 `GET /v1/reading/capabilities` 及 `POST /v1/reading:analyze`、`/v1/reading:embed`、`/v1/reading:weekly`、`/v1/reading:recommend`。沿用 Bearer 鉴权，无状态处理，不自行读取用户历史或触发发布。与知识归纳共享 2 个并发槽，每次请求最多 128 KiB、25 秒超时、不自动重试。模型/检索依赖及限制见 [完整说明](../docs/reading-loop.md) 和 [OpenAPI](../api/openapi.yaml)。服务端当前为单用户共享 Token 模型，不能当作有租户隔离的多用户托管服务。

- `GET /health`：健康检查（无需鉴权）
- `GET /metrics`：进程内计数器 JSON（无需鉴权）
- `POST /v1/captures:batch`：批量采集，必须带 `Idempotency-Key`；鉴权开启时还需 `Authorization: Bearer ...`
- `GET /v1/cards/{card_id}`：查询卡片
- `GET /v1/cards/{card_id}/versions`：查询不可变版本
- `POST /v1/knowledge:synthesize`：仅归纳请求中明确选定的 2–8 张卡片，验证原文引用后返回结果及关系候选；无状态、不自动发布，详见 [第二阶段说明](../docs/phase2-knowledge.md)
- `POST /v1/cards/{card_id}/analyses`：在原卡片上重新生成，提交单条采集结构及新的任务 ID/Idempotency-Key，保留旧版本；结果强制人工确认后同步
- `POST /v1/cards/{card_id}/confirm`：确认、发布并同步
- `POST /v1/cards/{card_id}/rollback`：请求体 `{"version_id":"ver_..."}`，切换活动版本并同步
- `POST /v1/cards/{card_id}/sync:retry`：重试单向同步

批量请求：

```json
{
  "captures": [
    {
      "client_capture_id": "device-local-uuid",
      "raw_text": "摘录内容",
      "text_sha256": "495bab87238250a5eb136a12b7124702bc0aafd24591d5a547b270d33bc1ae1f",
      "source_app": "com.example.reader",
      "source_url": "https://example.com/article",
      "mode": "auto",
      "captured_at": "2026-09-03T01:00:00+08:00"
    }
  ]
}
```

响应使用 `202 Accepted`，分别列出 `accepted` 和 `rejected`。`mode` 必须为 `auto` 或 `confirm`；可选的 `text_sha256` 必须是 `raw_text` 原始 UTF-8 字节的小写 SHA-256。`source_url` 按原值持久化。同一 `Idempotency-Key` 返回持久化的原响应；并发同 key 请求由 per-key 协调器合并。一次批量请求在 repository 内通过内存副本事务只执行一次原子持久化，失败不会污染当前内存状态。跨批次重复 `client_capture_id` 返回原 capture/card，并标记 `duplicate`。

可选 `client_analysis` 的固定契约如下；合法时流水线跳过服务端 LLM，但书籍候选仍由 OpenLibrary 验证。BYOK key 仅由客户端直连 provider 使用，**不经过 backend**，请求中不得出现 API key：

```json
{
  "provider": "ark|openrouter",
  "model": "模型ID",
  "primary_tag": "技术",
  "interpretation": {"summary": "...", "insight": "...", "action": "..."},
  "books": [{"title": "...", "author": "..."}]
}
```

`client_analysis` 验证失败只拒绝对应 capture，不导致批次 500。CardVersion 通过 `llm_provider`、`llm_model` 记录服务端或客户端分析来源。

## 流水线与状态

Worker 执行：保留原文 → 受控一级标签 → AI 三段解读（核心释义/场景应用/认知启发）→ OpenLibrary 书籍验证 → Markdown 渲染。`schema_version=2` 使用新含义；旧版 BYOK 保留旧格式。一级标签严格限定为：`人文/商业/技术/认知/职场/社会/随笔`。未被 OpenLibrary 真实响应验证的候选不会渲染为书目，元数据匹配不能证明摘抄出自该书。

状态机覆盖：`received → filtered_pass/filtered_reject → persisted → ai_running → ai_succeeded/ai_failed → awaiting_confirm → published → syncing → synced`。Worker 启动及每次轮询会恢复超过阈值仍处于 `ai_running` 的任务；失败任务可按次数重试。版本号由 repository 在写锁内按卡片最大版本号分配，每次成功输出新的不可变 `CardVersion`。OpenLibrary 的 `verified`、`not_found` 和 `dependency_error` 通过不包含候选文本的日志及指标区分。

## 验证

```bash
go test ./...
go vet ./...
```

原文备份为加密的版本化 JSON，结构化 `store.json` 和输出 Markdown 不是加密数据库，必须保护目录访问权限。新阅读接口结果由 Android 加密保存和导出；既有 Obsidian 自动同步路径不会自动带上仅在本地确认的关系、文章和批注。双向同步不在本轮范围。
