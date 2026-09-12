# LLM 服务商与模型选择

## Android 配置

设置 > AI 服务：先选择服务商，再输入该平台签发的 API Key，确认后加密保存并获取实时模型目录，最后搜索、选择模型。已有 Key 时，确认切换服务商后自动重新获取；进入设置不会未经确认发送旧 Key，也可以点击“刷新实时模型列表”。Key 不需要 `Bearer ` 前缀。

| 服务商 | 固定 API Base URL | 模型目录 | 凭证来源 |
| --- | --- | --- | --- |
| Kimi 官方 | `https://api.moonshot.cn/v1` | `GET /models` | Kimi API 开放平台 |
| 智谱 GLM 官方 | `https://open.bigmodel.cn/api/paas/v4` | 未接入已确认的目录 API，手动填写 | 智谱通用 API 平台 |
| OpenAI GPT 官方 | `https://api.openai.com/v1` | `GET /models`，筛选 Chat Completions 文本候选 | OpenAI API 平台 |
| Claude / Anthropic 官方 | `https://api.anthropic.com/v1` | `GET /models`，游标分页 | Anthropic Console API Key |
| Google Gemini 官方 | `https://generativelanguage.googleapis.com/v1beta` | `GET /models`，筛选 `generateContent`，分页 | Google AI Studio Gemini API Key |
| DeepSeek 官方 | `https://api.deepseek.com/v1` | `GET /models` | DeepSeek API 平台 |
| 通义千问 Qwen（新加坡） | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | `GET https://dashscope-intl.aliyuncs.com/api/v1/models`，页码分页 | 百炼新加坡区域 Key |
| OpenRouter | `https://openrouter.ai/api/v1` | `GET /models`，跨厂商文本候选 | OpenRouter |
| 火山方舟 Ark | `https://ark.cn-beijing.volces.com/api/v3` | 填写属于自己账号的 `ep-...` 部署 ID | 火山方舟 |
| 后端托管 | 使用现有 ClipMind 后端地址 | 由后端部署配置决定 | 无需在 Android 保存模型 Key |

已移除写死的模型目录。列表只展示当前服务商最近一次成功获取的文本模型候选；当前、最近模型仅在本次结果中确实存在时置顶。目录变化不会自动选中第一项、覆盖已保存模型或修改排队任务。列表未包含的已保存模型单独提示，可核对后继续使用自定义 ID。搜索只搜索已获取的内存结果，不逐次联网。

目录成功不代表模型推理权限、余额或结构化输出兼容性已验证。OpenAI 目录没有完整的端点能力字段，因此按名称保守排除音频、图片、embedding、Codex、Pro 等不适用于当前 Chat Completions 接入的候选；特殊或自定义模型仍可手动填写，但不保证兼容。获取失败、空列表、无已接入接口均分别显示，不回退到旧目录冒充实时结果。

Claude、Gemini、DeepSeek、Qwen 已有上述官方直连入口。OpenRouter 仍只填写 OpenRouter Key；其 BYOK 需在 OpenRouter 平台配置厂商 Key，不能把厂商 Key 直接当作 OpenRouter 鉴权。官方模型 ID 与 OpenRouter 的 `厂商/模型` ID 不通用。

Claude Code 是编程工具，不是另一个通用 API 服务商；选择“Claude / Anthropic 官方”并使用 Console API Key。Claude、ChatGPT、Codex、Kimi Code、GLM Coding Plan 等订阅与通用 API 权限、额度不等价，不模拟登录、不读取 Cookie、不使用订阅登录 Token。Qwen 本轮限定新加坡区域，使用其兼容域名；北京等其他区域及工作空间专用端点暂未接入，不能混用区域 Key，也不会自动跨区尝试。

目录请求只发送当前服务商 Key 和分页参数，不发送卡片，不调用模型生成。只保留内存结果，不记录 Key、原始响应或错误正文；换服务商或成功更换/清除 Key 会取消旧请求、清除列表并用请求代次阻止迟到响应覆盖。鉴权失败、限流与分页异常不自动重试；最多 20 页、5000 个原始条目、单页 2 MiB、累计 10 MiB，总超时 45 秒，超限或中途失败不显示部分结果。分页只将游标编码到固定官方 URL 的参数中，不访问响应中的任意链接。

### Kimi K3 测试失败排查

