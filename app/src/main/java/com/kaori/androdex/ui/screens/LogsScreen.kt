package com.kaori.androdex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaori.androdex.viewmodel.MainUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(state: MainUiState, contentPadding: PaddingValues, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp).padding(top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最近 ${state.logs.size} 条事件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { confirmClear = true }, enabled = state.logs.isNotEmpty()) { Text("清空") }
        }
        Text("日志会脱敏，不记录 API Key 或完整文件正文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.logs.isEmpty()) Card { Text("暂无运行记录。", Modifier.padding(16.dp)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(state.logs.asReversed(), key = { it.id }) { entry ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(entry.kind, fontWeight = FontWeight.SemiBold, color = if (entry.kind == "错误") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Text(SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.timeMillis)), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(entry.message, style = MaterialTheme.typography.bodySmall)
                        if (entry.durationMillis != null || entry.bytes != null) {
                            Text(listOfNotNull(entry.durationMillis?.let { "${it}ms" }, entry.bytes?.let { "$it B" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空运行日志？") },
        text = { Text("此操作只删除 Androdex 的本地诊断记录。") },
        confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
}
