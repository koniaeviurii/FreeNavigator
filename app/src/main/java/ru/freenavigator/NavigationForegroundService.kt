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
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

/** Keeps GPS guidance and Russian voice prompts alive while the map screen is backgrounded. */
class NavigationForegroundService : Service(), LocationListener, TextToSpeech.OnInitListener {
    private lateinit var locationManager: LocationManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayDeque<String>()
    private var destinationLat = Double.NaN
    private var destinationLon = Double.NaN
    private var stepLatitudes = doubleArrayOf()
    private var stepLongitudes = doubleArrayOf()
    private var instructions = emptyArray<String>()
    private var announced = Array(0) { BooleanArray(3) }
    private var previousStepDistances = doubleArrayOf()
    private var nextStep = 0
    private var arrived = false

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
        startForeground(NOTIFICATION_ID, buildNotification("Ведение по маршруту активно"))
        try {
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                stopGuidance(); stopSelf(); return START_NOT_STICKY
            }
            var requested = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 1000L, 2f, this)
                    requested = true
                }
            }
            if (!requested) {
                stopGuidance(); stopSelf(); return START_NOT_STICKY
            }
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onLocationChanged)
        } catch (_: SecurityException) {
            stopGuidance(); stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (arrived || !destinationLat.isFinite()) return
        while (nextStep < instructions.size) {
            val index = nextStep
            val distance = distanceMeters(location.latitude, location.longitude, stepLatitudes[index], stepLongitudes[index])
            val thresholds = intArrayOf(800, 300, 60)
            val previous = previousStepDistances[index]
            if (previous == Double.MAX_VALUE) {
                // On the first GPS fix, announce only the next upcoming threshold.
                // Never announce a 300 m warning while the manoeuvre is still farther than 800 m.
                when {
                    distance <= 60.0 -> {
                        speak("Сейчас. ${instructions[index]}")
                        announced[index].fill(true)
                    }
                    distance <= 300.0 -> {
                        announced[index][0] = true
                        announced[index][1] = true
                        speak("Через 60 метров. ${instructions[index]}")
                        announced[index][2] = true
                    }
                    distance <= 800.0 -> {
                        announced[index][0] = true
                        speak("Через 300 метров. ${instructions[index]}")
                        announced[index][1] = true
                    }
                }
            } else {
                for (i in thresholds.indices) {
                    if (!announced[index][i] && previous > thresholds[i] && distance <= thresholds[i]) {
                        announced[index][i] = true
                        val prefix = when (i) {
                            0 -> "Через 800 метров. "
                            1 -> "Через 300 метров. "
                            else -> "Через 60 метров. "
                        }
                        speak(prefix + instructions[index])
                    }
                }
            }
            previousStepDistances[index] = distance
            if (distance <= 25.0) nextStep++ else break
        }
        val remaining = distanceMeters(location.latitude, location.longitude, destinationLat, destinationLon)
        if (remaining <= 25.0 && !arrived) {
            arrived = true
            speak("Вы прибыли к месту назначения")
            stopGuidance()
            stopSelf()
        }
    }

    private fun speak(message: String) {
        if (!ttsReady) {
            if (pendingSpeech.size < 12) pendingSpeech.addLast(message)
            return
        }
        tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "fn-service-${System.nanoTime()}")
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        if (!aLat.isFinite() || !aLon.isFinite() || !bLat.isFinite() || !bLon.isFinite()) return Double.MAX_VALUE
        val result = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, result)
        return result[0].toDouble()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val languageStatus = tts?.setLanguage(Locale("ru", "RU")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        ttsReady = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
            languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
        if (ttsReady) {
            while (pendingSpeech.isNotEmpty()) {
                tts?.speak(pendingSpeech.removeFirst(), TextToSpeech.QUEUE_ADD, null,
                    "fn-service-${System.nanoTime()}")
            }
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

    private fun stopGuidance() {
        try { if (::locationManager.isInitialized) locationManager.removeUpdates(this) } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        stopGuidance()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

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
    }
}
