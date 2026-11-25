package ru.freenavigator

import android.content.Context
import android.net.ConnectivityManager
import android.location.Location
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** Downloads nearby fixed speed-camera points from OpenStreetMap/Overpass and keeps them offline. */
class RadarUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivity.activeNetwork ?: return Result.retry()
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork) ?: return Result.retry()
        // Deliberately reject mobile data and Ethernet: radar refresh is Wi-Fi-only.
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Result.retry()

        val prefs = applicationContext.getSharedPreferences("free_navigator_settings", Context.MODE_PRIVATE)
        val lat = inputData.getDouble("center_lat", prefs.getFloat("radar_center_lat", 0f).toDouble())
        val lon = inputData.getDouble("center_lon", prefs.getFloat("radar_center_lon", 0f).toDouble())
        if (lat == 0.0 && lon == 0.0) return Result.retry()
        val query = "[out:json][timeout:25];(node(around:30000,$lat,$lon)[highway=speed_camera];node(around:30000,$lat,$lon)[enforcement=maxspeed];);out body;"
        val address = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8")
        return try {
            val connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FreeNavigator/0.5.6 (Android navigation app)")
            }
            val body = try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Overpass HTTP $code")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally { connection.disconnect() }
            val elements = JSONObject(body).optJSONArray("elements") ?: return Result.retry()
            val normalized = org.json.JSONArray()
            for (i in 0 until elements.length()) {
                val item = elements.optJSONObject(i) ?: continue
                if (!item.has("lat") || !item.has("lon")) continue
                val tags = item.optJSONObject("tags") ?: JSONObject()
                normalized.put(JSONObject().apply {
                    put("id", item.optString("id"))
                    put("lat", item.optDouble("lat"))
                    put("lon", item.optDouble("lon"))
                    put("maxspeed", tags.optString("maxspeed", ""))
                    put("name", tags.optString("name", ""))
                    put("direction", tags.optString("camera:direction", tags.optString("direction", "")))
                    put("direction_forward", tags.optString("camera:direction:forward", ""))
                    put("direction_backward", tags.optString("camera:direction:backward", ""))
                })
            }
            // Never replace the whole database with one local download: keep nearby
            // cameras from earlier areas so a journey across region boundaries does
            // not suddenly lose its warnings. New records win when IDs overlap.
            if (normalized.length() == 0) return Result.retry()
            val merged = linkedMapOf<String, JSONObject>()
            val previousRaw = prefs.getString("radar_cache", null)
            if (!previousRaw.isNullOrBlank()) {
                try {
                    val previous = org.json.JSONArray(previousRaw)
                    for (i in 0 until previous.length()) {
                        val camera = previous.optJSONObject(i) ?: continue
                        val id = camera.optString("id")
                        if (id.isBlank()) continue
                        val distance = FloatArray(1)
                        Location.distanceBetween(
                            lat, lon,
                            camera.optDouble("lat", Double.NaN),
                            camera.optDouble("lon", Double.NaN),
                            distance
                        )
                        if (distance[0].isFinite() && distance[0] <= 60000f) {
                            merged[id] = camera
                        }
                    }
                } catch (_: Exception) {
                    // A damaged old cache is ignored; the fresh download remains usable.
                }
            }
            for (i in 0 until normalized.length()) {
                val camera = normalized.optJSONObject(i) ?: continue
                val id = camera.optString("id")
                if (id.isNotBlank()) merged[id] = camera
            }
            val combined = org.json.JSONArray()
            merged.values.takeLast(5000).forEach(combined::put)
            prefs.edit()
                .putString("radar_cache", combined.toString())
                .putLong("radar_updated_at", System.currentTimeMillis())
                .putFloat("radar_center_lat", lat.toFloat())
                .putFloat("radar_center_lon", lon.toFloat())
                .apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
