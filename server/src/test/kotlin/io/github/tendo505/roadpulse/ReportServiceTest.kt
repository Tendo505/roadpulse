package io.github.tendo505.roadpulse

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableClock(private var now: Instant = Instant.parse("2026-11-01T08:00:00Z")) : Clock() {
    @Synchronized fun advance(d: Duration) { now = now.plus(d) }
    @Synchronized override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

@TestConfiguration
class TestClockConfig {
    @Bean @Primary fun mutableClock() = MutableClock()
}

@SpringBootTest(properties = ["roadpulse.seed-demo-data=false", "roadpulse.cleanup.enabled=false"])
@Import(TestClockConfig::class)
abstract class IntegrationTest {
    @Autowired lateinit var service: ReportService
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM votes").update()
        jdbc.sql("DELETE FROM reports").update()
    }

    val here = LatLon(3.1390, 101.6869)
    fun metersNorth(p: LatLon, m: Double) = LatLon(p.lat + m / 111_195.0, p.lon)
}

class ReportServiceTest : IntegrationTest() {

    @Test
    fun `a new report expires after its type's TTL`() {
        val r = service.submit(ReportType.POLICE, here, "  speed trap  ", "device-aaaa").report
        assertEquals("speed trap", r.note)
        assertEquals(clock.instant().plus(ReportType.POLICE.ttl), r.expiresAt)

        clock.advance(Duration.ofMinutes(59))
        assertEquals(1, service.inBox(3.0, 101.0, 3.3, 102.0).size)
        clock.advance(Duration.ofMinutes(2))
        assertEquals(0, service.inBox(3.0, 101.0, 3.3, 102.0).size)
    }

    @Test
    fun `nearby reports of the same type merge into one`() {
        val first = service.submit(ReportType.ACCIDENT, here, null, "device-aaaa")
        val second = service.submit(ReportType.ACCIDENT, metersNorth(here, 80.0), null, "device-bbbb")

        assertFalse(first.merged)
        assertTrue(second.merged)
        assertEquals(first.report.id, second.report.id)
        assertEquals(2, second.report.confirmations)
        assertEquals(1, service.inBox(3.0, 101.0, 3.3, 102.0).size)
    }

    @Test
    fun `different types or distant reports stay separate`() {
        service.submit(ReportType.ACCIDENT, here, null, "device-aaaa")
        assertFalse(service.submit(ReportType.POLICE, here, null, "device-bbbb").merged)
        assertFalse(service.submit(ReportType.ACCIDENT, metersNorth(here, 400.0), null, "device-cccc").merged)
        assertEquals(3, service.inBox(3.0, 101.0, 3.3, 102.0).size)
    }

    @Test
    fun `a merge refreshes the expiry`() {
        val r = service.submit(ReportType.JAM, here, null, "device-aaaa").report
        clock.advance(Duration.ofMinutes(25))
        val merged = service.submit(ReportType.JAM, here, null, "device-bbbb").report
        assertEquals(clock.instant().plus(ReportType.JAM.ttl), merged.expiresAt)
        assertTrue(merged.expiresAt.isAfter(r.expiresAt))
    }

    @Test
    fun `re-reporting your own hazard does not inflate its confirmations`() {
        service.submit(ReportType.HAZARD, here, null, "device-aaaa")
        val again = service.submit(ReportType.HAZARD, here, null, "device-aaaa")
        assertTrue(again.merged)
        assertEquals(1, again.report.confirmations)
    }

    @Test
    fun `still-there votes confirm and extend, one vote per device`() {
        val r = service.submit(ReportType.POLICE, here, null, "device-aaaa").report
        clock.advance(Duration.ofMinutes(50))
        val voted = service.vote(r.id, "device-bbbb", stillThere = true)!!
        assertEquals(2, voted.confirmations)
        assertEquals(clock.instant().plus(ReportType.POLICE.ttl), voted.expiresAt)
        assertThrows<AlreadyVoted> { service.vote(r.id, "device-bbbb", stillThere = false) }
        assertThrows<OwnReport> { service.vote(r.id, "device-aaaa", stillThere = true) }
    }

    @Test
    fun `enough gone votes remove a report`() {
        val r = service.submit(ReportType.ACCIDENT, here, null, "device-aaaa").report // 1 confirmation
        val afterFirst = service.vote(r.id, "device-bbbb", stillThere = false)
        assertEquals(1, afterFirst!!.dismissals, "1 gone vs 1 confirmation: still shown")
        assertNull(service.vote(r.id, "device-cccc", stillThere = false), "2 gone vs 1 confirmation: removed")
        assertEquals(0, service.inBox(3.0, 101.0, 3.3, 102.0).size)
        assertThrows<ReportNotFound> { service.vote(r.id, "device-dddd", stillThere = true) }
    }

    @Test
    fun `devices are rate limited`() {
        repeat(10) { i -> service.submit(ReportType.HAZARD, metersNorth(here, i * 500.0), null, "spammer-01") }
        val e = assertThrows<RateLimited> { service.submit(ReportType.HAZARD, metersNorth(here, 9_000.0), null, "spammer-01") }
        assertTrue(e.retryAfter <= Duration.ofMinutes(10))
        clock.advance(Duration.ofMinutes(11))
        service.submit(ReportType.HAZARD, metersNorth(here, 9_000.0), null, "spammer-01")
    }

    @Test
    fun `huge map areas are rejected`() {
        assertThrows<IllegalArgumentException> { service.inBox(0.0, 0.0, 10.0, 10.0) }
        assertThrows<IllegalArgumentException> { service.inBox(3.2, 101.0, 3.1, 102.0) }
    }

    @Test
    fun `along-route finds reports near the road, ordered by distance travelled`() {
        // An east-west road about 2.2 km long.
        val route = listOf(LatLon(3.1390, 101.6869), LatLon(3.1390, 101.7069))
        val far = service.submit(ReportType.POLICE, LatLon(3.1391, 101.7000), null, "device-aaaa").report  // ~1.46 km in, 11 m off
        val near = service.submit(ReportType.ACCIDENT, LatLon(3.1389, 101.6900), null, "device-bbbb").report // ~340 m in
        service.submit(ReportType.HAZARD, LatLon(3.1420, 101.6950), null, "device-cccc")                      // 330 m off the road
        service.submit(ReportType.JAM, LatLon(3.1390, 101.7300), null, "device-dddd")                         // beyond the end

        val found = service.alongRoute(route, corridorMeters = 60.0)
        assertEquals(listOf(near.id, far.id), found.map { it.report.id })
        assertEquals(345.0, found[0].alongRouteMeters, 15.0)
        assertTrue(found[1].offRouteMeters < 20)
    }

    @Test
    fun `concurrent gone votes are all counted`() {
        val r = service.submit(ReportType.CLOSURE, here, null, "device-root").report
        // Lift confirmations to 20 so the report survives 15 dismissals.
        repeat(19) { service.vote(r.id, "confirm-$it-xx", stillThere = true) }

        val pool = Executors.newFixedThreadPool(15)
        val gate = CountDownLatch(1)
        val futures = (1..15).map { i -> pool.submit(Callable { gate.await(); service.vote(r.id, "dismiss-$i-xx", stillThere = false) }) }
        gate.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        val final = service.get(r.id)
        assertEquals(20, final.confirmations)
        assertEquals(15, final.dismissals, "no lost updates under concurrency")
    }
}
