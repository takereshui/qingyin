package im.molan.music.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import im.molan.music.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "qingyin_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val darkTheme = booleanPreferencesKey("dark_theme")
        val quality = stringPreferencesKey("quality")
        val ncmcBaseUrl = stringPreferencesKey("ncmc_base_url")
        val backupNcmcBaseUrl = stringPreferencesKey("backup_ncmc_base_url")
        val useBackupNcmc = booleanPreferencesKey("use_backup_ncmc")
        val chkszBaseUrl = stringPreferencesKey("chksz_base_url")
        val useChkszBackup = booleanPreferencesKey("use_chksz_backup")
        val chkszApiKey = stringPreferencesKey("chksz_api_key")
        val customFolderUri = stringPreferencesKey("custom_folder_uri")
        val ncmCookie = stringPreferencesKey("ncm_cookie")
        val ncmNickname = stringPreferencesKey("ncm_nickname")
        val ncmUserId = longPreferencesKey("ncm_user_id")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            darkTheme = preferences[Keys.darkTheme] ?: false,
            quality = AppSettings.Quality.entries.firstOrNull { it.wireValue == preferences[Keys.quality] }
                ?: AppSettings.Quality.EXHIGH,
            ncmcBaseUrl = preferences[Keys.ncmcBaseUrl] ?: "https://music.mcseekeri.com",
            backupNcmcBaseUrl = preferences[Keys.backupNcmcBaseUrl] ?: "",
            useBackupNcmc = preferences[Keys.useBackupNcmc] ?: false,
            chkszBaseUrl = preferences[Keys.chkszBaseUrl] ?: "https://api.chksz.com",
            useChkszBackup = preferences[Keys.useChkszBackup] ?: false,
            chkszApiKey = preferences[Keys.chkszApiKey] ?: "",
            customFolderUri = preferences[Keys.customFolderUri] ?: "",
            ncmCookie = preferences[Keys.ncmCookie] ?: "",
            ncmNickname = preferences[Keys.ncmNickname] ?: "",
            ncmUserId = preferences[Keys.ncmUserId] ?: 0L,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val current = settings.first()
        val next = transform(current)
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.darkTheme] = next.darkTheme
            preferences[Keys.quality] = next.quality.wireValue
            preferences[Keys.ncmcBaseUrl] = next.ncmcBaseUrl.trimEnd('/')
            preferences[Keys.backupNcmcBaseUrl] = next.backupNcmcBaseUrl.trimEnd('/')
            preferences[Keys.useBackupNcmc] = next.useBackupNcmc
            preferences[Keys.chkszBaseUrl] = next.chkszBaseUrl.trimEnd('/')
            preferences[Keys.useChkszBackup] = next.useChkszBackup
            preferences[Keys.chkszApiKey] = next.chkszApiKey.trim()
            preferences[Keys.customFolderUri] = next.customFolderUri
            preferences[Keys.ncmCookie] = next.ncmCookie
            preferences[Keys.ncmNickname] = next.ncmNickname
            preferences[Keys.ncmUserId] = next.ncmUserId
        }
    }
}
