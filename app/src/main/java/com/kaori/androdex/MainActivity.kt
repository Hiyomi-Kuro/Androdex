package com.kaori.androdex

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kaori.androdex.data.TunnelConnectionState
import com.kaori.androdex.ui.screens.FileManagerScreen
import com.kaori.androdex.ui.screens.HomeScreen
import com.kaori.androdex.ui.screens.LogsScreen
import com.kaori.androdex.ui.screens.PermissionsScreen
import com.kaori.androdex.ui.screens.SettingsScreen
import com.kaori.androdex.ui.theme.AndrodexTheme
import com.kaori.androdex.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    @Suppress("UNCHECKED_CAST")
    private val viewModel by viewModels<MainViewModel> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(application) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AndrodexTheme { AndrodexApp(viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

private enum class Page(val title: String) {
    HOME("首页"), FOLDERS("目录"), FILES("文件"), LOGS("日志"), SETTINGS("设置"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndrodexApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var page by remember { mutableStateOf(Page.HOME) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.addSafFolder(uri)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startService()
        else viewModel.showMessage("Android 13 及以上需要允许通知，Androdex 才能以可见前台服务运行。")
    }

    BackHandler(enabled = page != Page.HOME) {
        page = when (page) {
            Page.SETTINGS -> Page.HOME
            Page.FILES -> Page.HOME
            Page.FOLDERS -> Page.HOME
            Page.LOGS -> Page.HOME
            Page.HOME -> Page.HOME
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (page == Page.HOME) "Androdex" else page.title,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    if (page != Page.HOME) IconButton(onClick = { page = Page.HOME }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回首页")
                    }
                },
                actions = {
                    if (page != Page.SETTINGS) IconButton(onClick = { page = Page.SETTINGS }) {
                        Icon(Icons.Filled.Settings, contentDescription = "打开设置")
                    }
                },
            )
        },
        bottomBar = {
            if (page != Page.SETTINGS) {
                NavigationBar {
                    listOf(
                        Page.HOME to Icons.Filled.Home,
                        Page.FOLDERS to Icons.Filled.Folder,
                        Page.FILES to Icons.Filled.InsertDriveFile,
                        Page.LOGS to Icons.Filled.Article,
                    ).forEach { (destination, icon) ->
                        NavigationBarItem(
                            selected = page == destination,
                            onClick = { page = destination },
                            icon = { Icon(icon, contentDescription = destination.title) },
                            label = { Text(destination.title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (page) {
            Page.HOME -> HomeScreen(
                state = state,
                contentPadding = padding,
                onStart = {
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else viewModel.startService()
                },
                onStop = viewModel::stopService,
                onTest = viewModel::testConfiguration,
                onSettings = { page = Page.SETTINGS },
                onFolders = { page = Page.FOLDERS },
                onLogs = { page = Page.LOGS },
            )
            Page.FOLDERS -> PermissionsScreen(
                state = state,
                contentPadding = padding,
                onPickSaf = { folderPicker.launch(null) },
                onAllFiles = viewModel::openAllFilesSettings,
                onToggle = viewModel::setRootEnabled,
                onRemove = viewModel::removeRoot,
                onTestRoot = viewModel::testRoot,
                onAddPath = viewModel::addPathFolder,
            )
            Page.FILES -> FileManagerScreen(
                state = state,
                contentPadding = padding,
                onBrowse = viewModel::browse,
                onPreview = { rootId, path, document -> viewModel.preview(rootId, path, document) },
                onClosePreview = viewModel::closePreview,
            )
            Page.LOGS -> LogsScreen(state = state, contentPadding = padding, onClear = viewModel::clearLogs)
            Page.SETTINGS -> SettingsScreen(state = state, contentPadding = padding, viewModel = viewModel)
        }
    }

    if (state.message != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            title = { Text("Androdex") },
            text = { Text(state.message.orEmpty(), fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("知道了") } },
        )
    }
}
