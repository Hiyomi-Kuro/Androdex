package com.kaori.androdex.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaori.androdex.viewmodel.MainUiState
import com.kaori.androdex.viewmodel.MainViewModel
import com.kaori.androdex.data.ThemeMode

@Composable
fun SettingsScreen(state: MainUiState, contentPadding: PaddingValues, viewModel: MainViewModel) {
    var settings by remember(state.settings) { mutableStateOf(state.settings) }
    var apiKey by remember { mutableStateOf("") }
    var mcpBearer by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var showBearer by remember { mutableStateOf(false) }
    var setupDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tunnel 配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = settings.pluginName,
                    onValueChange = { settings = settings.copy(pluginName = it.take(48)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("插件名称") }, singleLine = true,
                    supportingText = { Text("仅修改 Android 本地名称；不会自动更改 ChatGPT 已注册的连接器。") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(settings.pluginName)); viewModel.showMessage("插件名称已复制。") }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null); Text("复制名称", Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { setupDialog = true }) { Text("复制 ChatGPT 配置说明") }
                }
                OutlinedTextField(
                    value = settings.tunnelId,
                    onValueChange = { settings = settings.copy(tunnelId = it.trim()) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("OpenAI Platform Tunnel ID") }, singleLine = true,
                    placeholder = { Text("tunnel_…") },
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Runtime API Key") },
                    placeholder = { Text(if (state.apiKeyConfigured) "已加密保存；输入新值以替换" else "输入仅具备 Tunnel 权限的运行时密钥") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showApiKey = !showApiKey }) { Icon(if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (showApiKey) "隐藏密钥" else "显示密钥") } },
                    supportingText = { Text("只用于官方 Secure MCP Tunnel 认证。密钥通过 Android Keystore 加密保存，不写入日志。") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (apiKey.isNotBlank()) { viewModel.saveRuntimeApiKey(apiKey); apiKey = "" } else viewModel.showMessage("请输入新的 Runtime API Key。") }) { Text("保存密钥") }
                    OutlinedButton(onClick = { viewModel.clearRuntimeApiKey(); apiKey = "" }, enabled = state.apiKeyConfigured) { Text("清除密钥") }
                }
                OutlinedTextField(
                    value = mcpBearer,
                    onValueChange = { mcpBearer = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("可选 MCP Bearer 认证") }, singleLine = true,
                    placeholder = { Text(if (state.mcpBearerConfigured) "已加密保存；输入新值以替换" else "留空表示关闭额外认证") },
                    visualTransformation = if (showBearer) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showBearer = !showBearer }) { Icon(if (showBearer) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "切换可见性") } },
                    supportingText = { Text("如果启用，ChatGPT 连接器也必须配置相同 Bearer 值。") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (mcpBearer.isNotBlank()) { viewModel.saveMcpBearer(mcpBearer); mcpBearer = "" } else viewModel.showMessage("请输入要保存的 MCP Bearer 值。") }) { Text("保存认证") }
                    OutlinedButton(onClick = { viewModel.saveMcpBearer(""); mcpBearer = "" }, enabled = state.mcpBearerConfigured) { Text("关闭并清除") }
                }
                Button(onClick = { viewModel.saveSettings(settings) }, modifier = Modifier.fillMaxWidth()) { Text("保存配置") }
                OutlinedButton(onClick = viewModel::testConfiguration, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "正在测试…" else "测试配置 / Tunnel / MCP") }
            }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("服务与资源限制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("服务由你手动启动，运行时显示持续通知；点击通知中的停止操作即可断开。不提供开机自动启动或隐式保活。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("断线后自动重连", fontWeight = FontWeight.Medium)
                        Text("采用退避等待；可在此关闭。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.autoReconnect, onCheckedChange = { settings = settings.copy(autoReconnect = it) })
                }
                NumberField("网络请求超时（秒）", settings.networkTimeoutSeconds.toString(), 10, 120) { settings = settings.copy(networkTimeoutSeconds = it) }
                NumberField("单文件累计读取字符上限", settings.maxReadChars.toString(), 100_000, 20_000_000) { settings = settings.copy(maxReadChars = it) }
                NumberField("单次工具输出字符上限", settings.maxOutputChars.toString(), 4_096, 262_144) { settings = settings.copy(maxOutputChars = it) }
                Text("大文件支持按字符偏移连续分块读取。单次工具输出上限只限制一页响应，不限制整份文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("详细调试日志", fontWeight = FontWeight.Medium)
                        Text("日志记录诊断事件，不记录文件正文和凭据。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.debugLogging, onCheckedChange = { settings = settings.copy(debugLogging = it) })
                }
            }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("主题模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                        )
                        Text(
                            text = mode.title(),
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setThemeMode(mode) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("后台运行与电池优化", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("前台服务：${if (state.serviceActive) "运行中" else "已停止"}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "电池优化：${if (state.batteryOptimizationAllowed) "已允许后台运行" else "受电池优化限制"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.batteryOptimizationAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text("通知权限：${if (state.notificationsAllowed) "已允许" else "未允许"}", style = MaterialTheme.typography.bodyMedium)
                Text("电池优化豁免不能保证 Android 系统永不停止应用。部分厂商另有自启动、后台高耗电或电池无限制选项，可在系统设置 → 应用 → Androdex → 电池/后台管理中手动开启。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = viewModel::requestBatteryOptimizationExemption,
                    enabled = !state.batteryOptimizationAllowed,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.batteryOptimizationAllowed) "已允许后台高耗电运行" else "允许后台高耗电运行") }
                OutlinedButton(onClick = viewModel::openBatteryOptimizationSettings, modifier = Modifier.fillMaxWidth()) { Text("打开系统电池优化设置") }
                OutlinedButton(onClick = viewModel::openAppBatterySettings, modifier = Modifier.fillMaxWidth()) { Text("打开应用后台电池设置") }
                OutlinedButton(onClick = viewModel::openAppNotificationSettings, modifier = Modifier.fillMaxWidth()) { Text("打开应用通知设置") }
            }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ChatGPT 端状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Android 无法通过 Tunnel API 可靠确认连接器注册。当前 OpenAI 帮助说明自定义 MCP 连接器仅支持 ChatGPT 网页端，手机应用不能连接自定义 MCP 服务器；账号计划与工作区策略也会影响权限。请按 SETUP_CHATGPT.md 在网页端完成注册并实测。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (setupDialog) AlertDialog(
        onDismissRequest = { setupDialog = false },
        title = { Text("ChatGPT 连接器配置说明") },
        text = { Text("1. 在 OpenAI Platform 创建 Secure MCP Tunnel，并复制 Tunnel ID。\n2. 创建仅具备 Tunnel Read 与 Use 权限的 Runtime API Key。\n3. 在 Androdex 保存 Tunnel ID 和密钥，授权目录并启动服务。\n4. 当前需在支持自定义 MCP 的 ChatGPT 网页端添加连接器并选择 Tunnel。OpenAI 帮助中心目前说明手机应用不支持自定义 MCP 服务器。\n5. 在新网页对话中手动选择 @${settings.pluginName}，请求读取一个已授权目录中的测试文件。\n\n账号计划与工作区权限会影响连接器创建。Android 端不能绕过注册或强制启用。详见项目根目录 SETUP_CHATGPT.md。", style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString("OpenAI Platform Tunnel ID: ${settings.tunnelId}\nAndrodex 插件名称: ${settings.pluginName}\n请在 ChatGPT 网页设置中注册自定义 MCP 连接器；账号及客户端支持情况需在 ChatGPT 中验证。")); setupDialog = false; viewModel.showMessage("配置摘要已复制；请勿将 Runtime API Key 发送到聊天中。") }) { Text("复制摘要") } },
        dismissButton = { TextButton(onClick = { setupDialog = false }) { Text("关闭") } },
    )
}

@Composable
private fun NumberField(label: String, value: String, minimum: Int, maximum: Int, onValue: (Int) -> Unit) {
    var text by remember(label) { mutableStateOf(value) }
    androidx.compose.runtime.LaunchedEffect(value) {
        if (text.toIntOrNull() != value.toIntOrNull()) text = value
    }
    val parsed = text.toIntOrNull()
    val invalid = parsed == null || parsed !in minimum..maximum
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.take(10)
            text.toIntOrNull()?.takeIf { it in minimum..maximum }?.let(onValue)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            Text(
                if (invalid) "请输入 $minimum–$maximum 之间的正整数" else "范围：$minimum–$maximum",
                color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private fun ThemeMode.title(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色模式"
    ThemeMode.DARK -> "深色模式"
}
