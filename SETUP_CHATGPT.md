# 配置 OpenAI Tunnel 与 ChatGPT

## 1. 创建 Secure MCP Tunnel

1. 在浏览器登录具有相应组织/工作区权限的 OpenAI Platform。
2. 打开 [Platform Tunnels](https://platform.openai.com/settings/organization/tunnels)，创建 Secure MCP Tunnel，并复制页面给出的 Tunnel ID。Tunnel ID 不是 API Key，也不是 ChatGPT 连接器名称。
3. 如果组织页面没有 Tunnel 功能，请先检查组织权限、账号可用性和平台当前开放范围；Androdex 不会通过自建 HTTP 接口替代官方 Tunnel。

## 2. 创建 Runtime API Key

1. 在 [Platform API keys](https://platform.openai.com/settings/organization/api-keys) 按 Tunnel onboarding 流程创建 Runtime API Key。
2. 只授予该隧道所需的 **Tunnels Read** 与 **Tunnels Use** 权限。不要使用组织管理员密钥或普通模型 API key 代替。
3. 复制密钥并妥善保管。它只在 Androdex 中用于 Secure MCP Tunnel 身份验证，不会调用模型。

Tunnel UI 与权限名称可能随 Platform 更新；若页面步骤不同，以 OpenAI Platform 页面和 [官方 onboarding](https://github.com/openai/tunnel-client/blob/master/docs/onboarding.md) 为准。

## 3. 在 Androdex 配置并启动

1. 安装 APK，进入“设置”。
2. 输入 Tunnel ID 和 Runtime API Key，保存配置与密钥；可选 MCP Bearer 默认关闭。若启用，需在 ChatGPT 连接器中配置同一 Bearer 值。
3. 在“目录”页通过 Android 系统文件夹选择器授权一个尽量小的测试文件夹。SAF 权限会持久保存。需要普通路径时，先查看 [Android 权限说明](ANDROID_PERMISSIONS.md)。
4. 在目录卡片中点击“测试目录 / 文件读取”，可填写目录内相对测试文件路径。
5. 首页点击“测试连接”。诊断会报告 Tunnel ID 格式、Tunnel 认证/网络、本地 MCP initialize/tools/list、目录检查；ChatGPT 注册会明确显示为需要网页端确认。
6. 首页点击“启动服务”，允许通知。持续通知说明服务当前由 Android 前台运行；通知中的停止操作会断开服务。

## 4. 在 ChatGPT 注册连接器

1. 使用符合资格的 ChatGPT 网页版账号/工作区，打开 [ChatGPT Plugins 设置](https://chatgpt.com/#settings/Connectors)。当前界面流程不要求先开启 Developer Mode。
2. 选择加号 → **Add custom MCP server**，输入连接器名称和可选说明。
3. 在 Connection 区选择 **Tunnel**，选择可用 Secure MCP Tunnel 或输入此 Tunnel ID；按界面配置认证。Runtime API Key 是 Android Tunnel runtime 的凭据，不要粘贴到聊天里；若额外启用 Androdex MCP Bearer，ChatGPT 端必须按界面可用方式配置相同 Bearer。
4. 阅读并确认风险提示，选择 **Create as a plugin**，检查发现的工具和元数据，确认清单都是只读工具。Android 设置中的插件名称只是本地 MCP server 名称，不会自动改动 ChatGPT 已注册连接器的名字。
5. 连接器创建并可用后，在新对话中输入 `@` 并选择 Androdex，手动发送读取指令。工具选择只作用于当前消息；后续需要再次读取时再选择连接器。Androdex 不会代替你发送 ChatGPT 消息，也不控制 ChatGPT 输入框。

按当前 OpenAI 帮助中心说明，自定义 MCP 连接器目前在 ChatGPT 网页端提供，**ChatGPT 手机应用不能连接自定义 MCP 服务器**。帮助中心列出的完整 MCP 支持面向 Business、Enterprise 和 Edu；Pro 用户可连接 read/fetch 权限的自定义服务器，其他账号/工作区能力取决于当时的账号资格及管理员策略。若网页 Plugins 区域没有“Add custom MCP server”，请联系工作区管理员或确认账号资格；Android APK 不能绕过此限制。

注册时 ChatGPT 会扫描并展示 MCP 工具。应确认只读工具清单后再创建。自定义工具定义更新后，工作区可能继续使用既有工具快照，需由有权限的管理员刷新工具。

不要把“Tunnel 元数据请求成功”解释成 ChatGPT 已注册。Android 应用负责在手机上运行文件服务；当前 ChatGPT 端到端验证需通过支持的 ChatGPT 网页端完成。

## 5. 端到端验证

1. 在刚授权的目录中放入无敏感内容的小型 `hello.txt`，例如 `Androdex test: 2026`。
2. 保持 Android 服务运行，在 ChatGPT 新对话中手动选择 `@Androdex` 并请求读取 `hello.txt`。先让工具列出 `list_roots`，再使用它返回的 `root_id` 和相对路径调用 `read_text`。
3. 查看 Android 首页服务/Tunnel 状态与“日志”页面的请求、成功读取或错误事件。日志不会显示文件正文或密钥；工具结果应由 ChatGPT 返回正文中的特定测试短语，才能证明实际读取链路。
4. 点击 Androdex 持续通知中的停止，确认 ChatGPT 后续文件调用失败；重新启动并撤销目录权限，再验证 `list_roots` 报告不可用且读取被拒绝。

以上端到端流程依赖实际 ChatGPT 账号、Platform Tunnel、Android 设备和网络。Androdex 的 Windows 构建/单元测试不等于这项端到端验证。
