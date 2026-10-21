# Androdex 架构与安全模型

## 数据流

```text
ChatGPT 自定义 MCP 连接器
  → OpenAI Secure MCP Tunnel
  → Android 前台服务中的 OkHttp 长轮询客户端
  → JSON-RPC / MCP 请求处理器
  → 只读工具注册表与参数校验
  → FileAccessManager + PermissionManager
  → SAF ContentResolver 或已白名单化的共享存储路径
  → 有界文本/文档解析
  → Tunnel 响应关联与返回
```

Androdex 只建立到官方 Tunnel 控制平面的出站 TLS 请求。设备不启动供互联网访问的 HTTP 服务。每条 Tunnel 命令的 `request_id`、`shard_token`、channel、请求头和响应期限都按 Tunnel 协议配对；MCP JSON-RPC 请求交给进程内 `McpRequestHandler`，响应通过 Tunnel response endpoint 返回。

## 代码分层

- `ui`：Compose 页面；只呈现 ViewModel 状态并提交用户操作。
- `viewmodel`：页面状态、连接诊断、目录授权和预览操作。
- `data`：设置仓库、Android Keystore 加密凭据、诊断日志、应用依赖图。
- `files`：SAF/普通路径访问、授权检查、目录遍历和 PDF/Office 文本解析。
- `mcp`：10 个只读工具的 JSON Schema、MCP 初始化/发现/列表/调用、认证和错误响应。
- `tunnel`：OpenAI Tunnel wire protocol、轮询、命令处理、限流并发、超时和重连。
- `service`：用户发起的 Android 前台服务及停止通知动作。
- `core`：可在 JVM 测试的路径、编码、权限策略、敏感信息和 Tunnel 规则。

## MCP 工具

`list_roots`、`list_directory`、`stat_file`、`search_files`、`search_file_contents`、`read_text`、`read_document`、`read_file_chunk`、`get_connection_status`、`get_capabilities`。

现代 stateless MCP discovery 与 2025 系列 legacy `initialize` 握手均有处理。工具参数按 JSON Schema 的类型、必填项、范围和额外字段校验。工具清单标记 `readOnlyHint=true`、`destructiveHint=false`。未知方法、参数、认证失败、权限撤销、文件不存在和格式不支持会返回结构化错误。

## 资源和内容边界

- 默认单文件上限 5 MiB，设置范围 1–16 MiB；单次 MCP 文本输出上限默认 64 Ki 字符，最高 256 Ki。
- 目录遍历、正文搜索、文档展开和解析都有独立上限；纯文本读取按行/分块流式读取。
- PDF 读取需将受限大小的文件载入 PDFBox；DOCX/XLSX/PPTX 只读取必要的 XML 部件，并限制压缩包展开量。
- 图片只返回名字、大小和时间元数据，不返回二进制、Base64、OCR 或视觉结果。
- 对用户文件返回的文本是不可信内容；它不扩大权限，也不应被当作新的文件授权指令。

## 权限与密钥

SAF 授权记录系统 URI 的持久只读权限。可选普通路径只在 Android 11+、用户授予 All files access 后使用，而且只接受 Androdex 中另行加入的目录根。所有工具解析路径时拒绝绝对路径、`..`、反斜杠、百分号编码和 `Android/data`、`Android/obb`；访问发生时重新查验系统授权和白名单。见 [ANDROID_PERMISSIONS.md](ANDROID_PERMISSIONS.md)。

Runtime API Key 与可选 MCP Bearer 使用 Android Keystore 中不可导出的 AES 密钥作 AES/GCM 加密。明文不进入设置页面回显、日志或调试输出。MCP Bearer 默认关闭。Tunnel 请求和响应正文会经过 OpenAI Secure MCP Tunnel，因此文件内容在 ChatGPT 工具调用时会经 OpenAI 服务传输；请仅授权愿意用于该用途的目录。

## 后台服务

用户点击启动后，Androdex 以 `specialUse` 前台服务显示持续通知，并由 NetworkCallback 与指数退避处理网络暂时中断。手动停止会取消活动轮询与请求，不会自动重启。服务受 Android 系统调度、电池管理和厂商策略影响，不能保证永不被终止。Android 14+ 的 `specialUse` 需要平台审核；Google Play 分发还需按商店政策审查服务用途和 `MANAGE_EXTERNAL_STORAGE` 权限。

## 明确不包含

Root、AccessibilityService、shell/本地代码执行、写入或删除文件、远程 APK 安装、ChatGPT 登录/输入框自动化、图片同步、Codex 连接和模型推理 API。Android 无法可靠检查 ChatGPT 账号的连接器注册状态。
