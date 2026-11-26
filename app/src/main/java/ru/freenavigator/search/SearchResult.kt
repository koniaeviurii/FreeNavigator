package ru.freenavigator.search

/** A normalized result independent of the online/offline provider. */
data class SearchResult(
    val id: String,
    val title: String,
    val subtitle: String?,
    val latitude: Double,
    val longitude: Double,
    val kind: SearchResultKind,
    val source: SearchSource
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(title.isNotBlank())
    }
}

enum class SearchResultKind { ADDRESS, STREET, CITY, POI, OTHER }
enum class SearchSource { ONLINE, OFFLINE }
