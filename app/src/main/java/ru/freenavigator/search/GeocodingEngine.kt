package ru.freenavigator.search

interface GeocodingEngine {
    suspend fun search(request: SearchRequest): List<SearchResult>
    suspend fun isAvailable(): Boolean
}