2026-09-12 再次核对 Kimi 官方 K3 文档：`kimi-k3` 与 `https://api.moonshot.cn/v1/chat/completions` 均为文档中的接入值，并非看到 404 就能判断模型已下线。

- 官方「访问条件」说明：开放平台完成充值（文档当前列出最低充值金额 10 元）后解锁 K3，注册认证赠送的 15 元代金券不可用于 K3；聊天会员不等于此 API 权限。金额与政策可能变化，以官方页面为准。不要仅凭一次测试失败重复充值。
- 在同一开放平台账号内核对 Key、模型使用权限、充值状态及可用余额；不要把 Kimi Code 或其他平台 Key 用于通用 API。若条件已满足但仍失败，应保留 HTTP 状态和固定错误分类交由平台排查，不发送完整 Key、Token 或原始请求正文。
- 客户端现在区分本地未选模型、HTTP 404、权限不足、认证失败和额度不足。仅解析服务商结构化 `error.code/type/status` 中的已知分类，不展示或持久化可能包含敏感信息的原始错误正文。通用 404 不再直接宣称模型下线，设置页同时展示测试接口及 K3 访问条件。
- 修复不自动换模型、不扩大 token 预算、不重试测试，也不修改已排队任务的模型。只读取了公开文档，并用拦截器/构造响应做本地回归；未读取用户 Key、未调用真实模型，不能据此确认某个用户账号的实际状态。

### 卡片整理被后端拒绝

`REJECTED_invalid_client_analysis` 来自 ClipMind 后端，不是模型厂商的鉴权错误。客户端已有有效分析缓存时，优先核对实际运行的后端是否支持当前 provider、一级分类及字段长度；只推送代码不等于部署了后端。当前源码支持新增的 `anthropic`、`gemini`、`deepseek`、`qwen`，但不能据此推断设备连接的后端版本。

- 新客户端仅将后端固定校验提示映射为服务商、模型、分类、释义、启发或书籍错误分类，不保存或展示任意原始错误正文。旧错误码没有具体原因时明确保留不确定性。
- 这种拒绝是终止状态，不再后台反复重试。旧客户端遗留的同类重试任务也会在下一次处理时本地停止，不重新调用模型或上传。
- 后端会保存拒绝回执，“重试原任务”无法绕过旧回执，即使后端后来已更新。确认后端已更新或校验问题已处理后，打开卡片点击“重新提交已有结果”，确认发送原文和已有分析。新提交在本地事务中更换提交标识，保留加密缓存、服务商、模型和云端卡片 ID；后端仍完整校验，不删除旧回执、不额外调用 LLM。
- 如果确实需要修正或重新生成分析，使用“更多 AI 与同步操作 → 重新整理”，此操作会使用当前配置重新调用模型并可能计费，仍需单独确认。未解决校验问题前不要反复提交或更换 Key。
- 本轮只修改本地客户端处理和回归测试，不更新任何运行中的服务。先在离线测试和 BOE 测试账号验证拒绝分类、缓存复用、旧回执、新提交以及清除/删除隔离，再按下文流程人工小范围启用。

## 范围与安全

