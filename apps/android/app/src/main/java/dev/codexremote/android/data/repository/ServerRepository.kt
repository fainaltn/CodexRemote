package dev.codexremote.android.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.codexremote.android.data.model.Server
import dev.codexremote.android.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.serverDataStore by preferencesDataStore(name = "servers")

private val SERVERS_KEY = stringPreferencesKey("server_list")
private val ACTIVE_SERVER_KEY = stringPreferencesKey("active_server_id")
private val THEME_PREFERENCE_KEY = stringPreferencesKey("theme_preference")

enum class SessionFolderSortOrder {
    RECENT,
    NAME_ASC,
    NAME_DESC,
    CUSTOM,
}

/**
 * Persists the saved server list and active selection using DataStore.
 *
 * Phase 0 stores a JSON-encoded list in a single preference key.
 * This is simple and sufficient for the single-host model;
 * Phase 2 multi-host can move to a Room database if needed.
 */
class ServerRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val servers: Flow<List<Server>> = context.serverDataStore.data.map { prefs ->
        val raw = prefs[SERVERS_KEY] ?: "[]"
        json.decodeFromString<List<Server>>(raw)
    }

    val activeServerId: Flow<String?> = context.serverDataStore.data.map { prefs ->
        prefs[ACTIVE_SERVER_KEY]
    }

    val themePreference: Flow<ThemePreference> = context.serverDataStore.data.map { prefs ->
        prefs[THEME_PREFERENCE_KEY]
            ?.let { raw -> ThemePreference.entries.find { it.name == raw } }
            ?: ThemePreference.AUTO
    }

    suspend fun saveServer(server: Server) {
        context.serverDataStore.edit { prefs ->
            val current = prefs[SERVERS_KEY]?.let {
                json.decodeFromString<List<Server>>(it).toMutableList()
            } ?: mutableListOf()
            current.removeAll { it.id == server.id }
            current.add(server)
            prefs[SERVERS_KEY] = json.encodeToString(current)
        }
    }

    suspend fun removeServer(serverId: String) {
        context.serverDataStore.edit { prefs ->
            val current = prefs[SERVERS_KEY]?.let {
                json.decodeFromString<List<Server>>(it).toMutableList()
            } ?: mutableListOf()
            current.removeAll { it.id == serverId }
            prefs[SERVERS_KEY] = json.encodeToString(current)
        }
    }

    suspend fun setActiveServer(serverId: String) {
        context.serverDataStore.edit { prefs ->
            prefs[ACTIVE_SERVER_KEY] = serverId
        }
    }

    suspend fun updateToken(serverId: String, token: String?) {
        context.serverDataStore.edit { prefs ->
            val current = prefs[SERVERS_KEY]?.let {
                json.decodeFromString<List<Server>>(it).toMutableList()
            } ?: mutableListOf()
            val idx = current.indexOfFirst { it.id == serverId }
            if (idx >= 0) {
                current[idx] = current[idx].copy(token = token)
                prefs[SERVERS_KEY] = json.encodeToString(current)
            }
        }
    }

    suspend fun setThemePreference(preference: ThemePreference) {
        context.serverDataStore.edit { prefs ->
            prefs[THEME_PREFERENCE_KEY] = preference.name
        }
    }

    suspend fun getSessionFolderSortOrder(serverId: String): SessionFolderSortOrder {
        val key = stringPreferencesKey("session_folder_sort_$serverId")
        val raw = context.serverDataStore.data.map { prefs -> prefs[key] }.firstOrNull()
        return raw
            ?.let { value -> SessionFolderSortOrder.entries.find { it.name == value } }
            ?: SessionFolderSortOrder.RECENT
    }

    suspend fun setSessionFolderSortOrder(serverId: String, order: SessionFolderSortOrder) {
        val key = stringPreferencesKey("session_folder_sort_$serverId")
        context.serverDataStore.edit { prefs ->
            prefs[key] = order.name
        }
    }

    suspend fun getCustomSessionFolderOrder(serverId: String): List<String> {
        val key = stringPreferencesKey("custom_session_folder_order_$serverId")
        val raw = context.serverDataStore.data.map { prefs -> prefs[key] }.firstOrNull() ?: "[]"
        return json.decodeFromString(raw)
    }

    suspend fun setCustomSessionFolderOrder(serverId: String, folderKeys: List<String>) {
        val key = stringPreferencesKey("custom_session_folder_order_$serverId")
        context.serverDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(folderKeys)
        }
    }

    suspend fun getCollapsedSessionFolders(serverId: String): Set<String> {
        val key = stringPreferencesKey("collapsed_session_folders_$serverId")
        val raw = context.serverDataStore.data.map { prefs -> prefs[key] }.firstOrNull() ?: "[]"
        return json.decodeFromString<List<String>>(raw).toSet()
    }

    suspend fun setCollapsedSessionFolders(serverId: String, folderKeys: Set<String>) {
        val key = stringPreferencesKey("collapsed_session_folders_$serverId")
        context.serverDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(folderKeys.sorted())
        }
    }

    suspend fun getHiddenSessionFolders(serverId: String): Set<String> {
        val key = stringPreferencesKey("hidden_session_folders_$serverId")
        val raw = context.serverDataStore.data.map { prefs -> prefs[key] }.firstOrNull() ?: "[]"
        return json.decodeFromString<List<String>>(raw).toSet()
    }

    suspend fun setHiddenSessionFolders(serverId: String, folderKeys: Set<String>) {
        val key = stringPreferencesKey("hidden_session_folders_$serverId")
        context.serverDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(folderKeys.sorted())
        }
    }
}
