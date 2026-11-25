package ru.freenavigator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import org.json.JSONObject
import java.util.PriorityQueue
import kotlin.math.*

/** Local directed road graph imported from an explicitly requested OpenStreetMap Overpass extract. */
class OfflineRoadGraph(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    data class Step(val latitude: Double, val longitude: Double, val instruction: String, val distanceMeters: Double)
    data class Route(
        val points: List<Pair<Double, Double>>,
        val distanceMeters: Double,
        val durationSeconds: Int,
        val steps: List<Step>
    )
    private data class Edge(
        val from: String, val to: String,
        val fromLat: Double, val fromLon: Double,
        val toLat: Double, val toLon: Double,
        val distance: Double, val road: String, val highway: String, val speed: Double
    )
    private data class QueueNode(val id: String, val score: Double)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE edges(
            from_id TEXT NOT NULL, to_id TEXT NOT NULL,
            from_lat REAL NOT NULL, from_lon REAL NOT NULL,
            to_lat REAL NOT NULL, to_lon REAL NOT NULL,
            distance REAL NOT NULL, road TEXT NOT NULL, highway TEXT NOT NULL,
            speed REAL NOT NULL, PRIMARY KEY(from_id,to_id))""".trimIndent())
        db.execSQL("CREATE INDEX edges_from_idx ON edges(from_id)")
        db.execSQL("CREATE INDEX edges_to_idx ON edges(to_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS edges")
        onCreate(db)
    }

    fun edgeCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM edges", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /** Replaces the local graph only after a valid road network has been parsed. */
    fun replaceFromOverpassJson(json: String): Int {
        val elements = JSONObject(json).optJSONArray("elements") ?: error("В ответе нет списка дорог")
        val parsed = ArrayList<Edge>()
        val allowed = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary",
            "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link",
            "unclassified", "residential", "living_street", "service", "road")
        for (i in 0 until elements.length()) {
            val way = elements.optJSONObject(i) ?: continue
            if (way.optString("type") != "way") continue
            val tags = way.optJSONObject("tags") ?: continue
            val highway = tags.optString("highway")
            if (highway !in allowed) continue
            if (tags.optString("access") == "private" || tags.optString("motor_vehicle") in setOf("no", "private")) continue
            val geometry = way.optJSONArray("geometry") ?: continue
            if (geometry.length() < 2) continue
            val road = tags.optString("name").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val speed = speedFor(highway, tags.optString("maxspeed"))
            val oneway = tags.optString("oneway").lowercase()
            for (p in 0 until geometry.length() - 1) {
                val a = geometry.optJSONObject(p) ?: continue
                val b = geometry.optJSONObject(p + 1) ?: continue
                val aLat = a.optDouble("lat", Double.NaN)
                val aLon = a.optDouble("lon", Double.NaN)
                val bLat = b.optDouble("lat", Double.NaN)
                val bLon = b.optDouble("lon", Double.NaN)
                if (!valid(aLat, aLon) || !valid(bLat, bLon)) continue
                val distance = distanceMeters(aLat, aLon, bLat, bLon)
                if (distance < 1.0 || distance > 5000.0) continue
                val aId = nodeId(aLat, aLon)
                val bId = nodeId(bLat, bLon)
                val forward = Edge(aId, bId, aLat, aLon, bLat, bLon, distance, road, highway, speed)
                val reverse = Edge(bId, aId, bLat, bLon, aLat, aLon, distance, road, highway, speed)
                when (oneway) {
                    "-1" -> parsed.add(reverse)
                    "yes", "1", "true" -> parsed.add(forward)
                    else -> { parsed.add(forward); parsed.add(reverse) }
                }
            }
        }
        require(parsed.size >= 20) { "В выбранной области найдено слишком мало пригодных дорог" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            parsed.forEach { edge ->
                val values = ContentValues().apply {
                    put("from_id", edge.from); put("to_id", edge.to)
                    put("from_lat", edge.fromLat); put("from_lon", edge.fromLon)
                    put("to_lat", edge.toLat); put("to_lon", edge.toLon)
                    put("distance", edge.distance); put("road", edge.road)
                    put("highway", edge.highway); put("speed", edge.speed)
                }
                db.insertWithOnConflict("edges", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return parsed.size
    }

    /** A* routing over the imported directed graph. Both endpoints must be near downloaded roads. */
    fun calculateRoute(startLat: Double, startLon: Double, endLat: Double, endLon: Double, fastest: Boolean = true): Route? {
        if (!valid(startLat, startLon) || !valid(endLat, endLon)) return null
        val adjacency = HashMap<String, MutableList<Edge>>()
        val nodes = HashMap<String, Pair<Double, Double>>()
        readableDatabase.rawQuery(
            "SELECT from_id,to_id,from_lat,from_lon,to_lat,to_lon,distance,road,highway,speed FROM edges", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val edge = Edge(
                    cursor.getString(0), cursor.getString(1), cursor.getDouble(2), cursor.getDouble(3),
                    cursor.getDouble(4), cursor.getDouble(5), cursor.getDouble(6), cursor.getString(7),
                    cursor.getString(8), cursor.getDouble(9)
                )
                adjacency.getOrPut(edge.from) { mutableListOf() }.add(edge)
                nodes[edge.from] = edge.fromLat to edge.fromLon
                nodes[edge.to] = edge.toLat to edge.toLon
            }
        }
        if (adjacency.isEmpty()) return null
        fun nearest(lat: Double, lon: Double): Pair<String, Double>? =
            nodes.entries.asSequence().map { entry ->
                entry.key to distanceMeters(lat, lon, entry.value.first, entry.value.second)
            }.minByOrNull { it.second }?.takeIf { it.second <= 1800.0 }

        val start = nearest(startLat, startLon) ?: return null
        val end = nearest(endLat, endLon) ?: return null
        val target = nodes[end.first] ?: return null
        val queue = PriorityQueue(compareBy<QueueNode> { it.score })
        val cost = HashMap<String, Double>()
        val previous = HashMap<String, Edge>()
        val closed = HashSet<String>()
        cost[start.first] = 0.0
        queue.add(QueueNode(start.first, heuristic(nodes[start.first]!!, target, fastest)))
        var visited = 0
        while (queue.isNotEmpty() && visited < 500000) {
            val current = queue.poll().id
            if (!closed.add(current)) continue
            if (current == end.first) break
            visited++
            val currentCost = cost[current] ?: continue
            for (edge in adjacency[current].orEmpty()) {
                if (edge.to in closed) continue
                val weight = if (fastest) edge.distance / edge.speed.coerceAtLeast(1.0) else edge.distance
                val candidate = currentCost + weight
                if (candidate < (cost[edge.to] ?: Double.POSITIVE_INFINITY)) {
                    cost[edge.to] = candidate
                    previous[edge.to] = edge
                    val point = nodes[edge.to] ?: continue
                    queue.add(QueueNode(edge.to, candidate + heuristic(point, target, fastest)))
                }
            }
        }
        if (start.first != end.first && end.first !in previous) return null
        val path = ArrayList<Edge>()
        var cursor = end.first
        while (cursor != start.first) {
            val edge = previous[cursor] ?: return null
            path.add(edge)
            cursor = edge.from
            if (path.size > 200000) return null
        }
        path.reverse()
        if (path.isEmpty()) return null
        val points = ArrayList<Pair<Double, Double>>()
        points.add(startLat to startLon)
        points.add(nodes[start.first]!!)
        path.forEach { points.add(it.toLat to it.toLon) }
        points.add(endLat to endLon)
        val compact = points.distinctBy { nodeId(it.first, it.second) }
        val roadDistance = path.sumOf { it.distance }
        val totalDistance = roadDistance + start.second + end.second
        val seconds = path.sumOf { (it.distance / it.speed.coerceAtLeast(1.0)).toInt() } +
            ((start.second + end.second) / 5.0).toInt()
        return Route(compact, totalDistance, seconds.coerceAtLeast(1), buildSteps(path))
    }

    private fun buildSteps(path: List<Edge>): List<Step> {
        if (path.size < 2) return emptyList()
        val result = mutableListOf<Step>()
        var accumulated = 0.0
        var previousBearing = bearing(path.first())
        for (i in 1 until path.size) {
            val edge = path[i]
            val bearing = bearing(edge)
            val delta = ((bearing - previousBearing + 540.0) % 360.0) - 180.0
            val roadChanged = edge.road.isNotBlank() && path[i - 1].road.isNotBlank() && edge.road != path[i - 1].road
            if (abs(delta) >= 35.0 || roadChanged) {
                val action = when {
                    delta > 145.0 || delta < -145.0 -> "Развернитесь"
                    delta > 35.0 -> "Поверните направо"
                    delta < -35.0 -> "Поверните налево"
                    else -> "Продолжайте движение"
                }
                val instruction = if (edge.road.isNotBlank()) "$action на ${edge.road}" else action
                result.add(Step(edge.fromLat, edge.fromLon, instruction, accumulated))
                accumulated = 0.0
            }
            accumulated += edge.distance
            previousBearing = bearing
        }
        return result
    }

    private fun bearing(edge: Edge): Double {
        val a = Math.toRadians(edge.fromLat)
        val b = Math.toRadians(edge.toLat)
        val dLon = Math.toRadians(edge.toLon - edge.fromLon)
        return (Math.toDegrees(atan2(sin(dLon) * cos(b), cos(a) * sin(b) - sin(a) * cos(b) * cos(dLon))) + 360.0) % 360.0
    }

    private fun heuristic(a: Pair<Double, Double>, b: Pair<Double, Double>, fastest: Boolean): Double {
        val distance = distanceMeters(a.first, a.second, b.first, b.second)
        return if (fastest) distance / 45.0 else distance
    }

    private fun speedFor(highway: String, maxspeed: String): Double {
        val explicit = Regex("\\d+(?:\\.\\d+)?").find(maxspeed)?.value?.toDoubleOrNull()
        if (explicit != null && explicit in 5.0..160.0) return explicit / 3.6
        val kmh = when (highway) {
            "motorway", "motorway_link" -> 100.0
            "trunk", "trunk_link" -> 80.0
            "primary", "primary_link" -> 65.0
            "secondary", "secondary_link" -> 55.0
            "tertiary", "tertiary_link" -> 45.0
            "residential", "unclassified", "road" -> 35.0
            else -> 20.0
        }
        return kmh / 3.6
    }

    private fun valid(lat: Double, lon: Double) = lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0
    private fun nodeId(lat: Double, lon: Double) = "${(lat * 1_000_000).roundToLong()}:${(lon * 1_000_000).roundToLong()}"
    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, out)
        return out[0].toDouble()
    }

    companion object {
        private const val DB_NAME = "free_navigator_roads.db"
        private const val DB_VERSION = 1
    }
}
