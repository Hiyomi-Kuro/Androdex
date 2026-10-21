package com.kaori.androdex.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDefaultsTest {
    @Test fun newInstallDefaultsAreSafeAndHaveSeparateReadBudgets() {
        val settings = UserSettings()
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue(null))
        assertEquals(NetworkTimeoutDefaults.REQUEST_SECONDS, settings.networkTimeoutSeconds)
        assertEquals(10_000_000, settings.maxReadChars)
        assertEquals(64L * 1024 * 1024, settings.maxReadBytes)
        assertEquals(262_144, settings.maxOutputChars)
    }

    @Test fun themePreferenceAndLegacyValuesHaveSafeFallbacks() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStoredValue("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStoredValue("DARK"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue("unknown"))
        assertEquals(true, ThemeMode.SYSTEM.isDark(systemIsDark = true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(systemIsDark = false))
        assertEquals(false, ThemeMode.LIGHT.isDark(systemIsDark = true))
        assertEquals(true, ThemeMode.DARK.isDark(systemIsDark = false))
    }
}
