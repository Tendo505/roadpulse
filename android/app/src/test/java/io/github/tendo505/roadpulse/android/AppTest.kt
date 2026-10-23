package io.github.tendo505.roadpulse.android

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.tendo505.roadpulse.android.data.AppContainer
import io.github.tendo505.roadpulse.android.data.LatLng
import io.github.tendo505.roadpulse.android.data.LocalReports
import io.github.tendo505.roadpulse.android.data.Maneuver
import io.github.tendo505.roadpulse.android.data.Place
import io.github.tendo505.roadpulse.android.data.ReportSource
import io.github.tendo505.roadpulse.android.data.ReportType
import io.github.tendo505.roadpulse.android.data.Route
import io.github.tendo505.roadpulse.android.data.Router
import io.github.tendo505.roadpulse.android.ui.MapViewModel
import io.github.tendo505.roadpulse.android.ui.instruction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** Turn instructions in both languages, using the real string resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RoadPulseApp::class)
class InstructionTest {
    private fun res(locale: Locale): android.content.res.Resources {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val config = android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(locale) }
        return ctx.createConfigurationContext(config).resources
    }

    private fun m(type: String, mod: String?, road: String?, exit: Int? = null) = Maneuver(type, mod, road, exit, LatLng(0.0, 0.0))

    @Test
    fun english() {
        val r = res(Locale.ENGLISH)
        assertEquals("Turn left onto Jalan Ampang", instruction(r, m("turn", "left", "Jalan Ampang")))
        assertEquals("Keep slightly right", instruction(r, m("fork", "slight right", null)))
        assertEquals("At the roundabout, take exit 2 onto Jalan Raja", instruction(r, m("roundabout", "right", "Jalan Raja", exit = 2)))
        assertEquals("Arrive at your destination", instruction(r, m("arrive", null, "Jalan X")))
    }

    @Test
    fun malay() {
        val r = res(Locale("ms"))
        assertEquals("Belok kiri ke Jalan Ampang", instruction(r, m("turn", "left", "Jalan Ampang")))
        assertEquals("Buat pusingan U", instruction(r, m("turn", "uturn", null)))
        assertEquals("Di bulatan, ambil susur keluar ke-3", instruction(r, m("rotary", null, null, exit = 3)))
    }
}

/** The whole driving flow through the real ViewModel: search result → route → simulated drive → alerts → arrival. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RoadPulseApp::class)
class DriveFlowTest {
    private val clock = MutableClock()
    private val local = LocalReports(null, clock)

    private val container = object : AppContainer(ApplicationProvider.getApplicationContext(), clock) {
        override val router = object : Router() {
            override suspend fun route(from: LatLng, to: LatLng): Route = testRoute()
        }
        override suspend fun reports(): ReportSource = local
    }

    private fun idleUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        }
    }

    @Test
    fun `simulated drive warns about the police report once, gives directions, and arrives`() {
        runBlocking { local.submit(ReportType.POLICE, LatLng(3.1391, 101.6930), null) }
        val spoken = mutableListOf<String>()
        val vm = MapViewModel(container, speak = { spoken += it }, tickMillis = 100)

        vm.choose(Place("Suria KLCC", null, LatLng(3.1480, 101.6959)))
        vm.requestRoute(fallbackStart = LatLng(3.1390, 101.6869))
        idleUntil { vm.state.value.route != null && vm.state.value.routeReports.isNotEmpty() }
        assertEquals(1, vm.state.value.routeReports.size)

        vm.startNavigation(simulate = true)
        idleUntil { vm.state.value.nav?.arrived == true }

        val police = spoken.filter { it.contains("Police reported ahead") }
        assertEquals("hazard announced exactly once: $spoken", 1, police.size)
        assertTrue("turn announced: $spoken", spoken.any { it.contains("Turn left onto Jalan Ampang") })
        assertEquals("You have arrived", spoken.last())
    }

    @Test
    fun `reporting while driving uses the current position`() {
        val vm = MapViewModel(container, speak = null, tickMillis = 100)
        vm.choose(Place("Somewhere", null, LatLng(3.1480, 101.6959)))
        vm.requestRoute(fallbackStart = LatLng(3.1390, 101.6869))
        idleUntil { vm.state.value.route != null }
        vm.startNavigation(simulate = true)
        idleUntil { (vm.state.value.nav?.travelledMeters ?: 0.0) > 300 }
        val at = vm.state.value.nav!!.position

        vm.submitReport(ReportType.HAZARD, "Pothole", fallback = null)
        idleUntil { vm.state.value.message != null }
        val stored = runBlocking { local.inBox(3.0, 101.0, 3.3, 102.0) }.single()
        assertEquals(at.lat, stored.position.lat, 1e-6)
        assertEquals("Pothole", stored.note)
        vm.stopNavigation()
    }
}
