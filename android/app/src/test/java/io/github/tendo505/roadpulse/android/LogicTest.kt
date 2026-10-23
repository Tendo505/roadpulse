package io.github.tendo505.roadpulse.android

import io.github.tendo505.roadpulse.android.data.Geo
import io.github.tendo505.roadpulse.android.data.LatLng
import io.github.tendo505.roadpulse.android.data.LocalReports
import io.github.tendo505.roadpulse.android.data.Maneuver
import io.github.tendo505.roadpulse.android.data.OsrmResponse
import io.github.tendo505.roadpulse.android.data.PhotonResponse
import io.github.tendo505.roadpulse.android.data.Report
import io.github.tendo505.roadpulse.android.data.ReportType
import io.github.tendo505.roadpulse.android.data.Route
import io.github.tendo505.roadpulse.android.data.Step
import io.github.tendo505.roadpulse.android.data.Units
import io.github.tendo505.roadpulse.android.data.defaultUnits
import io.github.tendo505.roadpulse.android.data.json
import io.github.tendo505.roadpulse.android.data.toPlace
import io.github.tendo505.roadpulse.android.data.toRoute
import io.github.tendo505.roadpulse.android.nav.Navigator
import io.github.tendo505.roadpulse.android.nav.RouteSimulator
import io.github.tendo505.roadpulse.android.ui.formatDistance
import io.github.tendo505.roadpulse.android.ui.formatDuration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class MutableClock(var now: Instant = Instant.parse("2026-11-01T08:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

/** A straight 2 km road heading east, with a left turn halfway. */
fun testRoute(): Route {
    val a = LatLng(3.1390, 101.6869)
    val b = LatLng(3.1390, 101.6959)  // ~1 km east
    val c = LatLng(3.1480, 101.6959)  // ~1 km north
    fun step(type: String, mod: String?, at: LatLng, road: String?) = Step(Maneuver(type, mod, road, null, at), 1000.0, 60.0)
    return Route(listOf(a, b, c), 2000.0, 150.0, listOf(
        step("depart", null, a, "Jalan Sultan"),
        step("turn", "left", b, "Jalan Ampang"),
        step("arrive", null, c, null),
    ))
}

fun report(type: ReportType, at: LatLng, id: String = type.name) =
    Report(id, type, at, null, 1, 0, Instant.EPOCH, Instant.MAX)

class GeoAndFormatTest {
    @Test
    fun `distance and bearing`() {
        val a = LatLng(3.1390, 101.6869)
        assertEquals(1001.0, Geo.distance(a, LatLng(3.1390, 101.6959)), 5.0)
        assertEquals(90.0, Geo.bearing(a, LatLng(3.1390, 101.70)), 0.1)
        assertEquals(0.0, Geo.bearing(a, LatLng(3.15, 101.6869)), 0.1)
    }

    @Test
    fun `pointAlong walks the polyline and clamps`() {
        val r = testRoute().points
        val mid = Geo.pointAlong(r, 1500.0)
        assertEquals(101.6959, mid.lon, 1e-4)
        assertTrue(mid.lat > 3.1390)
        assertEquals(r.last(), Geo.pointAlong(r, 99_999.0))
        assertEquals(r.first(), Geo.pointAlong(r, -5.0))
    }

    @Test
    fun `distances are rounded the way drivers read them`() {
        assertEquals("40 m", formatDistance(37.0, Units.METRIC, Locale.US))
        assertEquals("250 m", formatDistance(262.0, Units.METRIC, Locale.US))
        assertEquals("1.4 km", formatDistance(1_380.0, Units.METRIC, Locale.US))
        assertEquals("23 km", formatDistance(22_600.0, Units.METRIC, Locale.US))
        assertEquals("500 ft", formatDistance(150.0, Units.IMPERIAL, Locale.US))
        assertEquals("1.0 mi", formatDistance(1_609.0, Units.IMPERIAL, Locale.US))
        assertEquals("1 h 5 min", formatDuration(3_900.0))
        assertEquals("1 min", formatDuration(10.0))
    }

    @Test
    fun `default units follow the country`() {
        assertEquals(Units.METRIC, defaultUnits(Locale("ms", "MY")))
        assertEquals(Units.IMPERIAL, defaultUnits(Locale.US))
        assertEquals(Units.METRIC, defaultUnits(Locale.GERMANY))
    }
}

class NavigatorTest {
    @Test
    fun `progress, next maneuver and remaining distance`() {
        val nav = Navigator(testRoute())
        val s = nav.update(LatLng(3.1390, 101.6900)) // ~345 m along the first leg
        assertEquals(345.0, s.travelledMeters, 10.0)
        assertEquals("left", s.nextStep!!.maneuver.modifier)
        assertEquals(655.0, s.metersToNextStep, 15.0)
        assertEquals(nav.totalMeters - s.travelledMeters, s.remainingMeters, 0.01)
        assertEquals(90.0, s.bearing, 1.0)
        assertFalse(s.arrived)
    }

    @Test
    fun `after the turn the next maneuver is arrival`() {
        val s = Navigator(testRoute()).update(LatLng(3.1420, 101.6959))
        assertEquals("arrive", s.nextStep!!.maneuver.type)
        assertEquals(0.0, s.bearing, 1.0)
    }

    @Test
    fun `nearest hazard ahead is reported once, and passed ones drop off`() {
        val police = report(ReportType.POLICE, LatLng(3.1391, 101.6930))   // ~670 m along, on the road
        val behind = report(ReportType.ACCIDENT, LatLng(3.1390, 101.6875)) // ~70 m along
        val nav = Navigator(testRoute(), reportsOnRoute = { listOf(police, behind) }, alertMeters = 600.0)

        val atStart = nav.update(LatLng(3.1390, 101.6869))
        assertEquals("the nearest hazard ahead wins (police is 670 m away, beyond the alert range)", ReportType.ACCIDENT, atStart.hazardAhead!!.type)
        assertEquals(70.0, atStart.metersToHazard, 10.0)
        val s = nav.update(LatLng(3.1390, 101.6895)) // ~290 m along: accident is behind, police is ~380 m ahead
        assertEquals(ReportType.POLICE, s.hazardAhead!!.type)
        assertEquals(380.0, s.metersToHazard, 20.0)
        assertTrue(nav.shouldAnnounce(s.hazardAhead!!))
        assertFalse("announce only once", nav.shouldAnnounce(s.hazardAhead!!))
    }

    @Test
    fun `one bad GPS fix is not off-route, three in a row are`() {
        val nav = Navigator(testRoute())
        val away = LatLng(3.1300, 101.6900) // ~1 km south of the road
        assertFalse(nav.update(away).offRoute)
        assertFalse(nav.update(LatLng(3.1390, 101.6900)).offRoute) // back on the road resets the count
        nav.update(away); nav.update(away)
        assertTrue(nav.update(away).offRoute)
    }

    @Test
    fun `arrival near the destination`() {
        val s = Navigator(testRoute()).update(LatLng(3.1478, 101.6959))
        assertTrue(s.arrived)
        assertFalse(s.offRoute)
    }

    @Test
    fun `simulator drives the whole route at the requested speed`() {
        val route = testRoute()
        val sim = RouteSimulator(route, metersPerSecond = 20.0)
        val nav = Navigator(route)
        val first = nav.update(sim.advance(10.0))
        assertEquals(200.0, first.travelledMeters, 5.0)
        var last = first
        while (!sim.finished) last = nav.update(sim.advance(10.0))
        assertTrue(last.arrived)
    }
}

class ParsingTest {
    @Test
    fun `OSRM response becomes a route with lat-lon order fixed`() {
        val body = """
        {"code":"Ok","routes":[{"distance":1234.5,"duration":180.2,
          "geometry":{"coordinates":[[101.6869,3.139],[101.6959,3.139],[101.6959,3.148]]},
          "legs":[{"steps":[
            {"distance":1000,"duration":90,"name":"Jalan Sultan","maneuver":{"type":"depart","location":[101.6869,3.139]}},
            {"distance":234.5,"duration":90,"name":"","maneuver":{"type":"turn","modifier":"left","location":[101.6959,3.139]}},
            {"distance":0,"duration":0,"name":"","maneuver":{"type":"arrive","location":[101.6959,3.148]}}
          ]}]}]}
        """
        val route = json.decodeFromString(OsrmResponse.serializer(), body).toRoute()
        assertEquals(LatLng(3.139, 101.6869), route.points.first())
        assertEquals(3, route.steps.size)
        assertEquals("Jalan Sultan", route.steps[0].maneuver.road)
        assertNull("blank road names become null", route.steps[1].maneuver.road)
        assertEquals("left", route.steps[1].maneuver.modifier)
        assertEquals(1234.5, route.distanceMeters, 0.0)
    }

    @Test
    fun `Photon features become readable places`() {
        val body = """
        {"features":[
          {"geometry":{"coordinates":[101.7116,3.1579]},"properties":{"name":"Suria KLCC","city":"Kuala Lumpur","country":"Malaysia"}},
          {"geometry":{"coordinates":[101.6,3.1]},"properties":{"street":"Jalan Tun Razak","housenumber":"12","country":"Malaysia"}}
        ]}
        """
        val places = json.decodeFromString(PhotonResponse.serializer(), body).features.map { it.toPlace() }
        assertEquals("Suria KLCC", places[0].name)
        assertEquals("Kuala Lumpur, Malaysia", places[0].detail)
        assertEquals(LatLng(3.1579, 101.7116), places[0].position)
        assertEquals("12 Jalan Tun Razak", places[1].name)
        assertEquals("Malaysia", places[1].detail)
    }
}

class LocalReportsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = MutableClock()
    private val here = LatLng(3.1390, 101.6869)
    private fun north(m: Double) = LatLng(here.lat + m / 111_195.0, here.lon)

    @Test
    fun `reports merge, expire and persist across restarts`() = runTest {
        val file = File(tmp.root, "reports.json")
        val store = LocalReports(file, clock)
        val a = store.submit(ReportType.ACCIDENT, here, "  left lane  ")
        val merged = store.submit(ReportType.ACCIDENT, north(90.0), null)
        assertEquals(a.id, merged.id)
        assertEquals(2, merged.confirmations)
        assertEquals("left lane", a.note)
        store.submit(ReportType.POLICE, here, null)

        val reloaded = LocalReports(file, clock)
        assertEquals(2, reloaded.inBox(3.0, 101.0, 3.3, 102.0).size)

        clock.now = clock.now.plus(Duration.ofMinutes(61)) // police (60 min) expired, accident (120 min) not
        assertEquals(listOf(ReportType.ACCIDENT), reloaded.inBox(3.0, 101.0, 3.3, 102.0).map { it.type })
    }

    @Test
    fun `gone removes, still there extends`() = runTest {
        val store = LocalReports(null, clock)
        val jam = store.submit(ReportType.JAM, here, null)
        clock.now = clock.now.plus(Duration.ofMinutes(20))
        val extended = store.vote(jam.id, stillThere = true)!!
        assertEquals(clock.now.plus(ReportType.JAM.ttl), extended.expiresAt)
        assertNull(store.vote(jam.id, stillThere = false))
        assertTrue(store.inBox(3.0, 101.0, 3.3, 102.0).isEmpty())
    }

    @Test
    fun `along-route keeps only reports near the road`() = runTest {
        val store = LocalReports(null, clock)
        store.submit(ReportType.POLICE, LatLng(3.1391, 101.6930), null)
        store.submit(ReportType.HAZARD, LatLng(3.1450, 101.6900), null) // 600 m off the road
        assertEquals(listOf(ReportType.POLICE), store.alongRoute(testRoute().points).map { it.type })
    }

    @Test
    fun `a corrupt file starts fresh instead of crashing`() = runTest {
        val file = File(tmp.root, "reports.json").apply { writeText("{not json") }
        assertTrue(LocalReports(file, clock).inBox(-90.0, -180.0, 90.0, 180.0).isEmpty())
    }
}
