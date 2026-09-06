# ClipMind Android（Phase 1）

这是单模块 Kotlin/Jetpack Compose Android 客户端。它通过 Shizuku UserService 执行固定的剪贴板读取操作，在本地过滤、去重并写入 Room outbox，再由 WorkManager 批量上报。

## 环境与构建

- JDK 17
- Android SDK Platform 35、Build Tools 35.x
- minSdk 26，targetSdk/compileSdk 35

```bash
# `clipmind.baseUrl` 是构建时的必传/覆盖参数，URL 必须以 / 结尾；未提供时构建脚本回退到不可用的 https://example.invalid/
# 本仓库当前 gradle.properties 已为 BOE Debug 联调提供默认值：http://10.37.228.188:8080/
# 正式 token 必须在应用 UI 中保存；不要通过 Gradle/BuildConfig 注入
./gradlew testDebugUnitTest
./gradlew assembleDebug

# 如需临时覆盖后端地址，URL 必须以 / 结尾
./gradlew assembleDebug -Pclipmind.baseUrl=https://api.example.com/

# 仅本地 debug 开发可选；该值会进入 debug APK，严禁用于生产凭证
./gradlew assembleDebug -Pclipmind.debugAuthToken=temporary-dev-token
```

Debug Manifest 仅为当前 BOE 联调开放明文 HTTP；Release 构建不会继承该设置。

> **安全警告：`clipmind.debugAuthToken` 只能用于临时本地调试，绝不能填写生产 Token。Release 构建中的该默认值强制为空。** 正式 Token 应在应用 UI 中保存，随后使用独立 Android Keystore 密钥加密并存入 SharedPreferences；界面不回显 Token。

完整 Gradle Wrapper（含 `gradle-wrapper.jar`）已提交。Windows 可使用 `gradlew.bat`。

## Shizuku 前置

1. 安装 Shizuku；在已 root 设备或通过无线调试启动 Shizuku。
2. 打开 ClipMind。若显示 `PERMISSION_REQUIRED`，点击“申请权限”并在 Shizuku 中允许。
3. 可先点击“启动采集”保留用户意图；`ACTIVE` 后才会启动唯一轮询任务。
4. Binder 断开会进入 `DEAD` 并执行有上限的指数退避重连。前台服务保持运行、通知显示等待状态，轮询暂停；只有用户点击“停止采集”才清除采集意图并停止服务。

状态含义：

- `UNAVAILABLE`：Shizuku Binder 不可达。
- `BINDER_READY`：Binder 和授权已就绪，正在连接 UserService。
- `PERMISSION_REQUIRED`：需要用户授权。
- `ACTIVE`：UserService 已连接。
- `DEAD`：Binder/UserService 已断开。

## 行为与安全边界

- UserService 的 AIDL **仅提供 `configureUserId(int)` 与 `readPrimaryClip()`**，不接受、更不会执行任意 shell 命令。客户端绑定后在普通应用进程中计算与 `UserHandle.myUserId()` 等价的当前 profile userId 并配置给 UserService；未配置时读取返回 `USER_ID_NOT_CONFIGURED`。UserService 绝不从自身 shell/root UID 猜测 userId。
- UserService 反射获取系统 clipboard Binder，并探测 `getPrimaryClip` 签名。仅为已知的 `String`、`int`、`boolean` 参数构造值；出现未知对象参数不会猜测，而会返回 `SIGNATURE_NOT_FOUND`、`ALL_SIGNATURES_FAILED` 等结构化错误。
- 只读取 `ClipData.Item.text`，不解析 URI/Intent，不把原始文本写入日志。
- 系统 clipboard Binder 是隐藏且随 Android/OEM 变化的接口，无法保证所有系统版本可用。失败会显式降级，不会伪装成功；UI 仍可展示服务状态，但不会采集。
- clipboard 接口不可靠地暴露来源应用，因此 Phase 1 的自动采集 `sourceApp/sourceUrl` 为 `null`；字段保留在 outbox/API 模型供可信来源后续填充。
- 本地过滤包括：最小长度、纯 URL、手机号、身份证、验证码、token/密钥、私钥块与来源黑名单。被过滤内容不进入数据库。
- 自动模式成功写入 `READY` 后会立即调度上传；确认模式写入 `PENDING_CONFIRMATION`，在 UI 确认并成功更新为 `READY` 后也会立即调度上传。15 分钟周期任务仅作为兜底。丢弃不会触发上传。
- `/v1/captures:batch` 使用 snake_case DTO、RFC3339 `captured_at`、`Authorization` 与基于有序 client ID 集合 SHA-256 生成的稳定 `Idempotency-Key`。响应中的 `accepted[].client_capture_id` 才会转为 `SUCCEEDED`；`filtered_reject` 转为终态 `REJECTED`，其他拒绝或未明确确认的条目进入 `RETRYABLE_ERROR` 并指数退避。
- Room 仅持久化 `encryptedRawText`：使用 Android Keystore 内不可导出的 AES-256 密钥和 `AES/GCM/NoPadding`，每条记录生成随机 12-byte IV；密文封装版本、IV、ciphertext 与独立 tag。日志中不记录剪贴板原文。
- 当前 Phase 1 工程从未发布，因此加密后的最终 schema 直接定义为数据库 version 1，并移除了 `fallbackToDestructiveMigration`；不存在已发布明文 schema 的静默破坏性升级路径。后续版本必须提供显式 Migration。
- 上传与 UI 展示只在内存中短暂解密。认证失败、密文损坏或 Keystore 密钥失效时绝不上传密文/垃圾数据，记录结构化错误并转为可见终态 `DECRYPTION_FAILED`；UI 显示“内容无法解密”。
- Keystore 密钥通常随应用数据生命周期存在。系统安全状态变化可能使密钥失效；清除应用数据或卸载会同时删除密钥和本地数据库。若数据库被单独恢复但密钥不存在，历史密文无法恢复，只能丢弃相应记录或清除应用数据，客户端不会生成替代固定密钥尝试解密。

## 权限

Manifest 仅声明联网、通知和前台服务（含 Android 14 `specialUse` 类型）所需权限。项目没有声明或实现 AccessibilityService。

## 测试

JVM 单元测试覆盖：

- 本地安全过滤规则及 reason；
- Shizuku 状态机、有限指数退避重连与 Work Profile userId 配置门限；
- 前台服务启动竞态、等待状态与唯一轮询策略；
- Token Header 规范化；
- 文本规范化、SHA-256 与时间窗去重；
- outbox 合法/非法状态转换。
