package com.kaori.androdex.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kaori.androdex.AndrodexApplication
import com.kaori.androdex.MainActivity
import com.kaori.androdex.R
import com.kaori.androdex.data.TunnelConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConnectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var stateJob: Job? = null

    private val graph get() = (application as AndrodexApplication).graph

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                graph.connectionManager.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    graph.diagnostics.log("错误", "未授予通知权限，前台 Tunnel 服务未启动", error = true)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                try {
                    val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(TunnelConnectionState.STARTING), type)
                } catch (error: Exception) {
                    graph.diagnostics.log("错误", "无法启动 Android 前台服务（${error.javaClass.simpleName}）", error = true)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                graph.connectionManager.start()
                stateJob?.cancel()
                stateJob = serviceScope.launch {
                    graph.connectionManager.state.collect { status ->
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, buildNotification(status))
                    }
                }
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(callback) }
        }
        graph.connectionManager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = graph.connectionManager.notifyNetworkChanged()
            override fun onLost(network: Network) = graph.connectionManager.notifyNetworkChanged()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: android.net.NetworkCapabilities) =
                graph.connectionManager.notifyNetworkChanged()
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Androdex 隧道服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示 Androdex MCP Tunnel 服务的实际运行状态"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: TunnelConnectionState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, ConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val description = when (state) {
            TunnelConnectionState.STARTING -> "正在连接 Secure MCP Tunnel"
            TunnelConnectionState.CONNECTED -> "已连接，等待用户发起的 MCP 请求"
            TunnelConnectionState.DISCONNECTED -> "连接中断${if (graph.settings.settings().autoReconnect) "，正在重连" else ""}"
            TunnelConnectionState.AUTH_ERROR -> "凭据或 Tunnel 配置需要检查"
            TunnelConnectionState.SERVICE_ERROR -> "服务出现错误，请打开 Androdex 检查"
            TunnelConnectionState.STOPPED -> "服务已停止"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_androdex)
            .setContentTitle("Androdex")
            .setContentText(description)
            .setContentIntent(openApp)
            .setOngoing(state != TunnelConnectionState.STOPPED)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "停止服务", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.kaori.androdex.action.START_TUNNEL"
        const val ACTION_STOP = "com.kaori.androdex.action.STOP_TUNNEL"
        private const val CHANNEL_ID = "androdex_tunnel"
        private const val NOTIFICATION_ID = 3901
    }
}
