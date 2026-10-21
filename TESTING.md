# 自动化测试与设备验证

## Windows JVM 单元测试

需要 JDK 17、Gradle Wrapper 和 Android SDK Platform 36。运行：

```powershell
./gradlew.bat :app:testDebugUnitTest
```

测试覆盖：

- 相对路径标准化、路径穿越拒绝、规范路径根边界。
- 目录开关、权限撤销和不存在文件时拒绝访问的共享策略。
- UTF-8、BOM、GB18030 中文读取、分页、分块及读取边界。
- Tunnel ID / 响应期限解析和瞬时/永久 Tunnel 错误分类、网络中断重试策略。
- MCP 工具列表、JSON 参数校验、tools/call 分发、Bearer 认证、现代 stateless discovery 与日志凭据排除。
- 已注册凭据的日志脱敏。

需要真实 Android `ContentResolver`、Keystore 或系统 SAF 的情况不在普通 JVM 单元测试中伪装通过；下方列为实机验证。

本次 Windows 验证结果：`:app:testDebugUnitTest` 通过，3 个测试套件共 14 项、0 失败；`:app:assembleDebug` 成功。APK 包名 `com.kaori.androdex`、minSdk 29、targetSdk/compileSdk 36、显示名称 `Androdex`。未连接 Android 真机或模拟器；MCP/Tunnel/ChatGPT 网页端端到端流程未实测。

## APK 构建检查

```powershell
./gradlew.bat :app:assembleDebug
```

产物应为 `app/build/outputs/apk/debug/app-debug.apk`。构建后可用 Android SDK `apkanalyzer` 或 `aapt dump badging` 检查 applicationId、version、minSdk、targetSdk、应用名与权限。

## Android 14 实机验收清单

1. 安装 Debug APK，确认应用名 Androdex、包名 `com.kaori.androdex`、minSdk 29。
2. 首次运行不授予 All files access，通过 SAF 添加一个小型测试目录；检查重启应用后目录授权仍存在。
3. 测试目录开关、删除目录、撤销 SAF 授权、选择受限目录、缺失文件、超大文件和路径穿越输入；确认 UI 与工具错误明确拒绝。
4. 配置测试 Tunnel ID/Runtime API Key，执行连接诊断；在 Android 14 启动服务、检查持续通知、点击通知停止，确认轮询中止。
5. 测试 Wi-Fi/蜂窝网络切换、短时断网、恢复网络和手动关闭自动重连；检查状态实际反映断线。
6. 使用 ChatGPT 网页版中可用的自定义 MCP 连接器，按 [SETUP_CHATGPT.md](SETUP_CHATGPT.md) 手动选择 `@Androdex`，验证一个已授权测试文件内容。单独记录账号/工作区和 ChatGPT 客户端是否支持。
7. 检查 Android 日志与 Androdex 运行日志，不得出现 Runtime API Key、MCP Bearer 或完整文件正文。

连接器注册、OpenAI Tunnel 端到端行为、厂商文件提供程序、Android 14 通知/前台服务以及 Android 14 真机运行需由实际设备和账户验证。Windows 单元测试与 APK 编译成功不表示这些已验收。
