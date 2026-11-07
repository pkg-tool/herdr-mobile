package dev.herdr.android

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.serverDataStore by preferencesDataStore(name = "servers")

class ServerStore(context: Context) {
    private val store = context.applicationContext.serverDataStore
    private val profilesKey = stringPreferencesKey("profiles")
    private val json = Json { ignoreUnknownKeys = true }

    private val settingsKey = stringPreferencesKey("client_settings")
    val settings = store.data.map { preferences ->
        preferences[settingsKey]?.let { json.decodeFromString<ClientSettings>(it) } ?: ClientSettings()
    }

    suspend fun saveSettings(settings: ClientSettings) {
        require(settings.theme in setOf("system", "light", "dark") && settings.fontSize in 8f..28f)
        store.edit { it[settingsKey] = json.encodeToString(settings) }
    }

    val profiles = store.data.map { preferences ->
        preferences[profilesKey]?.let { json.decodeFromString<List<ServerProfile>>(it) }.orEmpty()
    }

    suspend fun save(profile: ServerProfile) {
        profile.validate()
        store.edit { preferences ->
            val current = preferences[profilesKey]?.let {
                json.decodeFromString<List<ServerProfile>>(it)
            }.orEmpty()
            preferences[profilesKey] = json.encodeToString(
                current.filterNot { it.id == profile.id } + profile,
            )
        }
    }

    suspend fun remove(id: String) {
        store.edit { preferences ->
            val current = preferences[profilesKey]?.let {
                json.decodeFromString<List<ServerProfile>>(it)
            }.orEmpty()
            preferences[profilesKey] = json.encodeToString(current.filterNot { it.id == id })
        }
    }
}
