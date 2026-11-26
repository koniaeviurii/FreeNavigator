package ru.freenavigator

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** Downloads fixed speed-camera objects from Overpass and merges them into the persistent local cache. */
class RadarUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private fun downloadNationalDatabase(): org.json.JSONArray {
        val connection = (URL("https://speedcams.world/downloads/ru/ru-all.csv").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 90000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "FreeNavigator/0.6.0 (Android navigation app; OSM camera data)")
            setRequestProperty("Accept", "text/csv")
        }
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("Camera database HTTP ${connection.responseCode}")
            return connection.inputStream.use { RadarCsvParser.parse(it) }
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun doWork() {
        val prefs = applicationContext.getSharedPreferences("free_navigator_settings", Context.MODE_PRIVATE)
        fun fail(reason: String): Result {
            prefs.edit().putString("radar_last_error", reason).apply()
            return Result.retry()
        }
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivity.activeNetwork ?: return fail("Нет подключения к интернету")
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork) ?: return fail("Нет данных о подключении")
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return fail("Интернет недоступен")

        // Prefer a complete country snapshot over a GPS-centred fragment. The CSV is
        // bundled in the APK and refreshed from its public source on explicit/daily updates.
        val national = try { downloadNationalDatabase() } catch (error: Exception) {
            android.util.Log.w("FreeNavigator", "National radar refresh failed; falling back to nearby OSM", error)
            null
        }
        if (national != null && national.length() >= 1000) {
            prefs.edit()
                .putString("radar_cache", national.toString())
                .putLong("radar_updated_at", System.currentTimeMillis())
                .putString("radar_source_date", "21.09.2026")
                .remove("radar_last_error")
                .putString("radar_last_result", "Обновлена база России: ${national.length()} камер · OpenStreetMap")
                .apply()
            return Result.success()
        }

        val lat = inputData.getDouble("center_lat", prefs.getFloat("radar_center_lat", 0f).toDouble())
        val lon = inputData.getDouble("center_lon", prefs.getFloat("radar_center_lon", 0f).toDouble())
        if (!lat.isFinite() || !lon.isFinite() || (lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0)
            return fail("Национальная база недоступна, GPS-координаты для резервной загрузки не получены")

        // Fallback: if the national download is temporarily unavailable, refresh
        // the 30 km area around the current location without discarding the cache.
        val query = "[out:json][timeout:50];(nwr(around:30000,$lat,$lon)[highway=speed_camera];nwr(around:30000,$lat,$lon)[enforcement=maxspeed];);out center;"
        val endpoints = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
        var body: String? = null
        var lastFailure = "Серверы базы камер временно недоступны"
        for (endpoint in endpoints) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 65000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("User-Agent", "FreeNavigator/0.6.0 (Android navigation app)")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                val payload = "data=" + URLEncoder.encode(query, "UTF-8")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    lastFailure = "Сервер камер ответил: HTTP $code"
                    continue
                }
                body = connection.inputStream.bufferedReader().use { it.readText() }
                if (!body.isNullOrBlank()) break
                lastFailure = "Сервер вернул пустой ответ"
            } catch (e: Exception) {
                lastFailure = when (e) {
                    is java.net.SocketTimeoutException -> "Истекло время ожидания сервера камер"
                    else -> "Ошибка связи с сервером камер: ${e.message ?: "сеть"}"
                }
            } finally {
                connection?.disconnect()
            }
        }
        val response = body ?: return fail(lastFailure)
        return try {
            val elements = JSONObject(response).optJSONArray("elements") ?: return fail("Сервер прислал данные без списка камер")
            val normalized = org.json.JSONArray()
            for (i in 0 until elements.length()) {
                val item = elements.optJSONObject(i) ?: continue
                val center = item.optJSONObject("center")
                val cameraLat = if (item.has("lat")) item.optDouble("lat", Double.NaN) else center?.optDouble("lat", Double.NaN) ?: Double.NaN
                val cameraLon = if (item.has("lon")) item.optDouble("lon", Double.NaN) else center?.optDouble("lon", Double.NaN) ?: Double.NaN
                if (!cameraLat.isFinite() || !cameraLon.isFinite()) continue
                val tags = item.optJSONObject("tags") ?: JSONObject()
                normalized.put(JSONObject().apply {
                    put("id", item.optString("type", "osm") + "/" + item.optString("id"))
                    put("lat", cameraLat)
                    put("lon", cameraLon)
                    put("maxspeed", tags.optString("maxspeed", ""))
                    put("name", tags.optString("name", ""))
                    put("direction", tags.optString("camera:direction", tags.optString("direction", "")))
                    put("direction_forward", tags.optString("camera:direction:forward", ""))
                    put("direction_backward", tags.optString("camera:direction:backward", ""))
                })
            }

            // Keep previously downloaded areas. An empty first response is not a usable
            // initial database: leave the update timestamp untouched so WorkManager retries.
            val merged = linkedMapOf<String, JSONObject>()
            val previousRaw = prefs.getString("radar_cache", null)
            if (!previousRaw.isNullOrBlank()) {
                try {
                    val previous = org.json.JSONArray(previousRaw)
                    for (i in 0 until previous.length()) {
                        val camera = previous.optJSONObject(i) ?: continue
                        val id = camera.optString("id")
                        if (id.isNotBlank()) merged[id] = camera
                    }
                } catch (_: Exception) { }
            }
            if (normalized.length() == 0 && merged.isEmpty()) {
                return fail("Сервер не нашёл камер в этой области. Повторим загрузку позже")
            }
            for (i in 0 until normalized.length()) {
                val camera = normalized.optJSONObject(i) ?: continue
                val id = camera.optString("id")
                if (id.isNotBlank()) merged[id] = camera
            }
            val combined = org.json.JSONArray()
            merged.values.toList().takeLast(20000).forEach { combined.put(it) }
            prefs.edit()
                .putString("radar_cache", combined.toString())
                .putLong("radar_updated_at", System.currentTimeMillis())
                .putFloat("radar_center_lat", lat.toFloat())
                .putFloat("radar_center_lon", lon.toFloat())
                .remove("radar_last_error")
                .putString("radar_last_result", if (normalized.length() == 0) "Новых камер не найдено; сохранена ранее загруженная база" else "Получено камер: ${normalized.length()}")
                .apply()
            Result.success()
        } catch (e: Exception) {
            fail("Не удалось обработать базу камер: ${e.message ?: "ошибка данных"}")
        }
    }
}
