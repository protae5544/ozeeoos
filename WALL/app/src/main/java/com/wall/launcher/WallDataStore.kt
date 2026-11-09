package com.wall.launcher

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.wallDataStore: DataStore<Preferences> by preferencesDataStore(name = "wall_preferences")

/**
 * Strictly limited to the 5 allowed user settings + structural wall persistence
 * (Curb Dock pins, Wheatpaste Folders, and Ghost Stencil remnants).
 */
data class WallSettings(
    val reduceMotion: Boolean = false,
    val columnCount: Int = 6, // Default 6 for Mi Pad 5 Pro 16:10 tablet geometry
    val hiddenPackages: Set<String> = emptySet(),
    val showStatusStencil: Boolean = true,
    val showAppLabels: Boolean = true,
    val dockPackages: List<String> = emptyList(),
    val folders: Map<String, List<String>> = emptyMap(), // FolderName -> List<PackageName>
    val ghostRemnants: Map<String, String> = emptyMap(), // GridSlotIndex -> PackageName left behind
    val demoRedDrips: Set<String> = emptySet()
)

class WallRepository(private val context: Context) {

    private object Keys {
        // Strictly 5 User Settings:
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val COLUMN_COUNT = intPreferencesKey("column_count")
        val HIDDEN_PACKAGES = stringSetPreferencesKey("hidden_packages")
        val SHOW_STATUS_STENCIL = booleanPreferencesKey("show_status_stencil")
        val SHOW_APP_LABELS = booleanPreferencesKey("show_app_labels")

        // Structural Wall State:
        val DOCK_PACKAGES = stringPreferencesKey("dock_packages")
        val FOLDERS_SERIALIZED = stringPreferencesKey("folders_serialized")
        val GHOST_REMNANTS = stringPreferencesKey("ghost_remnants")
        val DEMO_RED_DRIPS = stringSetPreferencesKey("demo_red_drips")
    }

    val settingsFlow: Flow<WallSettings> = context.wallDataStore.data.map { prefs ->
        WallSettings(
            reduceMotion = prefs[Keys.REDUCE_MOTION] ?: false,
            columnCount = prefs[Keys.COLUMN_COUNT] ?: 6,
            hiddenPackages = prefs[Keys.HIDDEN_PACKAGES] ?: emptySet(),
            showStatusStencil = prefs[Keys.SHOW_STATUS_STENCIL] ?: true,
            showAppLabels = prefs[Keys.SHOW_APP_LABELS] ?: true,
            dockPackages = prefs[Keys.DOCK_PACKAGES]
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?: emptyList(),
            folders = deserializeFolders(prefs[Keys.FOLDERS_SERIALIZED] ?: ""),
            ghostRemnants = deserializeGhosts(prefs[Keys.GHOST_REMNANTS] ?: ""),
            demoRedDrips = prefs[Keys.DEMO_RED_DRIPS] ?: emptySet()
        )
    }

    suspend fun setReduceMotion(enabled: Boolean) {
        context.wallDataStore.edit { it[Keys.REDUCE_MOTION] = enabled }
    }

    suspend fun setColumnCount(columns: Int) {
        context.wallDataStore.edit { it[Keys.COLUMN_COUNT] = columns.coerceIn(4, 8) }
    }

    suspend fun toggleHiddenPackage(packageName: String) {
        context.wallDataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (!current.add(packageName)) current.remove(packageName)
            prefs[Keys.HIDDEN_PACKAGES] = current
        }
    }

    suspend fun clearHiddenPackages() {
        context.wallDataStore.edit { it[Keys.HIDDEN_PACKAGES] = emptySet() }
    }

    suspend fun setShowStatusStencil(show: Boolean) {
        context.wallDataStore.edit { it[Keys.SHOW_STATUS_STENCIL] = show }
    }

    suspend fun setShowAppLabels(show: Boolean) {
        context.wallDataStore.edit { it[Keys.SHOW_APP_LABELS] = show }
    }

    suspend fun updateDockPackages(packages: List<String>) {
        context.wallDataStore.edit { prefs ->
            prefs[Keys.DOCK_PACKAGES] = packages.distinct().take(7).joinToString("|")
        }
    }

    suspend fun saveFolders(folders: Map<String, List<String>>) {
        context.wallDataStore.edit { prefs ->
            prefs[Keys.FOLDERS_SERIALIZED] = serializeFolders(folders)
        }
    }

    suspend fun recordGhostRemnant(slotKey: String, packageName: String) {
        context.wallDataStore.edit { prefs ->
            val current = deserializeGhosts(prefs[Keys.GHOST_REMNANTS] ?: "").toMutableMap()
            current[slotKey] = packageName
            // Keep most recent 12 ghost stencil stains on the wall
            val trimmed = current.entries.toList().takeLast(12).associate { it.key to it.value }
            prefs[Keys.GHOST_REMNANTS] = serializeGhosts(trimmed)
        }
    }

    suspend fun toggleRedDrip(packageName: String) {
        context.wallDataStore.edit { prefs ->
            val current = prefs[Keys.DEMO_RED_DRIPS]?.toMutableSet() ?: mutableSetOf()
            if (!current.add(packageName)) current.remove(packageName)
            prefs[Keys.DEMO_RED_DRIPS] = current
        }
    }

    private fun serializeFolders(folders: Map<String, List<String>>): String {
        return folders.entries.joinToString(";;") { (name, pkgs) ->
            val cleanName = name.replace("::", "").replace(";;", "")
            "$cleanName::${pkgs.distinct().joinToString(",")}"
        }
    }

    private fun deserializeFolders(raw: String): Map<String, List<String>> {
        if (raw.isBlank()) return emptyMap()
        val result = mutableMapOf<String, List<String>>()
        raw.split(";;").forEach { entry ->
            val parts = entry.split("::")
            if (parts.size == 2 && parts[0].isNotBlank()) {
                val pkgs = parts[1].split(",").filter { it.isNotBlank() }
                if (pkgs.isNotEmpty()) {
                    result[parts[0]] = pkgs
                }
            }
        }
        return result
    }

    private fun serializeGhosts(ghosts: Map<String, String>): String {
        return ghosts.entries.joinToString(";;") { "${it.key}::${it.value}" }
    }

    private fun deserializeGhosts(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        raw.split(";;").forEach { entry ->
            val parts = entry.split("::")
            if (parts.size == 2) map[parts[0]] = parts[1]
        }
        return map
    }
}
