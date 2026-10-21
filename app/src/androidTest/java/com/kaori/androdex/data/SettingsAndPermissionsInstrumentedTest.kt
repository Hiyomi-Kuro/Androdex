package com.kaori.androdex.data

import android.provider.Settings
import android.os.PowerManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kaori.androdex.files.PermissionManager
import com.kaori.androdex.service.ConnectionService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAndPermissionsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceFile = "androdex_settings_test"

    @Before fun clearTestPreferences() {
        context.getSharedPreferences(preferenceFile, android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun removeTestPreferences() {
        context.getSharedPreferences(preferenceFile, android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun timeoutThemeAndReadBudgetsDefaultAndPersist() {
        val repository = SettingsRepository(context, preferenceFile)
        assertEquals(120, repository.settings().networkTimeoutSeconds)
        assertEquals(ThemeMode.SYSTEM, repository.themeMode())
        assertEquals(10_000_000, repository.settings().maxReadChars)
        assertEquals(262_144, repository.settings().maxOutputChars)

        repository.saveThemeMode(ThemeMode.DARK)
        repository.saveSettings(repository.settings().copy(networkTimeoutSeconds = 75, maxReadChars = 5_000_000))
        val reopened = SettingsRepository(context, preferenceFile)
        assertEquals(ThemeMode.DARK, reopened.themeMode())
        assertEquals(75, reopened.settings().networkTimeoutSeconds)
        assertEquals(5_000_000, reopened.settings().maxReadChars)
    }

    @Test fun legacyTimeoutAndByteLimitAreKeptWhileNewReadBudgetUsesDefault() {
        context.getSharedPreferences(preferenceFile, android.content.Context.MODE_PRIVATE).edit()
            .putInt("network_timeout", 45)
            .putLong("max_read_bytes", 5L * 1024 * 1024)
            .commit()

        val settings = SettingsRepository(context, preferenceFile).settings()
        assertEquals(45, settings.networkTimeoutSeconds)
        assertEquals(5L * 1024 * 1024, settings.maxReadBytes)
        assertEquals(10_000_000, settings.maxReadChars)
    }

    @Test fun batteryOptimizationActionsUseOfficialSystemSettings() {
        val manager = PermissionManager(context, SettingsRepository(context, preferenceFile))
        val request = manager.requestBatteryOptimizationExemptionIntent()
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, request.action)
        assertEquals("package:${context.packageName}", request.dataString)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, manager.batteryOptimizationSettingsIntent().action)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, manager.appNotificationSettingsIntent().action)
        val expected = (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
        assertEquals(expected, manager.isIgnoringBatteryOptimizations())
    }

    @Test fun tunnelServiceDeclaresItsAndroid14SpecialUseType() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, ConnectionService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
    }
}
