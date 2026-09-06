# ClipMind Phase 1 接口与数据存储说明

> 适用版本：Android + Shizuku MVP（Phase 1）
>
> 接口基址示例：`http://<server-host>:8080`
>
> 契约源文件：[`api/openapi.yaml`](../api/openapi.yaml)

## 1. 快速结论：请求后数据存在哪里

一次剪贴内容从手机发往服务端后，会经过以下存储层：

1. **Android 本地 Outbox**：上传前暂存在 Room 数据库 `clipmind.db` 的 `capture_outbox` 表中；正文保存为 Android Keystore 保护的 AES-GCM 密文 `encryptedRawText`。
2. **后端状态库**：接收成功后写入 `${CLIPMIND_DATA_DIR}/store.json`；默认是 `./data/store.json`，Docker Compose 中位于 `clipmind-data` Volume 的 `/data/store.json`。
3. **原文加密备份**：同时写入 `${CLIPMIND_DATA_DIR}/raw/<capture_id>.bin`；启用 `CLIPMIND_BACKUP_KEY` 时为 AES-256-GCM 密文，Docker Compose 中位于 `clipmind-data` Volume。
4. **知识卡版本**：AI 处理后，卡片元数据与 Markdown 版本写回 `store.json`。
5. **Obsidian 文件**：自动模式处理成功，或确认模式经确认后，最终 Markdown 原子写入 `${CLIPMIND_VAULT_DIR}/<card_id>.md`；Docker Compose 中位于 `clipmind-vault` Volume 的 `/vault`。

> **当前实现的重要安全事实**：虽然原文备份文件支持 AES-256-GCM 加密，但 `store.json` 中的 `captures[].text` 仍保存清洗后的正文，`versions[].markdown` 也可能包含正文片段。因此当前服务端状态库不能视为“仅存密文”。生产部署必须保护数据目录、限制文件权限并使用加密磁盘；后续应将正文从状态索引中拆出或进行字段级加密。

## 2. 通用约定

### 2.1 鉴权

除 `GET /health`、`GET /metrics` 外，所有 `/v1/*` 接口默认使用 Bearer Token：

```http
Authorization: Bearer <CLIPMIND_AUTH_TOKEN>
```

- 开发环境默认可关闭鉴权（`CLIPMIND_AUTH_DISABLED=true`）。
- 生产环境强制要求启用鉴权且 `CLIPMIND_AUTH_TOKEN` 非空。
- 未通过鉴权返回 `401 unauthorized`。

### 2.2 请求追踪

客户端可传：

```http
X-Request-ID: req_client_generated_id
```

若未传，服务端生成请求 ID。响应头和错误体都会返回该值。

### 2.3 Content-Type

请求和响应均使用：

```http
Content-Type: application/json
```

### 2.4 统一错误结构

```json
{
  "error": {
    "code": "invalid_request",
    "message": "Idempotency-Key is required",
    "request_id": "req_..."
  }
}
```

常见 HTTP 状态：

| HTTP | code | 含义 |
|---|---|---|
| 400 | `invalid_request` | 请求体、幂等键或必填字段不合法 |
| 401 | `unauthorized` | Bearer Token 缺失或错误 |
| 404 | `not_found` | 路由、Card 或 Version 不存在 |
| 409 | `invalid_state` | 当前卡片状态不允许确认、回滚或重试 |
| 500 | `internal_error` | 持久化、备份或内部处理失败 |

## 3. 接口总览

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---:|---|
| GET | `/health` | 否 | 服务存活检查 |
| GET | `/metrics` | 否 | 获取进程内计数指标快照 |
| POST | `/v1/captures:batch` | 是 | 批量提交剪贴内容 |
| GET | `/v1/cards/{cardId}` | 是 | 查询知识卡 |
| GET | `/v1/cards/{cardId}/versions` | 是 | 查询知识卡历史版本 |
| POST | `/v1/cards/{cardId}/confirm` | 是 | 确认并发布 confirm 模式卡片 |
| POST | `/v1/cards/{cardId}/rollback` | 是 | 回滚到指定版本 |
| POST | `/v1/cards/{cardId}/sync:retry` | 是 | 重试 Obsidian 同步 |

## 4. 健康检查

### `GET /health`

成功响应：`200 OK`

```json
{
  "status": "ok"
}
```

此接口只说明 HTTP 服务可响应，不代表外部 LLM、OpenLibrary 或存储目录一定健康。

## 5. 指标快照

### `GET /metrics`

成功响应：`200 OK`

```json
{
  "http_requests_total": 12,
  "capture_batches_total": 3,
  "openlibrary_verified_total": 1
}
```

当前返回 JSON 计数器快照，不是 Prometheus 文本格式。指标只在内存中累计，进程重启后清零。

