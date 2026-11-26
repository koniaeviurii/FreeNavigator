package ru.freenavigator.routing

import kotlinx.coroutines.CancellationException

data class RouteCalculation(val routes: List<RouteOption>, val usedSource: RouteSource)

class RouteRepository(private val online: RoutingEngine, private val offline: RoutingEngine) {
    suspend fun calculate(request: RouteRequest, mode: NetworkMode): RouteCalculation {
        return when (mode) {
            NetworkMode.ONLINE -> RouteCalculation(online.calculateRoutes(request), RouteSource.ONLINE)
            NetworkMode.OFFLINE -> RouteCalculation(offline.calculateRoutes(request), RouteSource.OFFLINE)
            NetworkMode.AUTO -> {
                if (online.isAvailable()) {
                    try {
                        val routes = online.calculateRoutes(request)
                        if (routes.isNotEmpty()) RouteCalculation(routes, RouteSource.ONLINE)
                        else RouteCalculation(offline.calculateRoutes(request), RouteSource.OFFLINE)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        RouteCalculation(offline.calculateRoutes(request), RouteSource.OFFLINE)
                    }
                } else RouteCalculation(offline.calculateRoutes(request), RouteSource.OFFLINE)
            }
        }
    }
}
