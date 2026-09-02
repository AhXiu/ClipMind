# ClipMind Phase 1 Backend

Go 1.20+ 标准库优先的模块化单体，实现 Android 批量采集、安全过滤、加密原文备份、异步 AI 卡片流水线，以及 Obsidian 单向同步。

## 快速开始

```bash
cp .env.example .env
set -a; . ./.env; set +a
go run ./cmd/server
```

也可执行 `docker compose up --build`。默认监听 `:8080`，开发模式使用 deterministic LLM；数据和 Vault 分别持久化到配置目录。开发模式未提供备份密钥时，会使用固定的**仅限本地开发**密钥；生产模式必须显式配置密钥和鉴权。

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
| `CLIPMIND_LLM_PROVIDER` | `deterministic` | `deterministic` 或 `openai` |
| `OPENAI_BASE_URL` | OpenAI v1 地址 | Chat Completions 兼容端点 |
| `OPENAI_API_KEY` | 空 | Provider 密钥 |
| `OPENAI_MODEL` | `gpt-4o-mini` | 模型名 |

生产密钥示例：`openssl rand -base64 32`。服务不会记录 `raw_text`；原文备份为 AES-256-GCM 文件。`RawBackup.Delete` 是明确的删除策略扩展点，不作“不可删除”承诺。

## API

- `GET /health`：健康检查（无需鉴权）
- `GET /metrics`：进程内计数器 JSON（无需鉴权）
- `POST /v1/captures:batch`：批量采集，必须带 `Idempotency-Key`；鉴权开启时还需 `Authorization: Bearer ...`
- `GET /v1/cards/{card_id}`：查询卡片
- `GET /v1/cards/{card_id}/versions`：查询不可变版本
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

## 流水线与状态

Worker 执行：清洗 → 受控一级标签 → AI 三段解读（总结/解读/行动）→ OpenLibrary 书籍验证 → Markdown 渲染。一级标签严格限定为：`人文/商业/技术/认知/职场/社会/随笔`。未被 OpenLibrary 真实响应验证的候选不会渲染为确定书目。

状态机覆盖：`received → filtered_pass/filtered_reject → persisted → ai_running → ai_succeeded/ai_failed → awaiting_confirm → published → syncing → synced`。Worker 启动及每次轮询会恢复超过阈值仍处于 `ai_running` 的任务；失败任务可按次数重试。版本号由 repository 在写锁内按卡片最大版本号分配，每次成功输出新的不可变 `CardVersion`。OpenLibrary 的 `verified`、`not_found` 和 `dependency_error` 通过不包含候选文本的日志及指标区分。

## 验证

```bash
go test ./...
go vet ./...
```

Phase 1 不包含向量库、文章搜索或双向同步。