## 6. 批量提交剪贴内容

### `POST /v1/captures:batch`

必需请求头：

```http
Authorization: Bearer <token>
Idempotency-Key: <stable-batch-key>
Content-Type: application/json
```

限制：

- 每批必须包含 `1–100` 条记录。
- 请求体最大约 `2 MiB`。
- JSON 中出现未知字段会被拒绝。
- 相同 `Idempotency-Key` 会重放首次处理结果，不重复创建记录。
- `client_capture_id` 用于单条采集幂等；同一批内不可重复。

请求示例：

```json
{
  "captures": [
    {
      "client_capture_id": "01JABCDEF0123456789",
      "raw_text": "The best way to predict the future is to invent it.",
      "text_sha256": "<raw_text 的小写十六进制 SHA-256>",
      "source_app": "com.android.chrome",
      "source_url": "https://example.com/article",
      "mode": "auto",
      "captured_at": "2025-03-08T10:30:00Z"
    }
  ]
}
```

字段说明：

| 字段 | 必填 | 类型 | 说明 |
|---|---:|---|---|
| `client_capture_id` | 是 | string | Android 生成的稳定采集 ID |
| `raw_text` | 是 | string | 原始剪贴正文；服务端会执行安全过滤与清洗 |
| `text_sha256` | 否 | string | `raw_text` 的小写十六进制 SHA-256；传入时必须匹配 |
| `source_app` | 否 | string | 来源应用包名 |
| `source_url` | 否 | string | 来源 URL |
| `mode` | 是 | string | 仅允许 `auto` 或 `confirm` |
| `captured_at` | 否 | RFC3339 | 客户端采集时间；缺失或零值时使用服务端当前时间 |

成功接收返回：`202 Accepted`

```json
{
  "accepted": [
    {
      "client_capture_id": "01JABCDEF0123456789",
      "capture_id": "cap_...",
      "card_id": "card_..."
    }
  ],
  "rejected": []
}
```

单项拒绝示例：

```json
{
  "accepted": [],
  "rejected": [
    {
      "client_capture_id": "01JABCDEF0123456789",
      "code": "invalid_text_sha256",
      "message": "text_sha256 must be the lowercase SHA-256 of raw_text"
    }
  ]
}
```

单项拒绝码：

| code | 含义 |
|---|---|
| `invalid_client_capture_id` | 缺少 `client_capture_id` |
| `invalid_mode` | `mode` 不是 `auto` 或 `confirm` |
| `invalid_text_sha256` | 文本哈希与正文不匹配 |
| `duplicate_in_batch` | 同一请求批次内 ID 重复 |
| `filtered_reject` | 正文未通过服务端安全过滤 |

> HTTP `202` 表示采集已持久化并进入异步 Pipeline，不表示 AI 分析或 Obsidian 同步已经完成。客户端应使用返回的 `card_id` 查询后续状态。

## 7. 查询知识卡

### `GET /v1/cards/{cardId}`

成功响应：`200 OK`

```json
{
  "id": "card_...",
  "capture_id": "cap_...",
  "status": "published",
  "current_version_id": "ver_...",
  "created_at": "2025-03-08T10:30:01Z",
  "updated_at": "2025-03-08T10:30:02Z",
  "last_error": ""
}
```

`status` 可能值：

- `persisted`：已接收，等待异步处理。
- `ai_running`：正在执行清洗、标签、AI 解读与书籍校验。
- `ai_succeeded`：AI 处理完成。
- `ai_failed`：AI 处理失败。
- `awaiting_confirm`：confirm 模式等待用户确认。
- `published`：已发布并进入同步流程。
- `syncing`：正在同步 Obsidian。
- `synced`：Obsidian 文件写入成功。

## 8. 查询版本历史

### `GET /v1/cards/{cardId}/versions`

成功响应：`200 OK`

```json
[
  {
    "id": "ver_...",
    "card_id": "card_...",
    "version": 1,
    "markdown": "# ...",
    "summary": "...",
    "labels": ["..."],
    "book_verified": false,
    "created_at": "2025-03-08T10:30:02Z"
  }
]
```

版本只追加、不原地覆盖。`markdown` 与 `summary` 可能包含原文内容，应按敏感数据保护。

## 9. 确认发布

### `POST /v1/cards/{cardId}/confirm`

请求体为空。仅 `awaiting_confirm` 状态允许确认。

成功响应：`200 OK`，返回更新后的 Card。确认后状态转为 `published`，Worker 随后尝试写入 Obsidian。

## 10. 回滚版本

### `POST /v1/cards/{cardId}/rollback`

请求：

```json
{
  "version_id": "ver_..."
}
```

