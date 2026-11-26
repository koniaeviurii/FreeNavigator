package ru.freenavigator

import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/** Parser for the public Russia CSV snapshot published by SpeedCams.world. */
object RadarCsvParser {
    fun parse(input: InputStream): JSONArray =
        parse(input.bufferedReader(Charsets.UTF_8).use { it.readText() })

    fun parse(csv: String): JSONArray {
        val rows = parseRows(csv.removePrefix("\uFEFF"))
        if (rows.isEmpty()) throw IllegalArgumentException("CSV database is empty")
        val header = rows.first().map { it.trim().lowercase() }
        fun column(name: String): Int = header.indexOf(name)
        val idIndex = column("id")
        val latIndex = column("latitude")
        val lonIndex = column("longitude")
        val nameIndex = column("name")
        val typeIndex = column("type")
        val speedIndex = column("maxspeed")
        val sourceIndex = column("source")
        if (latIndex < 0 || lonIndex < 0) throw IllegalArgumentException("CSV is missing coordinate columns")
        val result = JSONArray()
        val seen = HashSet<String>()
        for (row in rows.drop(1)) {
            val lat = row.getOrNull(latIndex)?.toDoubleOrNull() ?: continue
            val lon = row.getOrNull(lonIndex)?.toDoubleOrNull() ?: continue
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val id = row.getOrNull(idIndex)?.trim().orEmpty().ifBlank { "csv:$lat:$lon" }
            if (!seen.add(id)) continue
            result.put(JSONObject().apply {
                put("id", id)
                put("lat", lat)
                put("lon", lon)
                put("name", row.getOrNull(nameIndex).orEmpty())
                put("type", row.getOrNull(typeIndex).orEmpty())
                put("maxspeed", row.getOrNull(speedIndex).orEmpty())
                put("direction", "")
                put("direction_forward", "")
                put("direction_backward", "")
                put("source", row.getOrNull(sourceIndex).orEmpty())
            })
        }
        if (result.length() < 1000) throw IllegalArgumentException("CSV contains too few valid camera coordinates")
        return result
    }

    private fun parseRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    row.add(field.toString())
                    field.setLength(0)
                }
                (ch == '\n' || ch == '\r') && !quoted -> {
                    if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row.add(field.toString())
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = ArrayList()
                }
                else -> field.append(ch)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }
}
