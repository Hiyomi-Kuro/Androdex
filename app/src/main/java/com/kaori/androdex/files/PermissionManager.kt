package com.kaori.androdex.files

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import com.kaori.androdex.data.AuthorizedRoot
import com.kaori.androdex.data.RootKind
import com.kaori.androdex.data.SettingsRepository
import com.kaori.androdex.core.PathNormalizer
import java.io.File
import java.util.UUID

class PermissionManager(private val context: Context, private val settings: SettingsRepository) {
    fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun isIgnoringBatteryOptimizations(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    fun requestBatteryOptimizationExemptionIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun batteryOptimizationSettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun appNotificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun openAllFilesSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
    }

    fun hasPersistedReadPermission(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }

    fun saveSafTree(uri: Uri): AuthorizedRoot {
        val resolver = context.contentResolver
        val persisted = hasPersistedReadPermission(uri)
        if (!persisted) throw SecurityException("SAF 目录的持久读取权限未生效")
        val label = displayPath(uri)
        if (label.contains("/Android/data", ignoreCase = true) || label.contains("/Android/obb", ignoreCase = true)) {
            throw SecurityException("Android/data 与 Android/obb 受 Android 存储沙箱限制")
        }
        val root = AuthorizedRoot(
            id = UUID.randomUUID().toString(),
            kind = RootKind.SAF,
            name = label.substringAfterLast('/').ifBlank { "已选目录" },
            displayPath = label,
            uriString = uri.toString(),
        )
        settings.saveRoot(root)
        return root
    }

    fun savePathRoot(path: String): AuthorizedRoot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw IllegalStateException("Android 10 请使用系统文件夹选择器；普通路径授权需要 Android 11 或更高版本")
        }
        if (!hasAllFilesAccess()) throw SecurityException("请先授予所有文件访问权限")
        val canonical = PathNormalizer.validateExternalRoot(path, Environment.getExternalStorageDirectory().absolutePath)
        val root = AuthorizedRoot(
            id = UUID.randomUUID().toString(),
            kind = RootKind.PATH,
            name = canonical.name.ifBlank { "外部存储目录" },
            displayPath = canonical.absolutePath,
            filePath = canonical.absolutePath,
        )
        settings.saveRoot(root)
        return root
    }

    fun releaseRoot(root: AuthorizedRoot) {
        if (root.kind == RootKind.SAF && root.uriString != null) {
            val uri = Uri.parse(root.uriString)
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        settings.removeRoot(root.id)
    }

    fun displayPath(uri: Uri): String {
        return runCatching {
            val documentId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            if (documentId.startsWith("primary:")) {
                "/storage/emulated/0/" + documentId.removePrefix("primary:").replace(':', '/')
            } else {
                "${uri.authority ?: "document-provider"}:/${documentId.replace(':', '/')}"
            }.trimEnd('/')
        }.getOrDefault(uri.toString())
    }

    fun openAllFilesSettings(activity: Activity) {
        activity.startActivity(openAllFilesSettingsIntent())
    }
}