成功响应：`200 OK`，返回更新后的 Card。

回滚不会删除历史版本；它会复制目标版本内容，创建一个新的版本号，并将其设为 `current_version_id`，随后重新进入发布/同步流程。

## 11. 重试同步

### `POST /v1/cards/{cardId}/sync:retry`

请求体为空。适用于已存在当前版本、但 Obsidian 同步未完成的卡片。

成功响应：`200 OK`，卡片状态回到 `published`，等待 Worker 再次同步。

## 12. 数据流与生命周期

### 12.1 Android 本地

| 阶段 | 位置 | 内容 | 清理时机 |
|---|---|---|---|
| 捕获后、上传前 | `clipmind.db` / `capture_outbox` | 正文密文、hash、来源、模式、重试信息 | 服务端接受或明确拒绝后删除 |
| Token | Android Keystore + SharedPreferences | 加密后的 Bearer Token | 用户清除配置/应用数据 |
| 正文密钥 | Android Keystore | AES-GCM 密钥材料 | 卸载应用或清除系统密钥 |

Room Outbox 不保存明文正文。Worker 上传前在内存中解密，网络请求完成后按结果更新或删除队列项。

### 12.2 后端接收事务

对每条通过校验和过滤的采集，服务端先创建原文备份，再通过一次 Repository 事务写入：

- Capture 记录；
- Card 记录；
- `Idempotency-Key` 对应的响应 Receipt。

若事务失败，会尝试删除本批已创建的备份文件，避免状态库与备份不一致。

### 12.3 异步 Pipeline

Worker 从 `persisted` 卡片开始处理：

1. 更新为 `ai_running`；
2. 清洗与结构化正文；
3. 生成受控标签；
4. 生成 AI 三段解读；
5. 调用 OpenLibrary 校验书籍元数据；
6. 生成 Markdown，并追加 CardVersion；
7. `confirm` 模式进入 `awaiting_confirm`；`auto` 模式进入 `published`；
8. 将当前版本原子写入 Obsidian Vault；成功后更新为 `synced`。

### 12.4 后端文件布局

非 Docker 默认布局：

```text
backend/
├── data/
│   ├── store.json
│   └── raw/
│       └── cap_<id>.bin
└── vault/
    └── card_<id>.md
```

Docker Compose 布局：

```text
clipmind-data volume
└── /data
    ├── store.json
    └── raw/cap_<id>.bin

clipmind-vault volume
└── /vault
    └── card_<id>.md
```

可通过以下环境变量修改：

| 变量 | 默认值 | 用途 |
|---|---|---|
| `CLIPMIND_DATA_DIR` | `./data` | 状态库与原文备份根目录 |
| `CLIPMIND_VAULT_DIR` | `./vault` | Obsidian Markdown 输出目录 |
| `CLIPMIND_BACKUP_KEY` | 空 | Base64 编码的 32 字节 AES-256 密钥；生产必填 |

生成备份密钥示例：

```bash
openssl rand -base64 32
```

## 13. 存储安全建议

当前 Phase 1 是单机文件存储，生产/公网部署至少应做到：

- 使用 `CLIPMIND_ENV=production`，启用 Bearer Token，配置独立高强度 Token。
- 设置 Base64 编码的 32 字节 `CLIPMIND_BACKUP_KEY`，并放入 Secret 管理系统，不提交到 Git。
- 对 `CLIPMIND_DATA_DIR` 和 `CLIPMIND_VAULT_DIR` 使用加密磁盘、最小文件权限和定期备份。
- 不在日志、Metrics 标签、错误消息或通知中记录 `raw_text`、Markdown 正文或 Token。
- 将 `/health`、`/metrics` 是否公网暴露交由反向代理控制；当前应用层不鉴权这两个端点。
- 在面向多用户或互联网部署前，将文件 Repository 迁移到具备事务、访问控制和备份恢复能力的数据库/对象存储。
- 优先整改 `store.json` 中的正文与 Markdown 明文副本，避免“备份已加密但状态库仍含正文”的误判。

## 14. 调试示例

```bash
curl -sS http://127.0.0.1:8080/health
```

```bash
curl -sS -X POST http://127.0.0.1:8080/v1/captures:batch \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -H 'Idempotency-Key: demo-batch-001' \
  -d '{
    "captures": [{
      "client_capture_id": "demo-capture-001",
      "raw_text": "A useful paragraph copied from an article.",
      "source_app": "curl",
      "mode": "auto",
      "captured_at": "2025-03-08T10:30:00Z"
    }]
  }'
```

拿到 `card_id` 后查询：

```bash
curl -sS http://127.0.0.1:8080/v1/cards/<card_id> \
  -H 'Authorization: Bearer <token>'
```
