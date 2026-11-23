package ru.freenavigator.search

import kotlinx.coroutines.CancellationException

/** Coordinates online and local search. Offline engine must query an installed regional index. */
class SearchRepository(
    private val online: GeocodingEngine,
    private val offline: GeocodingEngine
) {
    suspend fun search(request: SearchRequest, mode: SearchMode = SearchMode.AUTO): List<SearchResult> {
        return when (mode) {
            SearchMode.ONLINE -> online.search(request)
            SearchMode.OFFLINE -> offline.search(request)
            SearchMode.AUTO -> {
                if (!online.isAvailable()) return offline.search(request)
                try {
                    online.search(request).ifEmpty { offline.search(request) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    offline.search(request)
                }
            }
        }
    }
}
