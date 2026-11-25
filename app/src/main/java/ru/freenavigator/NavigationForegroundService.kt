package ru.freenavigator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.roundToInt

/** Keeps GPS guidance and Russian voice prompts alive while the map screen is backgrounded. */
class NavigationForegroundService : Service(), LocationListener, TextToSpeech.OnInitListener {
    private data class PendingSpeech(val text: String, val utteranceId: String)

    private lateinit var locationManager: LocationManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayDeque<PendingSpeech>()
    private var destinationLat = Double.NaN
    private var destinationLon = Double.NaN
    private var stepLatitudes = doubleArrayOf()
    private var stepLongitudes = doubleArrayOf()
    private var instructions = emptyArray<String>()
    private var announced = Array(0) { BooleanArray(3) }
    private var previousStepDistances = doubleArrayOf()
    private var nextStep = 0
    private var arrived = false
    private val radarWarningStages = mutableMapOf<String, Int>()
    private val requestedProviders = mutableSetOf<String>()
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastFixAt = 0L
    private var lastProviderRetryAt = 0L
    private var waitingForGps = false
    private val gpsWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (!arrived && lastFixAt > 0L && now - lastFixAt > 15000L) {
                if (!waitingForGps) {
                    waitingForGps = true
                    updateNotification("Сигнал GPS потерян · маршрут сохранён, ищем сигнал")
                    speak("Сигнал GPS потерян. Маршрут сохранён, продолжаю поиск сигнала.")
                }
                // Re-register location providers periodically. The route and next
                // maneuver state are deliberately kept intact while GPS is unavailable.
                if (now - lastProviderRetryAt >= 15000L) {
                    lastProviderRetryAt = now
                    removeLocationUpdates()
                    requestAvailableProviders()
                    try {
                        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                            .filter { locationManager.isProviderEnabled(it) }
                            .mapNotNull { locationManager.getLastKnownLocation(it) }
                            .maxByOrNull { it.time }
                            ?.takeIf { System.currentTimeMillis() - it.time <= 30000L }
                            ?.let(::onLocationChanged)
                    } catch (_: SecurityException) { }
                }
            }
            watchdogHandler.postDelayed(this, 5000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopGuidance()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        destinationLat = intent.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN)
        destinationLon = intent.getDoubleExtra(EXTRA_DEST_LON, Double.NaN)
        stepLatitudes = intent.getDoubleArrayExtra(EXTRA_STEP_LATS) ?: doubleArrayOf()
        stepLongitudes = intent.getDoubleArrayExtra(EXTRA_STEP_LONS) ?: doubleArrayOf()
        removeLocationUpdates()
        requestedProviders.clear()
        instructions = intent.getStringArrayExtra(EXTRA_INSTRUCTIONS) ?: emptyArray()
        val count = minOf(stepLatitudes.size, stepLongitudes.size, instructions.size)
        if (!destinationLat.isFinite() || !destinationLon.isFinite()) {
            stopSelf()
            return START_NOT_STICKY
        }
        stepLatitudes = stepLatitudes.take(count).toDoubleArray()
        stepLongitudes = stepLongitudes.take(count).toDoubleArray()
        instructions = instructions.take(count).toTypedArray()
        announced = Array(count) { BooleanArray(3) }
        previousStepDistances = DoubleArray(count) { Double.MAX_VALUE }
        nextStep = 0
        arrived = false
        pendingSpeech.clear()
        startForeground(NOTIFICATION_ID, buildNotification("Ведение по маршруту активно"))
        try {
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                stopGuidance(); stopSelf(); return START_NOT_STICKY
            }
            lastFixAt = System.currentTimeMillis()
            waitingForGps = false
            requestAvailableProviders()
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onLocationChanged)
            watchdogHandler.removeCallbacks(gpsWatchdog)
            watchdogHandler.postDelayed(gpsWatchdog, 5000L)
        } catch (_: SecurityException) {
            stopGuidance(); stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (arrived || !destinationLat.isFinite()) return
        lastFixAt = System.currentTimeMillis()
        lastProviderRetryAt = lastFixAt
        if (waitingForGps) {
            waitingForGps = false
            updateNotification("GPS восстановлен · ведение активно")
            speak("Сигнал GPS восстановлен")
        }
        checkRadarProximity(location)
        while (nextStep < instructions.size) {
            val index = nextStep
            val distance = distanceMeters(location.latitude, location.longitude, stepLatitudes[index], stepLongitudes[index])
            val thresholds = intArrayOf(800, 300, 60)
            val previous = previousStepDistances[index]
            if (previous == Double.MAX_VALUE) {
                // The first usable GPS fix may already be inside a warning band.
                // Give one truthful distance prompt instead of silently skipping it.
                if (distance <= 60.0) {
                    announced[index].fill(true)
                    speak("Сейчас. ${instructions[index]}")
                } else if (distance <= 800.0) {
                    announced[index][0] = true
                    if (distance <= 300.0) announced[index][1] = true
                    val firstPromptMeters = ((distance / 50.0).roundToInt() * 50).coerceAtLeast(50)
                    speak("Через $firstPromptMeters метров. ${instructions[index]}")
                }
            } else {
                val crossed = thresholds.indices.filter {
                    !announced[index][it] && previous > thresholds[it] && distance <= thresholds[it]
                }
                if (crossed.isNotEmpty()) {
                    // GPS can jump across several thresholds in one update. Mark them all,
                    // but announce only the closest applicable warning to avoid a burst.
                    crossed.forEach { announced[index][it] = true }
                    val nearest = crossed.last()
                    val prefix = when (nearest) {
                        0 -> "Через 800 метров. "
                        1 -> "Через 300 метров. "
                        else -> "Сейчас. "
                    }
                    speak(prefix + instructions[index])
                }
            }
            previousStepDistances[index] = distance
            if (distance <= 25.0) nextStep++ else break
        }

        val remaining = distanceMeters(location.latitude, location.longitude, destinationLat, destinationLon)
        if (remaining <= 25.0 && !arrived) {
            arrived = true
            removeLocationUpdates()
            speak("Вы прибыли к месту назначения", ARRIVAL_UTTERANCE_ID)
        }
    }

    /** Multi-stage radar warnings continue while navigation runs in the foreground. */
    private fun checkRadarProximity(location: Location) {
        val prefs = getSharedPreferences("free_navigator_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("radar_enabled", true)) {
            radarWarningStages.clear()
            return
        }
        val raw = prefs.getString("radar_cache", null) ?: return
        try {
            val cameras = org.json.JSONArray(raw)
            data class RadarEvent(
                val id: String,
                val distance: Double,
                val stage: Int,
                val message: String
            )
            val events = mutableListOf<RadarEvent>()
            for (i in 0 until cameras.length()) {
                val camera = cameras.optJSONObject(i) ?: continue
                val id = camera.optString("id")
                if (id.isBlank()) continue
                // OSM camera direction is a compass bearing in degrees. When the
                // GPS fix has a reliable course, ignore cameras aimed clearly away
                // from the vehicle's travel direction. Unknown/text directions remain
                // eligible rather than silently suppressing a potentially useful alert.
                val direction = sequenceOf("camera:direction", "direction")
                    .mapNotNull { key -> camera.optString(key).toDoubleOrNull() }
                    .firstOrNull { it.isFinite() && it in 0.0..360.0 }
                if (direction != null && location.hasBearing() && location.speed >= 3.0f) {
                    // OSM camera:direction describes where the camera faces;
                    // approaching traffic normally travels in the opposite heading.
                    val approachHeading = (direction + 180.0) % 360.0
                    val difference = kotlin.math.abs(((location.bearing - approachHeading + 540f) % 360f) - 180f)
                    if (difference > 75f) continue
                }
                val distance = distanceMeters(
                    location.latitude, location.longitude,
                    camera.optDouble("lat", Double.NaN), camera.optDouble("lon", Double.NaN)
                )
                if (distance > 2500.0) {
                    radarWarningStages.remove(id)
                    continue
                }
                val currentStage = radarWarningStages[id] ?: 0
                val limit = camera.optString("maxspeed")
                    .takeIf { it.isNotBlank() }
                    ?.let { " Ограничение скорости $it." }
                    .orEmpty()
                val roundedDistance = ((distance / 50.0).roundToInt() * 50).coerceAtLeast(50)
                val event = when {
                    currentStage == 0 && distance <= 800.0 -> {
                        val stage = when {
                            distance <= 60.0 -> 3
                            distance <= 300.0 -> 2
                            else -> 1
                        }
                        val warning = if (stage == 3) {
                            "Камера контроля скорости рядом.$limit"
                        } else {
                            "Через $roundedDistance метров камера контроля скорости.$limit"
                        }
                        RadarEvent(id, distance, stage, warning)
                    }
                    currentStage == 1 && distance <= 60.0 ->
                        RadarEvent(id, distance, 3, "Камера контроля скорости рядом.$limit")
                    currentStage == 1 && distance <= 300.0 ->
                        RadarEvent(id, distance, 2, "Через $roundedDistance метров камера контроля скорости.$limit")
                    currentStage == 2 && distance <= 60.0 ->
                        RadarEvent(id, distance, 3, "Камера контроля скорости рядом.$limit")
                    else -> null
                }
                if (event != null) events.add(event)
            }
            // Announce only one camera per GPS update. If GPS jumps across several
            // distance bands, use the nearest applicable phrase instead of a burst.
            val selected = events.minByOrNull { it.distance }
            if (selected != null) {
                radarWarningStages[selected.id] = selected.stage
                speak(selected.message)
            }
        } catch (_: Exception) {
            // A malformed cache must not interrupt route guidance.
        }
    }

    private fun speak(message: String, utteranceId: String = "fn-service-${System.nanoTime()}") {
        if (!ttsReady) {
            if (pendingSpeech.size < 12) pendingSpeech.addLast(PendingSpeech(message, utteranceId))
            return
        }
        tts?.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        if (!aLat.isFinite() || !aLon.isFinite() || !bLat.isFinite() || !bLon.isFinite()) return Double.MAX_VALUE
        val result = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, result)
        return result[0].toDouble()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            if (arrived) stopSelf()
            return
        }
        val languageStatus = tts?.setLanguage(Locale("ru", "RU")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        ttsReady = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
            languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsReady) {
            if (arrived) stopSelf()
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == ARRIVAL_UTTERANCE_ID) stopSelf()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == ARRIVAL_UTTERANCE_ID) stopSelf()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == ARRIVAL_UTTERANCE_ID) stopSelf()
            }
        })
        while (pendingSpeech.isNotEmpty()) {
            val item = pendingSpeech.removeFirst()
            tts?.speak(item.text, TextToSpeech.QUEUE_ADD, null, item.utteranceId)
        }
    }

    private fun buildNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_directions)
        .setContentTitle("FreeNavigator")
        .setContentText(text)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Навигация", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun removeLocationUpdates() {
        try {
            if (::locationManager.isInitialized) locationManager.removeUpdates(this)
        } catch (_: Exception) { }
        requestedProviders.clear()
    }

    private fun stopGuidance() {
        removeLocationUpdates()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(gpsWatchdog)
        stopGuidance()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    private fun requestAvailableProviders() {
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locationManager.isProviderEnabled(provider) && provider !in requestedProviders) {
                    locationManager.requestLocationUpdates(provider, 1000L, 2f, this)
                    requestedProviders.add(provider)
                }
            }
        } catch (_: SecurityException) {
            speak("Нет разрешения на получение координат GPS")
        } catch (_: Exception) { }
    }

    private fun updateNotification(message: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(message))
        } catch (_: Exception) { }
    }

    override fun onProviderEnabled(provider: String) {
        requestAvailableProviders()
    }

    override fun onProviderDisabled(provider: String) {
        val anyEnabled = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .any { locationManager.isProviderEnabled(it) }
        } catch (_: Exception) { false }
        if (!anyEnabled && !waitingForGps) {
            waitingForGps = true
            updateNotification("GPS выключен · включите местоположение")
            speak("GPS выключен. Включите местоположение для продолжения маршрута.")
        }
    }

    companion object {
        const val ACTION_START = "ru.freenavigator.action.START_NAVIGATION"
        const val ACTION_STOP = "ru.freenavigator.action.STOP_NAVIGATION"
        const val EXTRA_DEST_LAT = "destination_lat"
        const val EXTRA_DEST_LON = "destination_lon"
        const val EXTRA_STEP_LATS = "step_latitudes"
        const val EXTRA_STEP_LONS = "step_longitudes"
        const val EXTRA_INSTRUCTIONS = "step_instructions"
        private const val CHANNEL_ID = "free_navigator_navigation"
        private const val NOTIFICATION_ID = 4101
        private const val ARRIVAL_UTTERANCE_ID = "fn-arrival"
    }
}
