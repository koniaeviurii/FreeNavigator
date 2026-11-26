package ru.freenavigator.routing

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && latitude in -90.0..90.0); require(longitude.isFinite() && longitude in -180.0..180.0) }
}
enum class RoutePreference { FASTEST, SHORTEST, BALANCED }
enum class NetworkMode { AUTO, ONLINE, OFFLINE }
enum class RouteSource { ONLINE, OFFLINE }
data class RouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val preference: RoutePreference = RoutePreference.FASTEST,
    val alternatives: Boolean = true,
    val avoidTolls: Boolean = false,
    val considerTraffic: Boolean = true
)
data class RouteStep(
    val instruction: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val maneuver: String,
    val location: GeoPoint
)
data class RouteOption(
    val id: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val trafficDelaySeconds: Int?,
    val geometry: List<GeoPoint>,
    val steps: List<RouteStep>,
    val source: RouteSource
)
