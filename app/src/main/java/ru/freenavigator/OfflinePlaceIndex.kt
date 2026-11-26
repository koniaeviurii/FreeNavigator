package ru.freenavigator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.InputStream

/**
 * Local searchable POI index. Import a GeoJSON FeatureCollection containing Point features.
 * Coordinates follow GeoJSON order: [longitude, latitude].
 */
class OfflinePlaceIndex(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    data class Place(
        val id: String,
        val name: String,
        val category: String,
        val city: String,
        val latitude: Double,
        val longitude: Double
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE places (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                city TEXT NOT NULL DEFAULT '',
                latitude REAL NOT NULL,
                longitude REAL NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX places_name_idx ON places(name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX places_city_idx ON places(city COLLATE NOCASE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive migration is needed for the current schema.
    }

    /** Replaces the active local region atomically; a bad file leaves the previous index intact. */
    fun replaceFromGeoJson(input: InputStream): Int = importFromGeoJson(input, true)

    /** Adds or updates imported places without deleting previously saved territories. */
    fun mergeFromGeoJson(input: InputStream): Int = importFromGeoJson(input, false)

    private fun importFromGeoJson(input: InputStream, replaceExisting: Boolean): Int {
        val root = JSONObject(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(root.optString("type") == "FeatureCollection") {
            "Ожидается GeoJSON FeatureCollection"
        }
        val features = root.optJSONArray("features") ?: error("В файле нет списка объектов")
        val parsed = ArrayList<Place>(features.length())
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") != "Point") continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 2) continue
            val lon = coordinates.optDouble(0, Double.NaN)
            val lat = coordinates.optDouble(1, Double.NaN)
            if (!lat.isFinite() || lat !in -90.0..90.0 ||
                !lon.isFinite() || lon !in -180.0..180.0) continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val name = firstValue(properties, "name:ru", "name", "official_name", "brand")
                ?: continue
            val category = firstValue(
                properties, "category", "type", "amenity", "shop", "tourism",
                "leisure", "office", "craft", "historic"
            ).orEmpty()
            val city = firstValue(properties, "city", "town", "village", "municipality").orEmpty()
            val id = feature.optString("id").takeIf { it.isNotBlank() && it != "null" }
                ?: properties.optString("osm_id").takeIf { it.isNotBlank() && it != "null" }
                ?: "feature-$i"
            parsed.add(Place(id, name, category, city, lat, lon))
        }
        require(parsed.isNotEmpty()) { "В GeoJSON не найдено точек с названиями" }

        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceExisting) db.delete(TABLE, null, null)
            parsed.forEach { place ->
                val values = ContentValues().apply {
                    put("id", place.id)
                    put("name", place.name)
                    put("category", place.category)
                    put("city", place.city)
                    put("latitude", place.latitude)
                    put("longitude", place.longitude)
                }
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return parsed.size
    }

    fun search(query: String, limit: Int = 8): List<Place> {
        val cleaned = query.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length < 2) return emptyList()
        val tokens = cleaned.split(' ').filter { it.isNotBlank() }.take(5)
        // instr() treats user input literally and avoids SQL LIKE wildcard semantics.
        val clauses = tokens.map { "(instr(lower(name), lower(?)) > 0 OR instr(lower(category), lower(?)) > 0 OR instr(lower(city), lower(?)) > 0)" }
        val args = tokens.flatMap { token -> listOf(token, token, token) }.toMutableList()
        val prefix = cleaned
        val results = mutableListOf<Place>()
        readableDatabase.rawQuery(
            "SELECT id, name, category, city, latitude, longitude FROM $TABLE WHERE ${clauses.joinToString(" AND ")} ORDER BY CASE WHEN lower(substr(name,1,length(?))) = lower(?) THEN 0 ELSE 1 END, name COLLATE NOCASE LIMIT ?",
            (args + listOf(prefix, prefix, limit.coerceIn(1, 50).toString())).toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    Place(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getDouble(4), cursor.getDouble(5)
                    )
                )
            }
        }
        return results
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    private fun firstValue(json: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            json.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
        }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        private const val DB_NAME = "free_navigator_places.db"
        private const val DB_VERSION = 1
        private const val TABLE = "places"
    }
}
