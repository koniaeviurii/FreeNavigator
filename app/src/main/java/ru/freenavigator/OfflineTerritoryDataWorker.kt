package ru.freenavigator

import android.content.Context
import android.location.Location
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.min

/**
 * Durable background import of roads and searchable OSM objects for a selected Russian
 * territory. Each successful dataset/cell is checkpointed immediately; retries skip it.
 */
class OfflineTerritoryDataWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val name = inputData.getString(KEY_NAME) ?: return@withContext Result.failure()
        val south = inputData.getDouble(KEY_SOUTH, Double.NaN)
        val north = inputData.getDouble(KEY_NORTH, Double.NaN)
        val west = inputData.getDouble(KEY_WEST, Double.NaN)
        val east = inputData.getDouble(KEY_EAST, Double.NaN)
        if (!south.isFinite() || !north.isFinite() || !west.isFinite() || !east.isFinite() ||
            south >= north || west >= east) return@withContext Result.failure()

        val key = inputData.getString(KEY_PROGRESS) ?: name.hashCode().toUInt().toString(16)
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cells = split(south, north, west, east, 0.20)
        var roads = 0
        var places = 0
        var failures = 0
        var completed = 0

        for ((index, cell) in cells.withIndex()) {
            val roadDone = prefs.getBoolean("$key:$index:roads", false)
            val placesDone = prefs.getBoolean("$key:$index:places", false)
            val (s, w, n, e) = cell

            if (!roadDone) {
                try {
                    val query = "[out:json][timeout:90];way($s,$w,$n,$e)[highway~\"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service|road)$\"];out geom;"
                    val response = requestOverpass(query)
                    val elements = JSONObject(response).optJSONArray("elements")
                        ?: throw IllegalStateException("Overpass: нет списка дорог")
                    if (elements.length() > 0) {
                        val graph = OfflineRoadGraph(applicationContext)
                        try { roads += graph.mergeFromOverpassJson(response) } finally { graph.close() }
                    }
                    prefs.edit().putBoolean("$key:$index:roads", true).apply()
                } catch (error: Exception) {
                    failures++
                    android.util.Log.w(TAG, "Road cell ${index + 1}/${cells.size} failed", error)
                }
                delay(1200)
            }

            if (!placesDone) {
                try {
                    val query = "[out:json][timeout:90];(nwr($s,$w,$n,$e)[name];nwr($s,$w,$n,$e)[addr:housenumber];);out center;"
                    val response = requestOverpass(query)
                    places += mergePlaces(response)
                    prefs.edit().putBoolean("$key:$index:places", true).apply()
                } catch (error: Exception) {
                    failures++
                    android.util.Log.w(TAG, "Place cell ${index + 1}/${cells.size} failed", error)
                }
                delay(1200)
            }

            if (prefs.getBoolean("$key:$index:roads", false) &&
                prefs.getBoolean("$key:$index:places", false)) completed++
            setProgress(Data.Builder()
                .putString("territory", name)
                .putInt("completed_cells", completed)
                .putInt("total_cells", cells.size)
                .putInt("road_segments", roads)
                .putInt("places", places)
                .putInt("failed_requests", failures)
                .build())
        }

        val allDone = cells.indices.all { i ->
            prefs.getBoolean("$key:$i:roads", false) && prefs.getBoolean("$key:$i:places", false)
        }
        if (allDone) {
            prefs.edit().putString("$key:finished_name", name).apply()
            Result.success(Data.Builder().putString("territory", name)
                .putInt("total_cells", cells.size).putInt("road_segments_this_run", roads)
                .putInt("places_this_run", places).build())
        } else {
            Result.retry()
        }
    }

    private fun split(south: Double, north: Double, west: Double, east: Double, step: Double): List<Cell> {
        val result = mutableListOf<Cell>()
        var lat = south
        while (lat < north) {
            val top = min(lat + step, north)
            var lon = west
            while (lon < east) {
                val right = min(lon + step, east)
                result += Cell(lat, lon, top, right)
                lon = right
            }
            lat = top
        }
        return result
    }

    private data class Cell(val south: Double, val west: Double, val north: Double, val east: Double)

    private fun requestOverpass(query: String): String {
        val endpoints = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
        var last: Exception? = null
        for (endpoint in endpoints) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 120000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("User-Agent", "FreeNavigator/0.6.0 (Android offline maps; OpenStreetMap data)")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                val payload = "data=" + URLEncoder.encode(query, "UTF-8")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw java.io.IOException("Overpass HTTP $code: ${body.take(160)}")
                if (body.isBlank()) throw java.io.IOException("Overpass вернул пустой ответ")
                return body
            } catch (error: Exception) {
                last = error
            } finally {
                connection?.disconnect()
            }
        }
        throw java.io.IOException("Не удалось получить данные Overpass", last)
    }

    private fun mergePlaces(response: String): Int {
        val elements = JSONObject(response).optJSONArray("elements")
            ?: throw IllegalStateException("Сервер не вернул список объектов")
        val features = JSONArray()
        val categories = setOf(
            "amenity", "shop", "tourism", "leisure", "historic", "railway",
            "healthcare", "emergency", "office", "craft", "natural", "public_transport"
        )
        for (i in 0 until elements.length()) {
            val item = elements.optJSONObject(i) ?: continue
            val tags = item.optJSONObject("tags") ?: continue
            val addressLabel = listOf(tags.optString("addr:street"), tags.optString("addr:housenumber"))
                .map { it.trim() }.filter { it.isNotBlank() && it != "null" }.joinToString(" ")
            val name = sequenceOf("name:ru", "name", "official_name", "brand")
                .map { tags.optString(it).trim() }
                .firstOrNull { it.isNotBlank() && it != "null" }
                ?: addressLabel.takeIf { it.isNotBlank() } ?: continue
            val categoryKey = categories.firstOrNull { tags.has(it) }
                ?: if (tags.has("addr:housenumber")) "address" else continue
            val center = item.optJSONObject("center")
            val lat = if (item.has("lat")) item.optDouble("lat", Double.NaN)
                else center?.optDouble("lat", Double.NaN) ?: Double.NaN
            val lon = if (item.has("lon")) item.optDouble("lon", Double.NaN)
                else center?.optDouble("lon", Double.NaN) ?: Double.NaN
            if (!lat.isFinite() || !lon.isFinite()) continue
            val osmId = item.optString("type") + "/" + item.optString("id")
            features.put(JSONObject().apply {
                put("type", "Feature")
                put("id", osmId)
                put("geometry", JSONObject().apply {
                    put("type", "Point")
                    put("coordinates", JSONArray().put(lon).put(lat))
                })
                put("properties", JSONObject().apply {
                    put("osm_id", osmId)
                    put("name", name)
                    put("name:ru", tags.optString("name:ru", name))
                    put("category", if (categoryKey == "address") "address" else categoryKey + ":" + tags.optString(categoryKey))
                    put("city", tags.optString("addr:city", tags.optString("addr:suburb", "")))
                })
            })
        }
        if (features.length() == 0) return 0
        val geoJson = JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        return java.io.ByteArrayInputStream(geoJson.toByteArray(Charsets.UTF_8)).use {
            OfflinePlaceIndex(applicationContext).mergeFromGeoJson(it)
        }
    }

    companion object {
        const val KEY_NAME = "territory_name"
        const val KEY_SOUTH = "south"
        const val KEY_NORTH = "north"
        const val KEY_WEST = "west"
        const val KEY_EAST = "east"
        const val KEY_PROGRESS = "progress_key"
        private const val PREFS = "offline_territory_progress"
        private const val TAG = "OfflineTerritoryWorker"
    }
}