- 该选择用于采集摘录的卡片分析、多卡归纳、语义召回后的关系判断。完整阅读分析、搜索、向量生成、荐书、周报仍使用后端原有配置，设置页明确说明此边界。
- Key 按服务商分开保存在 Android Keystore 保护的密文中，不回显，不写入日志、Room 任务、导出文件或 ClipMind 后端。应用不接收自定义 Base URL。
- 所有模型请求只使用固定 HTTPS 接口，禁止重定向，不进行 HTTP 客户端自动重试，不会自动切换模型或服务商。OpenRouter 自身仍可能在该模型的托管供应商间路由，受 OpenRouter 的服务条款约束。
- 原有任务记录保留创建时的服务商和模型；切换下拉框只影响后续任务。Worker 根据任务中的服务商取 Key，而不是根据当前界面取 Key。清除某服务商的 Key 不影响其他服务商。
- 旧版通用 Key 缺少可信服务商归属，因此保留密文但停止自动使用。用户需明确将其迁移至 Ark 或 OpenRouter，或重新输入。迁移不能覆盖已有 Key。因缺 Key 失败的旧任务需要用户手动重试。
- 保存 Key、切换服务商和手动刷新目录需要确认；确认文案明确目录请求的目标与不发送正文的范围。测试仍须单独确认，才发送固定测试文本进行一次可能计费的调用；不读取用户剪贴板或卡片。取消已发送请求不能保证撤销平台计费。
- 常规采集 Worker 保留原有持久化重试机制：网络失败、限流等会延迟重试，已被平台接受但未返回的请求可能重复计费。需要严格控制费用时关闭自动提交，仅手动触发；不能将 HTTP 层不重试理解为任务永不重试。
- Claude 使用原生 `/messages`、`x-api-key`、固定 `anthropic-version`，通过唯一的 `return_json` 工具输出 JSON 数据，不执行任何工具。Gemini 使用原生 `generateContent` 与 `x-goog-api-key`，Key 不进入 URL；使用 `responseMimeType=application/json`。单卡与多卡归纳共用这些协议及结束状态校验。
- Claude / DeepSeek 的输出上限为 4096，Qwen 为 8192，Gemini 和原有新增服务商为 16384；DeepSeek、Qwen 关闭思考，Kimi K3 保留低推理强度，不额外设置不兼容的采样参数。达到 token 上限或结束原因异常的结果不作为成功结果保存，不自动扩大预算、重试生成或切换模型。
- 输入维持本地安全过滤；输出仍需 JSON、分类、长度和引用校验。模型失败不丢弃本地原文。BYOK 分析成功后的原文与分析结果仍按原有同步流程发送到 ClipMind 后端，因此 BYOK 不等于全文不出后端。

## 验证与渐进启用

1. 本地运行 JVM 测试、后端测试和 Debug 构建。网络单测用拦截器或 localhost 返回虚构响应，不使用实际服务商 Key。
2. 在 BOE 更新后端以接受 `anthropic`、`gemini`、`deepseek`、`qwen`（保留原 provider）的客户端结果，再安装新版 Android；旧后端会拒绝新 provider，不可直接将客户端单独投用。
3. 经人工授权，用专用低额度测试账号逐家验收：保存与切换后的实时列表、完整分页、空列表、403/429、无网络、快速切换服务商、替换/清除 Key、模型消失、自定义 ID 和大字体搜索。生成测试另行确认，并验收结构化输出、密钥隔离与队列快照。真实调用会发送数据并可能产生费用；本次只验证伪造响应，不代表真机或账号实际可用性已验收。
4. 人工审批后仅向小范围测试用户启用，关闭自动提交与自动学习，确认各平台数据合规和计费。观察结构错误率、拒绝率与费用，只采集聚合指标，不采集 Key、请求正文或服务商错误正文。
5. 扩大范围前再次人工确认。发生异常时先关闭 AI/自动任务，再人工处理队列；不得将积压任务自动切到其他服务商。保留本地加密数据，禁止清库或删除历史记录作为降级手段。本次代码变更不执行任何部署或生产操作。

## 官方依据

- [Kimi 模型列表](https://platform.kimi.com/docs/models)
- [Kimi K3 参数约束](https://platform.kimi.com/docs/guide/kimi-k3-quickstart)
- [Kimi API 与会员区别](https://platform.kimi.com/docs/guide/product-plans)
- [GLM OpenAI 兼容 API](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction)
- [GLM 结构化输出](https://docs.bigmodel.cn/cn/guide/capabilities/struct-output)
- [GPT-4.1 Mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini)
- [GPT-5 Mini](https://developers.openai.com/api/docs/models/gpt-5-mini)
- [GPT-6 Astra](https://developers.openai.com/api/docs/models/gpt-6-astra)
- [OpenRouter 公开模型目录](https://openrouter.ai/api/v1/models)
- [OpenAI 模型列表 API](https://developers.openai.com/api/reference/resources/models/methods/list)
- [Kimi 模型列表 API](https://platform.kimi.com/docs/api/list-models)
- [Anthropic 模型列表](https://platform.claude.com/docs/en/api/models/list)
- [Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create)
- [Gemini 模型列表](https://ai.google.dev/api/models)
- [Gemini generateContent](https://ai.google.dev/api/generate-content)
- [Gemini API Key](https://ai.google.dev/gemini-api/docs/api-key)
- [DeepSeek 模型列表](https://api-docs.deepseek.com/api/list-models)
- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion)
- [百炼模型列表与地域](https://www.alibabacloud.com/help/en/model-studio/list-models)
- [百炼 OpenAI 兼容协议与兼容域名](https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope)
