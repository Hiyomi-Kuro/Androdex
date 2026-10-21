package com.kaori.androdex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaori.androdex.data.TunnelConnectionState
import com.kaori.androdex.viewmodel.MainUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("运行状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusLine("插件名称", state.settings.pluginName)
                    StatusLine("Android", "${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）")
                    StatusLine(
                        "文件访问权限",
                        if (state.roots.any { it.accessible }) "${state.roots.count { it.accessible }} 个目录可读" else "尚无可用授权目录",
                    )
                    StatusLine("所有文件访问", if (state.allFilesAccess) "已授权（仍受目录白名单限制）" else "未授权；SAF 目录仍可用")
                    StatusLine("MCP 服务", if (state.serviceActive) "前台服务运行中" else "已停止")
                    StatusLine("Tunnel 连接", stateLabel(state.serviceState))
                    StatusLine("最近工具调用", state.lastToolCall?.let(::formatTime) ?: "暂无")
                    StatusLine("已读取文件", state.statsFilesRead.toString())
                    if (!state.lastError.isNullOrBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Column {
                                Text("最近错误", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                                Text(state.lastError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (!state.settings.tunnelId.isBlank()) StatusLine("Tunnel ID", state.settings.tunnelId)
                    if (!state.apiKeyConfigured) Text("请在设置中填写 Runtime API Key 并测试配置。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!state.serviceActive || state.serviceState == TunnelConnectionState.AUTH_ERROR || state.serviceState == TunnelConnectionState.DISCONNECTED) {
                        Button(onClick = onStart, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text(if (state.serviceActive) "重新连接" else "启动服务", modifier = Modifier.padding(start = 6.dp))
                        }
                    } else {
                        Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Text("停止服务", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    OutlinedButton(onClick = onTest, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Wifi, contentDescription = null)
                        Text("测试连接", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        if (state.roots.any { !it.accessible }) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("有目录当前不可访问", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        state.roots.filter { !it.accessible }.take(3).forEach { root ->
                            Text("${root.root.name}：${root.issue ?: "已暂停"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            softWrap = true,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(0.58f).padding(start = 8.dp),
        )
    }
}

private fun stateLabel(state: TunnelConnectionState): String = when (state) {
    TunnelConnectionState.STOPPED -> "未连接"
    TunnelConnectionState.STARTING -> "正在连接"
    TunnelConnectionState.CONNECTED -> "已连接"
    TunnelConnectionState.DISCONNECTED -> "已断开"
    TunnelConnectionState.AUTH_ERROR -> "认证/配置失败"
    TunnelConnectionState.SERVICE_ERROR -> "服务错误"
}

private fun formatTime(time: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
