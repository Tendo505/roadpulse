package io.github.tendo505.roadpulse.android.nav

import io.github.tendo505.roadpulse.android.data.Geo
import io.github.tendo505.roadpulse.android.data.LatLng
import io.github.tendo505.roadpulse.android.data.Report
import io.github.tendo505.roadpulse.android.data.Route
import io.github.tendo505.roadpulse.android.data.Step

/** Everything the driving screen needs, recomputed on every position fix. */
data class NavState(
    val position: LatLng,
    val snapped: LatLng,
    val bearing: Double,
    val travelledMeters: Double,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    /** The next maneuver to perform, and how far away it is. */
    val nextStep: Step?,
    val metersToNextStep: Double,
    /** The closest report ahead on the route within the alert distance. */
    val hazardAhead: Report?,
    val metersToHazard: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
)

/**
 * Turn-by-turn navigation logic, free of Android so it can be unit tested.
 *
 * Feed it position fixes with [update]. It snaps them to the route, works
 * out progress and the next maneuver, warns about reports ahead, and decides
 * when the driver is off-route (several fixes in a row far from the line, so
 * one bad GPS reading doesn't trigger a reroute) or has arrived.
 */
class Navigator(
    val route: Route,
    private val reportsOnRoute: () -> List<Report> = { emptyList() },
    private val offRouteMeters: Double = 50.0,
    private val offRouteFixes: Int = 3,
    private val arriveMeters: Double = 30.0,
    val alertMeters: Double = 600.0,
) {
    private val stepStarts: List<Double>
    private var consecutiveOff = 0
    /** Never let progress jump backwards by more than GPS noise (snapping to an earlier parallel segment). */
    private var maxTravelled = 0.0
    private val alerted = mutableSetOf<String>()

    init {
        require(route.points.size >= 2) { "route needs at least 2 points" }
        // Where each step's maneuver sits along the line.
        stepStarts = route.steps.map { Geo.matchToRoute(it.maneuver.location, route.points).alongRouteMeters }
    }

    val totalMeters: Double = Geo.length(route.points)

    fun update(position: LatLng): NavState {
        // Search near the last known progress so loops and overpasses don't snap to the wrong pass.
        val match = Geo.matchToRoute(position, route.points)
        val travelled = if (match.alongRouteMeters + 80 < maxTravelled) maxTravelled else match.alongRouteMeters
        maxTravelled = maxOf(maxTravelled, travelled)

        consecutiveOff = if (match.offRouteMeters > offRouteMeters) consecutiveOff + 1 else 0
        val remaining = (totalMeters - travelled).coerceAtLeast(0.0)
        val arrived = remaining <= arriveMeters || Geo.distance(position, route.points.last()) <= arriveMeters

        // The next maneuver is the first step whose start lies ahead of us (skipping the "depart" at 0).
        val nextIndex = stepStarts.indexOfFirst { it > travelled + 5 }
        val nextStep = if (nextIndex >= 0) route.steps[nextIndex] else route.steps.lastOrNull()
        val toNext = if (nextIndex >= 0) stepStarts[nextIndex] - travelled else remaining

        val ahead = reportsOnRoute()
            .map { it to Geo.matchToRoute(it.position, route.points).alongRouteMeters - travelled }
            .filter { (_, d) -> d in -20.0..alertMeters }
            .minByOrNull { (_, d) -> d }

        val seg = match.segment
        val bearing = Geo.bearing(route.points[seg], route.points[seg + 1])
        val speed = if (route.durationSeconds > 0) route.distanceMeters / route.durationSeconds else 13.9

        return NavState(
            position = position,
            snapped = match.snapped,
            bearing = bearing,
            travelledMeters = travelled,
            remainingMeters = remaining,
            remainingSeconds = remaining / speed,
            nextStep = nextStep,
            metersToNextStep = toNext.coerceAtLeast(0.0),
            hazardAhead = ahead?.first,
            metersToHazard = ahead?.second?.coerceAtLeast(0.0) ?: 0.0,
            offRoute = consecutiveOff >= offRouteFixes && !arrived,
            arrived = arrived,
        )
    }

    /** True the first time a given report comes into alert range, so it's announced once. */
    fun shouldAnnounce(report: Report): Boolean = alerted.add(report.id)
}

/**
 * Drives along a route at a steady speed, producing GPS-like fixes. Used for
 * the "Simulate drive" demo, so the app can be shown without being in a car.
 */
class RouteSimulator(private val route: Route, private val metersPerSecond: Double = 15.0) {
    private val total = Geo.length(route.points)
    private var travelled = 0.0

    val finished: Boolean get() = travelled >= total

    fun advance(seconds: Double): LatLng {
        travelled = (travelled + metersPerSecond * seconds).coerceAtMost(total)
        return Geo.pointAlong(route.points, travelled)
    }
}
