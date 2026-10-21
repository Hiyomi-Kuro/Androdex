package com.kaori.androdex.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaori.androdex.viewmodel.BrowserEntry
import com.kaori.androdex.viewmodel.MainUiState
import java.text.DecimalFormat

@Composable
fun FileManagerScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    onBrowse: (String, String) -> Unit,
    onPreview: (String, String, Boolean) -> Unit,
    onClosePreview: () -> Unit,
) {
    val roots = state.roots.filter { it.accessible }
    var selectedRootId by remember { mutableStateOf<String?>(null) }
    var rootMenu by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(roots.map { it.root.id }) {
        if (selectedRootId !in roots.map { it.root.id }) selectedRootId = roots.firstOrNull()?.root?.id
        selectedRootId?.let { onBrowse(it, "") }
    }
    val selected = roots.firstOrNull { it.root.id == selectedRootId }

    Column(Modifier.padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (roots.isEmpty()) {
            Card { Text("没有当前可访问的授权目录。请先在‘目录’页面添加并启用目录。", Modifier.padding(16.dp)) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rootMenu = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Text(selected?.root?.name ?: "选择目录", Modifier.padding(start = 8.dp))
                }
                DropdownMenu(expanded = rootMenu, onDismissRequest = { rootMenu = false }) {
                    roots.forEach { item -> DropdownMenuItem(text = { Text(item.root.displayPath) }, onClick = { selectedRootId = item.root.id; rootMenu = false; pathInput = ""; onBrowse(item.root.id, "") }) }
                }
                IconButton(onClick = { selectedRootId?.let { onBrowse(it, state.browserPath) } }) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
            }
            Text(selected?.root?.displayPath.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.browserPath.isNotBlank()) IconButton(onClick = {
                    val parent = state.browserPath.substringBeforeLast('/', "")
                    selectedRootId?.let { onBrowse(it, parent) }
                }) { Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一级") }
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("授权目录内相对路径") },
                    placeholder = { Text(state.browserPath.ifBlank { "留空表示根目录" }) },
                )
                OutlinedButton(onClick = { selectedRootId?.let { onBrowse(it, pathInput.trim()) } }) { Text("打开") }
            }
            Text("/${state.browserPath}".trimEnd('/').ifBlank { "/" }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            if (state.browserEntries.isEmpty()) {
                Card { Text("此目录为空，或没有可列出的内容。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(state.browserEntries, key = { it.path }) { entry -> EntryCard(
                    entry = entry,
                    onClick = {
                        if (entry.directory) {
                            pathInput = entry.path
                            selectedRootId?.let { onBrowse(it, entry.path) }
                        } else if (entry.image) notice = "图片正文不会由 Androdex 读取。请在 ChatGPT 对话中手动上传图片；这里仅展示文件元数据。"
                        else selectedRootId?.let { onPreview(it, entry.path, isDocumentFile(entry.name)) }
                    },
                ) }
            }
        }
    }

    if (state.previewTitle != null) AlertDialog(
        onDismissRequest = onClosePreview,
        title = { Text(state.previewTitle.orEmpty(), style = MaterialTheme.typography.titleSmall) },
        text = { Text(state.previewText.orEmpty(), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = onClosePreview) { Text("关闭") } },
    )
    if (notice != null) AlertDialog(
        onDismissRequest = { notice = null },
        title = { Text("图片读取限制") },
        text = { Text(notice.orEmpty()) },
        confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } },
    )
}

@Composable
private fun EntryCard(entry: BrowserEntry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when { entry.directory -> Icons.Filled.Folder; entry.image -> Icons.Filled.Image; else -> Icons.Filled.Description },
                contentDescription = null,
                tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    (if (entry.directory) "文件夹" else if (entry.image) "图片 · 仅元数据" else "${formatBytes(entry.size)}${entry.modified?.let { " · $it" } ?: ""}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun isDocumentFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("pdf", "docx", "xlsx", "pptx")
private fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> "${DecimalFormat("0.#").format(value / 1024.0)} KB"
    else -> "${DecimalFormat("0.##").format(value / (1024.0 * 1024.0))} MB"
}
