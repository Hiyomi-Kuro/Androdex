# Android 存储与前台服务权限

Androdex 最低 API 29（Android 10），以 API 36 编译并设为 target API 36。存储权限遵循系统的分区存储和用户选择，不使用 Root、无障碍服务或隐藏 API。

## SAF：默认推荐

在“目录”页点“使用系统文件夹选择器”。用户在 Storage Access Framework 中选择目录后，应用保存系统授予的持久只读 URI 权限。可添加多个根，并独立暂停或删除。Android 10–14 均可使用。

SAF 的系统/文档提供程序可能限制某些位置。Android 11 及以上通常不能通过该选择器选择内部存储根目录、Downloads 根、`Android/data` 或 `Android/obb`；提供程序行为也可能因设备厂商不同。请改选允许的子目录。Androdex 对 `Android/data` 与 `Android/obb` 一律报告受限。

## 可选：普通共享存储路径

Android 11（API 30）及以上可在用户明确授予 MANAGE_EXTERNAL_STORAGE/“所有文件访问”后，把共享存储内的特定目录添加为普通路径白名单。授予全盘权限不会自动向 MCP 开放全盘：工具仍只允许访问 Androdex 白名单中的根。

Android 10 不启用普通路径方式，请用 SAF。不能读取其他应用私有目录、应用沙箱目录或被系统阻止的位置。失去权限、目录被移走、provider 失效或文件不可读时，页面显示当前错误，文件访问逻辑会重新检查并拒绝请求。

Android 11+ 的 SAF 目录选择会受系统限制；下载目录可以通过具体文档提供程序授予其可选子目录，但不能假设每个设备均一致。用户应在授权页通过“测试目录 / 文件读取”确认。

## 用户控制

- 每个授权根有单独开关；关闭后所有 MCP 工具均拒绝该根。
- 删除授权会移除 Androdex 根记录并尽力释放 SAF 持久权限。
- 每次访问都检查 enabled 状态、当前系统权限、根是否有效、路径是否在根内和文件是否可读。
- 图片只显示目录元数据，不读取或传输图片内容。
- SAF 普通文本支持 UTF-8、UTF-8 BOM、UTF-16 BOM 和合理的 GB18030 检测。

## 前台服务

用户手动启动 Tunnel 后，Android 显示持续通知；通知动作可停止服务。Android 13+ 需要通知授权，否则应用拒绝启动以免用户无法看见正在运行的服务。Manifest 使用 `specialUse` 前台服务类型及其 Android 14 权限说明，因为该服务执行持续出站 MCP 隧道且不能映射为 `dataSync`（Android 15 起有时间配额）。系统和厂商仍可能停止服务，Androdex 不承诺后台永续。

`specialUse` 类型以及 MANAGE_EXTERNAL_STORAGE 在 Google Play 分发时需满足审核/资格要求。本项目的 Debug APK 可供用户本机安装验证；应用不会因调低 target API 来规避平台权限规则。
