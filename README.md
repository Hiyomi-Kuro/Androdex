# Androdex

Androdex 是一个 Android 只读文件 MCP 客户端。它由用户手动启动，通过 OpenAI Secure MCP Tunnel 的出站长轮询接收 MCP 请求；只有在工具被调用时才读取已授权目录中的所需文件。应用没有本地开放的网络监听端口，不连接 Codex，不控制 ChatGPT 界面，也不读取或上传图片正文。

## 功能

- Kotlin、Jetpack Compose、Material 3、MVVM 与 Repository。
- Android 10（API 29）及以上；以 API 36 编译并以 API 36 为 target。
- SAF 多目录持久授权；Android 11 及以上可选共享存储路径白名单。
- 每次文件访问时重新检查目录开关、系统权限、路径范围和文件可读状态。
- 10 个只读 MCP 工具，包含目录、元数据、文件名/正文搜索、文本分页、Office/PDF 文本提取和字节分块。
- OpenAI 官方 Tunnel 控制平面协议、TLS、响应关联、超时、断线退避重连和网络切换恢复。
- Android Keystore AES/GCM 凭据保护、日志脱敏、输出与资源上限。
- 中文首页、目录权限、文件预览、日志和设置页面。

当前产品注册状态不能由 Android Tunnel 接口可靠查询。APK 构建成功不代表 ChatGPT 端已注册或已完成端到端连接。ChatGPT 网页端设置与真实工具调用需按 [SETUP_CHATGPT.md](SETUP_CHATGPT.md) 单独验证。

## 构建

本项目使用 Gradle Wrapper，构建要求 JDK 17、Android SDK Platform 36 和 Android Build Tools 36：

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。更多测试说明见 [TESTING.md](TESTING.md)。

## 文档

- [架构与安全模型](ARCHITECTURE.md)
- [ChatGPT / Tunnel 配置](SETUP_CHATGPT.md)
- [Android 存储权限](ANDROID_PERMISSIONS.md)
- [自动化与实机测试](TESTING.md)

## 协议参考

- [OpenAI Tunnel Client 协议](https://github.com/openai/tunnel-client/blob/master/docs/protocol.md)
- [OpenAI Tunnel Client OpenAPI](https://github.com/openai/tunnel-client/blob/master/docs/openapi.json)
- [OpenAI Tunnel Client onboarding](https://github.com/openai/tunnel-client/blob/master/docs/onboarding.md)
- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
