package com.kaori.androdex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaori.androdex.viewmodel.MainUiState

@Composable
fun PermissionsScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    onPickSaf: () -> Unit,
    onAllFiles: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onTestRoot: (String, String) -> Unit,
    onAddPath: (String) -> Unit,
) {
    var pathDialog by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    var testFile by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("添加目录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onPickSaf, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Text("使用系统文件夹选择器", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = onAllFiles, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Security, contentDescription = null)
                        Text(if (state.allFilesAccess) "管理所有文件访问权限" else "打开所有文件访问设置", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text("普通路径授权需要 Android 11 及以上与系统‘所有文件访问’权限；Android 10 请使用 SAF。Android/data、Android/obb 和其他应用私有目录受系统限制。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.allFilesAccess) {
                        OutlinedButton(onClick = { pathDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("添加普通路径白名单", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
        if (state.roots.isEmpty()) item {
            Card { Text("尚未添加目录。先通过系统选择器授予一个目录的持久读取权限。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.roots, key = { it.root.id }) { item ->
            Card(colors = CardDefaults.cardColors(containerColor = if (item.accessible) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = if (item.accessible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(item.root.name, fontWeight = FontWeight.SemiBold)
                            Text(item.root.displayPath, style = MaterialTheme.typography.bodySmall, softWrap = true)
                        }
                        Switch(checked = item.root.enabled, onCheckedChange = { onToggle(item.root.id, it) })
                        IconButton(onClick = { onRemove(item.root.id) }) { Icon(Icons.Filled.DeleteOutline, contentDescription = "删除目录授权") }
                    }
                    Text(
                        if (item.accessible) "权限检查通过 · ${if (item.root.kind.name == "SAF") "SAF 持久授权" else "普通文件路径"}"
                        else "当前不可访问：${item.issue ?: "目录授权已暂停"}",
                        color = if (item.accessible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (item.root.enabled) {
                        OutlinedTextField(
                            value = testFile,
                            onValueChange = { testFile = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("可选：测试相对文件路径") },
                            placeholder = { Text("例如 README.md") },
                        )
                        OutlinedButton(onClick = { onTestRoot(item.root.id, testFile.trim()) }, enabled = item.accessible) {
                            Icon(Icons.Filled.Tune, contentDescription = null)
                            Text("测试目录 / 文件读取", Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }

    if (pathDialog) {
        AlertDialog(
            onDismissRequest = { pathDialog = false },
            title = { Text("添加普通路径白名单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("绝对目录路径") }, placeholder = { Text("/storage/emulated/0/Documents") }, singleLine = true)
                    Text("只允许外部共享存储中的真实目录。每次读取时都会再次检查系统权限与白名单。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { onAddPath(path); path = ""; pathDialog = false }, enabled = path.isNotBlank()) { Text("添加") } },
            dismissButton = { TextButton(onClick = { pathDialog = false }) { Text("取消") } },
        )
    }
}
