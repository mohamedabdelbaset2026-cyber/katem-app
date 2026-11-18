package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.katemDataStore by preferencesDataStore(name = "katem_preferences")

class AppPreferences(private val context: Context) {

    private val darkThemeKey = booleanPreferencesKey("dark_theme")
    private val hostingerBaseUrlKey = stringPreferencesKey("hostinger_media_base_url")
    private val localDeviceIdKey = stringPreferencesKey("local_device_id")

    val darkThemeFlow: Flow<Boolean> = context.katemDataStore.data.map { prefs ->
        prefs[darkThemeKey] ?: true
    }

    val hostingerBaseUrlFlow: Flow<String> = context.katemDataStore.data.map { prefs ->
        prefs[hostingerBaseUrlKey]?.takeIf { it.isNotBlank() }
            ?: BuildConfig.HOSTINGER_MEDIA_BASE_URL
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.katemDataStore.edit { prefs ->
            prefs[darkThemeKey] = enabled
        }
    }

    suspend fun setHostingerBaseUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        context.katemDataStore.edit { prefs ->
            prefs[hostingerBaseUrlKey] = cleaned
        }
    }

    suspend fun getOrCreateDeviceId(): String {
        val existing = context.katemDataStore.data.first()[localDeviceIdKey]
        if (!existing.isNullOrBlank()) return existing
        val created = "dev_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        context.katemDataStore.edit { prefs ->
            prefs[localDeviceIdKey] = created
        }
        return created
    }

    fun zangiOnboardingCompletedFlow(uid: String): Flow<Boolean> {
        val key = booleanPreferencesKey("zangi_setup_done_$uid")
        return context.katemDataStore.data.map { prefs -> prefs[key] ?: false }
    }

    suspend fun markZangiOnboardingCompleted(uid: String, completed: Boolean = true) {
        val key = booleanPreferencesKey("zangi_setup_done_$uid")
        context.katemDataStore.edit { prefs -> prefs[key] = completed }
    }

    fun requirePasswordOnLaunchFlow(uid: String): Flow<Boolean> {
        val key = booleanPreferencesKey("require_password_launch_$uid")
        return context.katemDataStore.data.map { prefs -> prefs[key] ?: false }
    }

    suspend fun setRequirePasswordOnLaunch(uid: String, enabled: Boolean) {
        val key = booleanPreferencesKey("require_password_launch_$uid")
        context.katemDataStore.edit { prefs -> prefs[key] = enabled }
    }
}
