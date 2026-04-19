package app.findeck.mobile.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.findeck.mobile.data.model.TrustedHostMetadata
import app.findeck.mobile.data.model.Server
import app.findeck.mobile.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

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

    val activeServer: Flow<Server?> = combine(activeServerId, servers) { activeServerId, serverList ->
        activeServerId?.let { id -> serverList.firstOrNull { it.id == id } }
    }

    val trustedReconnectServers: Flow<List<Server>> = servers.map { serverList ->
        serverList.filter { it.isTrustedReconnectEligible }
            .sortedByDescending { it.trustedHost?.pairedAt ?: "" }
    }

    val themePreference: Flow<ThemePreference> = context.serverDataStore.data.map { prefs ->
        prefs[THEME_PREFERENCE_KEY]
            ?.let { raw -> ThemePreference.entries.find { it.name == raw } }
            ?: ThemePreference.AUTO
    }

    suspend fun saveServer(server: Server) = editServers { current ->
        val index = current.indexOfFirst { it.id == server.id }
        val merged = if (index >= 0) {
            val existing = current[index]
            // Preserve trust metadata unless the caller explicitly replaces it.
            server.copy(trustedHost = server.trustedHost ?: existing.trustedHost)
        } else {
            server
        }
        current.removeAll { it.id == server.id }
        current.add(merged)
    }

    suspend fun removeServer(serverId: String) {
        editServers { current ->
            current.removeAll { it.id == serverId }
        }
    }

    suspend fun setActiveServer(serverId: String) {
        context.serverDataStore.edit { prefs ->
            prefs[ACTIVE_SERVER_KEY] = serverId
        }
    }

    suspend fun updateToken(serverId: String, token: String?) {
        updateServer(serverId) { it.copy(token = token) }
    }

    suspend fun updateCredentials(
        serverId: String,
        token: String?,
        appPassword: String?,
    ) {
        updateServer(serverId) {
            it.copy(
                token = token,
                appPassword = appPassword,
            )
        }
    }

    suspend fun getServer(serverId: String): Server? {
        return servers.firstOrNull().orEmpty().firstOrNull { it.id == serverId }
    }

    suspend fun getActiveServer(): Server? {
        val activeId = activeServerId.firstOrNull()
        return activeId?.let { getServer(it) }
    }

    suspend fun getTrustedReconnectServer(preferActiveServer: Boolean = true): Server? {
        val currentServers = servers.firstOrNull().orEmpty()
        val activeId = activeServerId.firstOrNull()
        if (preferActiveServer && activeId != null) {
            currentServers.firstOrNull { it.id == activeId && it.isTrustedReconnectEligible }?.let {
                return it
            }
        }
        return currentServers
            .filter { it.isTrustedReconnectEligible }
            .sortedByDescending { it.trustedHost?.pairedAt ?: "" }
            .firstOrNull()
    }

    suspend fun setTrustedHostMetadata(serverId: String, metadata: TrustedHostMetadata?) {
        updateServer(serverId) {
            it.copy(trustedHost = metadata)
        }
    }

    suspend fun markTrustedHost(
        serverId: String,
        pairingMethod: String? = null,
        trustLabel: String? = null,
        trustedUntil: String? = null,
        trustedClientId: String? = null,
        trustedClientSecret: String? = null,
    ) {
        updateServer(serverId) { current ->
            current.copy(
                trustedHost = TrustedHostMetadata(
                    pairedAt = current.trustedHost?.pairedAt ?: Instant.now().toString(),
                    lastAutoReconnectAt = current.trustedHost?.lastAutoReconnectAt,
                    trustedUntil = trustedUntil ?: current.trustedHost?.trustedUntil,
                    autoReconnectEnabled = true,
                    pairingMethod = pairingMethod ?: current.trustedHost?.pairingMethod,
                    trustLabel = trustLabel ?: current.trustedHost?.trustLabel,
                    trustedClientId = trustedClientId ?: current.trustedHost?.trustedClientId,
                    trustedClientSecret = trustedClientSecret ?: current.trustedHost?.trustedClientSecret,
                )
            )
        }
    }

    suspend fun setTrustedAutoReconnectEnabled(serverId: String, enabled: Boolean) {
        updateServer(serverId) { current ->
            val existing = current.trustedHost
            if (existing == null) {
                current
            } else {
                current.copy(
                    trustedHost = existing.copy(autoReconnectEnabled = enabled)
                )
            }
        }
    }

    suspend fun touchTrustedReconnect(serverId: String) {
        updateServer(serverId) { current ->
            val existing = current.trustedHost
            if (existing == null) {
                current
            } else {
                current.copy(
                    trustedHost = existing.copy(lastAutoReconnectAt = Instant.now().toString())
                )
            }
        }
    }

    suspend fun clearTrustedHostMetadata(serverId: String) {
        updateServer(serverId) { it.copy(trustedHost = null) }
    }

    suspend fun setThemePreference(preference: ThemePreference) {
        context.serverDataStore.edit { prefs ->
            prefs[THEME_PREFERENCE_KEY] = preference.name
        }
    }

    suspend fun getRuntimeDefaultModel(serverId: String): String? {
        val key = stringPreferencesKey("runtime_default_model_$serverId")
        return context.serverDataStore.data.map { prefs -> prefs[key] }.firstOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun setRuntimeDefaultModel(serverId: String, model: String?) {
        val key = stringPreferencesKey("runtime_default_model_$serverId")
        context.serverDataStore.edit { prefs ->
            if (model.isNullOrBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = model
            }
        }
    }

    suspend fun getRuntimeDefaultReasoningEffort(serverId: String): String? {
        val key = stringPreferencesKey("runtime_default_reasoning_$serverId")
        return context.serverDataStore.data.map { prefs -> prefs[key] }.firstOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun setRuntimeDefaultReasoningEffort(serverId: String, reasoningEffort: String?) {
        val key = stringPreferencesKey("runtime_default_reasoning_$serverId")
        context.serverDataStore.edit { prefs ->
            if (reasoningEffort.isNullOrBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = reasoningEffort
            }
        }
    }

    suspend fun getRuntimeDefaultPermissionMode(serverId: String): String? {
        val key = stringPreferencesKey("runtime_default_permission_$serverId")
        return context.serverDataStore.data.map { prefs -> prefs[key] }.firstOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun setRuntimeDefaultPermissionMode(serverId: String, permissionMode: String?) {
        val key = stringPreferencesKey("runtime_default_permission_$serverId")
        context.serverDataStore.edit { prefs ->
            if (permissionMode.isNullOrBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = permissionMode
            }
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

    private suspend fun updateServer(serverId: String, transform: (Server) -> Server) {
        editServers { current ->
            val index = current.indexOfFirst { it.id == serverId }
            if (index >= 0) {
                current[index] = transform(current[index])
            }
        }
    }

    private suspend fun editServers(transform: (MutableList<Server>) -> Unit) {
        context.serverDataStore.edit { prefs ->
            val current = prefs[SERVERS_KEY]?.let {
                json.decodeFromString<List<Server>>(it).toMutableList()
            } ?: mutableListOf()
            transform(current)
            prefs[SERVERS_KEY] = json.encodeToString(current)
        }
    }
}
