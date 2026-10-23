package io.github.tendo505.roadpulse

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 coordinate in degrees. */
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "latitude must be between -90 and 90" }
        require(lon in -180.0..180.0) { "longitude must be between -180 and 180" }
    }
}

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    private fun rad(deg: Double) = deg * PI / 180.0

    /** Great-circle distance in metres (haversine formula). */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Degrees of latitude / longitude spanning [meters] around [lat], for building a search box. */
    fun metersToDegrees(meters: Double, lat: Double): Pair<Double, Double> {
        val dLat = meters / (EARTH_RADIUS_M * PI / 180.0)
        val dLon = dLat / cos(rad(lat)).coerceAtLeast(0.01)
        return dLat to dLon
    }

    /** Where a point sits relative to a route polyline. */
    data class RouteMatch(
        /** Shortest distance from the point to the route, in metres. */
        val offRouteMeters: Double,
        /** Distance travelled along the route to the closest point, in metres. */
        val alongRouteMeters: Double,
    )

    /**
     * Project [p] onto the polyline [route]. Each segment is handled in a local
     * flat (equirectangular) projection centred on the point. Over the few
     * hundred metres that matter here the error is centimetres, and it is
     * orders of magnitude cheaper than spherical geometry per segment.
     */
    fun matchToRoute(p: LatLon, route: List<LatLon>): RouteMatch {
        require(route.size >= 2) { "a route needs at least 2 points" }
        val kx = cos(rad(p.lat)) * EARTH_RADIUS_M * PI / 180.0 // metres per degree of longitude here
        val ky = EARTH_RADIUS_M * PI / 180.0                   // metres per degree of latitude
        fun x(q: LatLon) = (q.lon - p.lon) * kx
        fun y(q: LatLon) = (q.lat - p.lat) * ky

        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        var travelled = 0.0
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            val ax = x(a); val ay = y(a)
            val dx = x(b) - ax; val dy = y(b) - ay
            val segLen2 = dx * dx + dy * dy
            // Parameter of the closest point on segment AB to the origin (our point), clamped to the segment.
            val t = if (segLen2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy).div(segLen2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val d = sqrt(cx * cx + cy * cy)
            val segLen = distance(a, b)
            if (d < best) {
                best = d
                bestAlong = travelled + t * segLen
            }
            travelled += segLen
        }
        return RouteMatch(best, bestAlong)
    }

    fun routeLength(route: List<LatLon>): Double = route.zipWithNext { a, b -> distance(a, b) }.sum()
}
