# ClipMind Backend 安全说明

## 服务端凭证

- 默认 LLM provider 为 Ark。`ARK_API_KEY` 必须由部署平台的 Secret/密钥管理能力注入环境变量，不得写入镜像、Compose 文件、源码或配置仓库。
- OpenRouter 使用 `OPENROUTER_API_KEY`；旧 OpenAI 兼容模式使用 `OPENAI_API_KEY`，采用同样的 Secret 注入规则。
- 服务启动时校验所选 provider 的 key/model，缺失即 fail-fast。校验错误、上游错误、日志与 API 响应均不包含 key。
- Provider key 只用于发往固定 Chat Completions 端点的 `Authorization: Bearer ...` 请求头，不进入请求 body。

## BYOK 边界

BYOK 在客户端完成推理。客户端只把结构化 `client_analysis`（provider、model、标签、三段解读及书籍候选）提交给 backend；**客户端 API key 不经过、不由 backend 接收，也不会持久化**。任何 `api_key` 等契约外字段会被严格 JSON 解码拒绝。

OpenRouter backend provider 的 base URL 固定为 `https://openrouter.ai/api/v1`，不能由 API 请求或环境变量改写，以防止 SSRF。可选 `HTTP-Referer` 与 `X-Title` 仅来自服务端配置，且不影响功能。

## 数据与日志

- `raw_text` 不写入访问日志；原文备份使用 AES-256-GCM。
- 生产环境必须配置 32 字节 Base64 `CLIPMIND_BACKUP_KEY` 并启用 Bearer 鉴权。
- 上游响应正文不会透传到客户端错误，避免泄露供应商细节或潜在敏感内容。
