package ru.freenavigator.search

data class SearchRequest(
    val query: String,
    val nearLatitude: Double? = null,
    val nearLongitude: Double? = null,
    val limit: Int = 10
) {
    init {
        require(query.trim().length >= 2) { "Введите не менее двух символов" }
        require(limit in 1..50)
        require((nearLatitude == null) == (nearLongitude == null))
        nearLatitude?.let { require(it.isFinite() && it in -90.0..90.0) }
        nearLongitude?.let { require(it.isFinite() && it in -180.0..180.0) }
    }
}

enum class SearchMode { AUTO, ONLINE, OFFLINE }
