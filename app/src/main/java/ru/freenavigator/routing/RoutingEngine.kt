package ru.freenavigator.routing

interface RoutingEngine {
    suspend fun calculateRoutes(request: RouteRequest): List<RouteOption>
    suspend fun isAvailable(): Boolean
}
