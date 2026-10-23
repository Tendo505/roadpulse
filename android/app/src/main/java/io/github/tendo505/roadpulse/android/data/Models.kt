package io.github.tendo505.roadpulse.android.data

import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)

enum class ReportType(val ttl: Duration) {
    POLICE(Duration.ofMinutes(60)),
    ACCIDENT(Duration.ofMinutes(120)),
    HAZARD(Duration.ofMinutes(180)),
    JAM(Duration.ofMinutes(30)),
    CLOSURE(Duration.ofHours(24)),
}

data class Report(
    val id: String,
    val type: ReportType,
    val position: LatLng,
    val note: String?,
    val confirmations: Int,
    val dismissals: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
    /** True when this device submitted it (you can't vote on your own reports). */
    val mine: Boolean = false,
)

/** A searched place. */
data class Place(val name: String, val detail: String?, val position: LatLng)

/** One routing step, kept structured so the UI can phrase it in the user's language. */
data class Maneuver(
    val type: String,        // OSRM maneuver type: depart, turn, roundabout, arrive, ...
    val modifier: String?,   // left, slight right, uturn, ...
    val road: String?,
    val exit: Int?,          // roundabout exit number
    val location: LatLng,
)

data class Step(val maneuver: Maneuver, val distanceMeters: Double, val durationSeconds: Double)

data class Route(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<Step>,
)

object Geo {
    private const val R = 6_371_008.8
    private fun rad(d: Double) = d * PI / 180

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial compass bearing from a to b, 0-360 degrees. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val y = sin(rad(b.lon - a.lon)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lon - a.lon))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun interpolate(a: LatLng, b: LatLng, t: Double) = LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    data class Match(val offRouteMeters: Double, val alongRouteMeters: Double, val segment: Int, val snapped: LatLng)

    /** Closest point on a polyline, using a local flat projection around [p] (accurate to centimetres at street scale). */
    fun matchToRoute(p: LatLng, route: List<LatLng>): Match {
        require(route.size >= 2)
        val kx = cos(rad(p.lat)) * R * PI / 180
        val ky = R * PI / 180
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestSeg = 0
        var bestSnap = route[0]
        var travelled = 0.0
        for (i in 0 until route.size - 1) {
            val a = route[i]; val b = route[i + 1]
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
            val dx = (b.lon - a.lon) * kx; val dy = (b.lat - a.lat) * ky
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            val d = sqrt(cx * cx + cy * cy)
            val segLen = distance(a, b)
            if (d < best) {
                best = d; bestAlong = travelled + t * segLen; bestSeg = i; bestSnap = interpolate(a, b, t)
            }
            travelled += segLen
        }
        return Match(best, bestAlong, bestSeg, bestSnap)
    }

    /** The point [meters] along a polyline (clamped to its ends). */
    fun pointAlong(route: List<LatLng>, meters: Double): LatLng {
        var remaining = meters.coerceAtLeast(0.0)
        for (i in 0 until route.size - 1) {
            val len = distance(route[i], route[i + 1])
            if (remaining <= len) return interpolate(route[i], route[i + 1], if (len == 0.0) 0.0 else remaining / len)
            remaining -= len
        }
        return route.last()
    }

    fun length(route: List<LatLng>) = route.zipWithNext { a, b -> distance(a, b) }.sum()
}
