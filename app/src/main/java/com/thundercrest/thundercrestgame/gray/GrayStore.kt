package com.thundercrest.thundercrestgame.gray

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.grayDataStore: DataStore<Preferences> by preferencesDataStore(name = "gray_store")

/**
 * Persistent state for the "gray" (shell) part of the app: decided mode, cached
 * WebView link + expiry, notification prompt bookkeeping, and any one-shot links
 * carried in from a push tap.
 */
class GrayStore(private val ctx: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("app_mode")
        val URL = stringPreferencesKey("saved_url")
        val EXPIRES = longPreferencesKey("saved_expires")
        val NOTIF_LAST_PROMPT = longPreferencesKey("notif_last_prompt_ms")
        val NOTIF_PERMANENT_DENY = booleanPreferencesKey("notif_permanent_deny")
        val PENDING_PUSH_URL = stringPreferencesKey("pending_push_url")
    }

    // ---- App mode --------------------------------------------------------

    suspend fun mode(): AppMode = ctx.grayDataStore.data
        .map { it[Keys.MODE] }
        .first()
        ?.let { runCatching { AppMode.valueOf(it) }.getOrDefault(AppMode.UNSET) }
        ?: AppMode.UNSET

    suspend fun setMode(mode: AppMode) {
        ctx.grayDataStore.edit { it[Keys.MODE] = mode.name }
    }

    // ---- Cached link -----------------------------------------------------

    suspend fun savedLink(): SavedLink? {
        val prefs = ctx.grayDataStore.data.first()
        val url = prefs[Keys.URL] ?: return null
        val expires = prefs[Keys.EXPIRES] ?: 0L
        return SavedLink(url, expires)
    }

    suspend fun saveLink(url: String, expires: Long) {
        ctx.grayDataStore.edit {
            it[Keys.URL] = url
            it[Keys.EXPIRES] = expires
        }
    }

    suspend fun clearSavedLink() {
        ctx.grayDataStore.edit {
            it.remove(Keys.URL)
            it.remove(Keys.EXPIRES)
        }
    }

    // ---- Notification prompt bookkeeping ---------------------------------

    suspend fun notifLastPromptMs(): Long =
        ctx.grayDataStore.data.map { it[Keys.NOTIF_LAST_PROMPT] ?: 0L }.first()

    suspend fun setNotifLastPromptMs(ms: Long) {
        ctx.grayDataStore.edit { it[Keys.NOTIF_LAST_PROMPT] = ms }
    }

    suspend fun notifPermanentlyDenied(): Boolean =
        ctx.grayDataStore.data.map { it[Keys.NOTIF_PERMANENT_DENY] ?: false }.first()

    suspend fun setNotifPermanentlyDenied(v: Boolean) {
        ctx.grayDataStore.edit { it[Keys.NOTIF_PERMANENT_DENY] = v }
    }

    // ---- One-shot URL from a push tap ------------------------------------

    suspend fun consumePendingPushUrl(): String? {
        val v = ctx.grayDataStore.data.map { it[Keys.PENDING_PUSH_URL] }.first()
        if (!v.isNullOrEmpty()) {
            ctx.grayDataStore.edit { it.remove(Keys.PENDING_PUSH_URL) }
        }
        return v
    }

    suspend fun setPendingPushUrl(url: String) {
        ctx.grayDataStore.edit { it[Keys.PENDING_PUSH_URL] = url }
    }
}
