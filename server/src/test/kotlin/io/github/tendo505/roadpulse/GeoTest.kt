package io.github.tendo505.roadpulse

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    private val klcc = LatLon(3.1579, 101.7116)
    private val merdeka118 = LatLon(3.1415, 101.7003)

    @Test
    fun `haversine matches known distances`() {
        // KLCC to Merdeka 118 is about 2.2 km.
        assertEquals(2_200.0, Geo.distance(klcc, merdeka118), 50.0)
        // One degree of latitude is about 111.2 km everywhere.
        assertEquals(111_195.0, Geo.distance(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 5.0)
        assertEquals(0.0, Geo.distance(klcc, klcc), 1e-9)
    }

    @Test
    fun `distance is symmetric and handles the antimeridian`() {
        val a = LatLon(10.0, 179.9)
        val b = LatLon(10.0, -179.9)
        assertEquals(Geo.distance(a, b), Geo.distance(b, a), 1e-9)
        assertTrue(Geo.distance(a, b) < 25_000, "points 0.2 degrees apart across the antimeridian are close")
    }

    @Test
    fun `point on a straight route is matched with the right along-distance`() {
        val route = listOf(LatLon(3.0, 101.0), LatLon(3.0, 101.01)) // ~1.1 km east-west
        val halfway = LatLon(3.0, 101.005)
        val m = Geo.matchToRoute(halfway, route)
        assertEquals(0.0, m.offRouteMeters, 0.5)
        assertEquals(Geo.routeLength(route) / 2, m.alongRouteMeters, 1.0)
    }

    @Test
    fun `offset point reports its perpendicular distance`() {
        val route = listOf(LatLon(3.0, 101.0), LatLon(3.0, 101.01))
        val north = LatLon(3.0 + 50.0 / 111_195, 101.003) // 50 m north of the road
        assertEquals(50.0, Geo.matchToRoute(north, route).offRouteMeters, 0.5)
    }

    @Test
    fun `multi-segment route accumulates distance across corners`() {
        val a = LatLon(3.0, 101.0)
        val b = LatLon(3.0, 101.01)
        val c = LatLon(3.01, 101.01)
        val onSecondLeg = LatLon(3.005, 101.01)
        val m = Geo.matchToRoute(onSecondLeg, listOf(a, b, c))
        assertEquals(Geo.distance(a, b) + Geo.distance(b, onSecondLeg), m.alongRouteMeters, 1.0)
        assertEquals(0.0, m.offRouteMeters, 0.5)
    }

    @Test
    fun `points beyond the ends clamp to the endpoints`() {
        val route = listOf(LatLon(3.0, 101.0), LatLon(3.0, 101.01))
        val before = Geo.matchToRoute(LatLon(3.0, 100.999), route)
        assertEquals(0.0, before.alongRouteMeters, 0.01)
        assertEquals(111.0, before.offRouteMeters, 2.0)
    }

    @Test
    fun `invalid coordinates are rejected`() {
        assertThrows<IllegalArgumentException> { LatLon(91.0, 0.0) }
        assertThrows<IllegalArgumentException> { LatLon(0.0, 181.0) }
        assertThrows<IllegalArgumentException> { Geo.matchToRoute(klcc, listOf(klcc)) }
    }
}

class DatabaseUrlTest {
    @Test
    fun `platform DATABASE_URL becomes JDBC properties`() {
        val p = jdbcPropertiesFrom("postgres://road:p%40ss@db.example.com:6543/roadpulse?sslmode=require")
        assertEquals("jdbc:postgresql://db.example.com:6543/roadpulse?sslmode=require", p["spring.datasource.url"])
        assertEquals("road", p["spring.datasource.username"])
        assertEquals("p@ss", p["spring.datasource.password"])
        assertEquals("jdbc:postgresql://h:5432/d", jdbcPropertiesFrom("postgresql://u:x@h/d")["spring.datasource.url"])
        assertTrue(jdbcPropertiesFrom(null).isEmpty())
        assertTrue(jdbcPropertiesFrom("jdbc:h2:mem:x").isEmpty())
    }
}
