package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppLanguage
import com.example.domain.model.ScanPreset
import com.example.domain.model.ScanSettings
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cf_scanner_prefs")

class SettingsDataStore(private val context: Context) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val KEY_SETTINGS_JSON = stringPreferencesKey("settings_json")
  private val KEY_VLESS_URI = stringPreferencesKey("vless_uri_private")
  private val KEY_MANUAL_INPUT = stringPreferencesKey("manual_input_draft")
  private val KEY_ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")

  val settingsFlow: Flow<ScanSettings> =
    context.dataStore.data.map { prefs ->
      val raw = prefs[KEY_SETTINGS_JSON]
      if (raw.isNullOrBlank()) {
        ScanSettings()
      } else {
        runCatching { json.decodeFromString<ScanSettings>(raw) }.getOrDefault(ScanSettings())
      }
    }

  val vlessUriFlow: Flow<String> =
    context.dataStore.data.map { prefs -> prefs[KEY_VLESS_URI].orEmpty() }

  val manualInputFlow: Flow<String> =
    context.dataStore.data.map { prefs -> prefs[KEY_MANUAL_INPUT].orEmpty() }

  val activeSessionIdFlow: Flow<String?> =
    context.dataStore.data.map { prefs -> prefs[KEY_ACTIVE_SESSION_ID] }

  suspend fun updateSettings(transform: (ScanSettings) -> ScanSettings) {
    context.dataStore.edit { prefs ->
      val current =
        prefs[KEY_SETTINGS_JSON]?.let {
          runCatching { json.decodeFromString<ScanSettings>(it) }.getOrNull()
        } ?: ScanSettings()
      val updated = transform(current)
      prefs[KEY_SETTINGS_JSON] = json.encodeToString(updated)
    }
  }

  suspend fun applyPreset(preset: ScanPreset) {
    updateSettings { cur ->
      when (preset) {
        ScanPreset.FAST ->
          cur.copy(
            scanPreset = ScanPreset.FAST,
            autoSampleCount = 50,
            tcpTimeoutMs = 1500,
            tlsTimeoutMs = 2200,
            wsTimeoutMs = 2500,
            phase1Workers = 12,
            phase1Retries = 1,
            tunnelRepetitions = 2,
          )
        ScanPreset.BALANCED ->
          cur.copy(
            scanPreset = ScanPreset.BALANCED,
            autoSampleCount = 100,
            tcpTimeoutMs = 2500,
            tlsTimeoutMs = 3500,
            wsTimeoutMs = 4000,
            phase1Workers = 8,
            phase1Retries = 1,
            tunnelRepetitions = 3,
          )
        ScanPreset.ACCURATE ->
          cur.copy(
            scanPreset = ScanPreset.ACCURATE,
            autoSampleCount = 500,
            tcpTimeoutMs = 3500,
            tlsTimeoutMs = 4500,
            wsTimeoutMs = 5000,
            phase1Workers = 6,
            phase1Retries = 2,
            tunnelRepetitions = 4,
          )
      }
    }
  }

  suspend fun setLanguage(language: AppLanguage) {
    updateSettings { it.copy(language = language) }
  }

  suspend fun setThemeMode(themeMode: ThemeMode) {
    updateSettings { it.copy(themeMode = themeMode) }
  }

  suspend fun saveVlessUri(uri: String) {
    context.dataStore.edit { prefs -> prefs[KEY_VLESS_URI] = uri.trim() }
  }

  suspend fun saveManualInput(text: String) {
    context.dataStore.edit { prefs -> prefs[KEY_MANUAL_INPUT] = text }
  }

  suspend fun saveActiveSessionId(sessionId: String?) {
    context.dataStore.edit { prefs ->
      if (sessionId == null) prefs.remove(KEY_ACTIVE_SESSION_ID)
      else prefs[KEY_ACTIVE_SESSION_ID] = sessionId
    }
  }
}
