package io.github.tendo505.roadpulse.android.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Clock
import java.util.Locale
import java.util.UUID

private val Context.dataStore by preferencesDataStore("settings")

enum class Units { METRIC, IMPERIAL }

data class Settings(
    /** Blank = reports stay on this phone. */
    val serverUrl: String = "",
    val voice: Boolean = true,
    val units: Units = defaultUnits(),
    val deviceId: String = "",
)

/** Miles and feet only where they're the everyday unit for driving. */
fun defaultUnits(locale: Locale = Locale.getDefault()): Units =
    if (locale.country in setOf("US", "GB", "LR", "MM")) Units.IMPERIAL else Units.METRIC

class SettingsStore(private val context: Context) {
    private val urlKey = stringPreferencesKey("server_url")
    private val voiceKey = booleanPreferencesKey("voice")
    private val unitsKey = stringPreferencesKey("units")
    private val deviceKey = stringPreferencesKey("device_id")
    private val myReportsKey = stringPreferencesKey("my_reports")

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            serverUrl = p[urlKey] ?: "",
            voice = p[voiceKey] ?: true,
            units = p[unitsKey]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: defaultUnits(),
            deviceId = p[deviceKey] ?: "",
        )
    }

    /** A random, anonymous id for this install: used for one-vote-per-device and rate limits. Never tied to the person. */
    suspend fun deviceId(): String {
        settings.first().deviceId.takeIf { it.isNotEmpty() }?.let { return it }
        val id = "rp-" + UUID.randomUUID().toString().replace("-", "")
        context.dataStore.edit { it[deviceKey] = id }
        return id
    }

    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[urlKey] = url.trim() }
    suspend fun setVoice(on: Boolean) = context.dataStore.edit { it[voiceKey] = on }
    suspend fun setUnits(units: Units) = context.dataStore.edit { it[unitsKey] = units.name }

    suspend fun myReports(): MutableSet<String> =
        context.dataStore.data.first()[myReportsKey]?.split(',')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()

    suspend fun saveMyReports(ids: Set<String>) = context.dataStore.edit { it[myReportsKey] = ids.toList().takeLast(200).joinToString(",") }
}

/**
 * Location from Android's own LocationManager (GPS + network), not Google
 * Play Services, so it works on every Android phone, including ones sold
 * without Google services.
 */
class LocationProvider(private val context: Context) {
    private val manager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission") // callers check permission first
    fun updates(): Flow<LatLng> = callbackFlow {
        val listener = LocationListener { loc: Location -> trySend(LatLng(loc.latitude, loc.longitude)) }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { trySend(LatLng(it.latitude, it.longitude)) }
        providers.forEach { manager.requestLocationUpdates(it, 1_000L, 2f, listener, Looper.getMainLooper()) }
        awaitClose { manager.removeUpdates(listener) }
    }
}

/** Spoken alerts and directions, via the phone's built-in text-to-speech (works offline on most phones). */
class Speaker(context: Context) {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.also { it.language = Locale.getDefault() }

    fun say(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun shutdown() = tts.shutdown()
}

/** Hand-wired dependencies. */
open class AppContainer(val context: Context, val clock: Clock = Clock.systemUTC()) {
    open val settings by lazy { SettingsStore(context) }
    open val router by lazy { Router() }
    open val search by lazy { Search() }
    open val location by lazy { LocationProvider(context) }
    private val local by lazy { LocalReports(File(context.filesDir, "reports.json"), clock) }
    private var remote: Pair<String, RemoteReports>? = null

    open suspend fun reports(): ReportSource {
        val s = settings.settings.first()
        if (s.serverUrl.isBlank()) return local
        remote?.let { (url, r) -> if (url == s.serverUrl) return r }
        return RemoteReports(s.serverUrl, settings.deviceId(), settings.myReports()).also { remote = s.serverUrl to it }
    }
}
