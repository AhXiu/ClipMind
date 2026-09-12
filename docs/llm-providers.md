# LLM 服务商与模型选择

## Android 配置

设置 > AI 服务提供两个联动下拉框：先选服务商，再选模型。模型下拉支持搜索及自定义模型 ID。输入由当前平台签发的 API Key 后，确认数据发送范围与 API 费用，再保存。Key 不需要 `Bearer ` 前缀。

| 服务商 | 固定 API Base URL | 内置模型示例 | 凭证来源 |
| --- | --- | --- | --- |
| Kimi 官方 | `https://api.moonshot.cn/v1` | `kimi-k3`、`kimi-k2.6` | Kimi API 开放平台 |
| 智谱 GLM 官方 | `https://open.bigmodel.cn/api/paas/v4` | `glm-5.3`、`glm-5.2` | 智谱通用 API 平台 |
| OpenAI GPT 官方 | `https://api.openai.com/v1` | `gpt-4.1-mini`、`gpt-5-mini`、`gpt-6-astra` | OpenAI API 平台 |
| OpenRouter | `https://openrouter.ai/api/v1` | GPT、Claude、Gemini、Kimi、GLM、DeepSeek、Qwen 等 | OpenRouter |
| 火山方舟 Ark | `https://ark.cn-beijing.volces.com/api/v3` | 填写属于自己账号的 `ep-...` 部署 ID | 火山方舟 |
| 后端托管 | 使用现有 ClipMind 后端地址 | 由后端部署配置决定 | 无需在 Android 保存模型 Key |

OpenRouter 模型目录是 2026-09-12 从官方公开目录提取的离线快照，共 209 个条目：筛选主流厂商、纯文本输出、支持 `response_format` 的模型，排除批处理等带冒号变体。打开或搜索下拉框不联网、不发送 Key。目录不等同于实时可用性承诺，不包含图像生成、语音等不适用本应用的模型；可用性和权限以各平台为准。新型号可填写自定义 ID，后续更新目录需要再次核对官方兼容性。

Claude、Gemini、DeepSeek、Qwen 等本轮通过 OpenRouter 使用，尚未提供各自官方 Key 的直连接口。不要将其他厂商 Key 填到 OpenRouter 入口。模型名必须使用当前平台提供的准确 ID，官方直连 ID 与 OpenRouter 的 `厂商/模型` ID 不通用。

聊天会员、Kimi Code、GLM Coding Plan 等订阅不自动转换为通用 API 额度。本轮使用通用 API，不模拟登录，不读取 Cookie，不使用订阅专属编程接口。

## 范围与安全

- 该选择用于采集摘录的卡片分析、多卡归纳、语义召回后的关系判断。完整阅读分析、搜索、向量生成、荐书、周报仍使用后端原有配置，设置页明确说明此边界。
- Key 按服务商分开保存在 Android Keystore 保护的密文中，不回显，不写入日志、Room 任务、导出文件或 ClipMind 后端。应用不接收自定义 Base URL。
- 所有模型请求只使用固定 HTTPS 接口，禁止重定向，不进行 HTTP 客户端自动重试，不会自动切换模型或服务商。OpenRouter 自身仍可能在该模型的托管供应商间路由，受 OpenRouter 的服务条款约束。
- 原有任务记录保留创建时的服务商和模型；切换下拉框只影响后续任务。Worker 根据任务中的服务商取 Key，而不是根据当前界面取 Key。清除某服务商的 Key 不影响其他服务商。
- 旧版通用 Key 缺少可信服务商归属，因此保留密文但停止自动使用。用户需明确将其迁移至 Ark 或 OpenRouter，或重新输入。迁移不能覆盖已有 Key。因缺 Key 失败的旧任务需要用户手动重试。
- 保存 Key 和切换服务商需要确认。只有点击测试并再次确认，才发送固定测试文本进行一次可能计费的调用；不读取用户剪贴板或卡片。取消已发送请求不能保证撤销平台计费。
- 常规采集 Worker 保留原有持久化重试机制：网络失败、限流等会延迟重试，已被平台接受但未返回的请求可能重复计费。需要严格控制费用时关闭自动提交，仅手动触发；不能将 HTTP 层不重试理解为任务永不重试。
- 新增服务商调用设置最多 16384 个输出/推理 token；Kimi K3 使用低推理强度并省略不兼容的 `temperature=0`。其他新增服务商使用默认采样参数；达到 token 上限而截断的结果不作为成功结果保存。不同模型仍可能不兼容或需要更多预算，失败不会自动扩大预算。
- 输入维持本地安全过滤；输出仍需 JSON、分类、长度和引用校验。模型失败不丢弃本地原文。BYOK 分析成功后的原文与分析结果仍按原有同步流程发送到 ClipMind 后端，因此 BYOK 不等于全文不出后端。

## 验证与渐进启用

1. 本地运行 JVM 测试、后端测试和 Debug 构建。网络单测用拦截器或 localhost 返回虚构响应，不使用实际服务商 Key。
2. 在 BOE 更新后端以接受 `kimi`、`glm`、`openai` 的客户端结果，再安装新版 Android；旧后端会拒绝新 provider，不可直接将客户端单独投用。
3. 经人工授权，用专用低额度测试账号逐家验收：授权与取消、余额不足、限流、模型下线、结构化输出、密钥切换、队列快照、离线缓存与重新生成。真实调用会发送数据并产生费用，必须另行确认。
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
