package io.github.tendo505.roadpulse.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tendo505.roadpulse.android.R
import io.github.tendo505.roadpulse.android.data.AppContainer
import io.github.tendo505.roadpulse.android.data.AppError
import io.github.tendo505.roadpulse.android.data.LatLng
import io.github.tendo505.roadpulse.android.data.Place
import io.github.tendo505.roadpulse.android.data.Report
import io.github.tendo505.roadpulse.android.data.ReportType
import io.github.tendo505.roadpulse.android.data.Route
import io.github.tendo505.roadpulse.android.data.Settings
import io.github.tendo505.roadpulse.android.nav.NavState
import io.github.tendo505.roadpulse.android.nav.Navigator
import io.github.tendo505.roadpulse.android.nav.RouteSimulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double)

/** One-shot request for the map camera. */
sealed interface CameraTarget {
    data class Point(val at: LatLng, val zoom: Double) : CameraTarget
    data class Fit(val points: List<LatLng>) : CameraTarget
}

data class MapUiState(
    val settings: Settings = Settings(),
    val hasLocationPermission: Boolean = false,
    val myLocation: LatLng? = null,
    val shared: Boolean = false,
    val reports: List<Report> = emptyList(),
    val query: String = "",
    val results: List<Place> = emptyList(),
    val destination: Place? = null,
    val route: Route? = null,
    val routeReports: List<Report> = emptyList(),
    val loadingRoute: Boolean = false,
    val navigating: Boolean = false,
    val simulating: Boolean = false,
    val nav: NavState? = null,
    val rerouting: Boolean = false,
    val selectedReport: Report? = null,
    val reportSheet: Boolean = false,
    val message: Int? = null,
    val camera: CameraTarget? = null,
)

/**
 * Drives the single map screen: search, routing, turn-by-turn navigation
 * (real GPS or simulated), spoken alerts, and community reports.
 */
class MapViewModel(
    private val container: AppContainer,
    /** Turns a navigation event into speech; null in tests. */
    private val speak: ((String) -> Unit)? = null,
    private val tickMillis: Long = 500,
) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()
    private val res get() = container.context.resources

    private var reportsJob: Job? = null
    private var searchJob: Job? = null
    private var navJob: Job? = null
    private var locationJob: Job? = null
    private var navigator: Navigator? = null
    private var announcedSteps = mutableSetOf<Int>()
    private var lastBounds: Bounds? = null

    init {
        viewModelScope.launch {
            container.settings.settings.collect { s ->
                val source = container.reports()
                _state.update { it.copy(settings = s, shared = source.shared) }
                lastBounds?.let { loadReports(it) }
            }
        }
    }

    private fun show(message: Int) = _state.update { it.copy(message = message) }
    fun messageShown() = _state.update { it.copy(message = null) }
    fun cameraHandled() = _state.update { it.copy(camera = null) }

    private fun errorMessage(e: Throwable): Int = when (e) {
        is AppError.Offline -> R.string.error_offline
        is AppError.NoRoute -> R.string.error_no_route
        is AppError.RateLimited -> R.string.error_rate_limited
        is AppError.AlreadyVoted -> R.string.error_already_voted
        else -> R.string.error_generic
    }

    private inline fun guarded(crossinline block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { show(errorMessage(e)) }
    }

    // ---------- location ----------

    fun onLocationPermission(granted: Boolean) {
        _state.update { it.copy(hasLocationPermission = granted) }
        if (!granted || locationJob != null) return
        var first = true
        locationJob = viewModelScope.launch {
            container.location.updates().collect { p ->
                _state.update { it.copy(myLocation = p) }
                if (first) { first = false; if (!_state.value.navigating) _state.update { it.copy(camera = CameraTarget.Point(p, 14.5)) } }
                if (_state.value.navigating && !_state.value.simulating) onFix(p)
            }
        }
    }

    fun recenter() {
        val target = _state.value.nav?.position ?: _state.value.myLocation ?: return
        _state.update { it.copy(camera = CameraTarget.Point(target, 16.0)) }
    }

    // ---------- reports on the map ----------

    fun onMapMoved(b: Bounds) {
        lastBounds = b
        reportsJob?.cancel()
        reportsJob = viewModelScope.launch { delay(250); loadReports(b) }
    }

    private suspend fun loadReports(b: Bounds) {
        // Very zoomed-out views would be huge requests for unreadable markers; skip them.
        if (b.north - b.south > 1.5 || b.east - b.west > 1.5) { _state.update { it.copy(reports = emptyList()) }; return }
        try {
            val list = container.reports().inBox(b.south, b.west, b.north, b.east)
            _state.update { it.copy(reports = list) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep showing what we had; the next camera move retries.
        }
    }

    fun openReportSheet() = _state.update { it.copy(reportSheet = true) }
    fun closeReportSheet() = _state.update { it.copy(reportSheet = false) }

    /** Report at the driver's position (or the map pin / screen centre without GPS). */
    fun submitReport(type: ReportType, note: String?, fallback: LatLng?) {
        val at = _state.value.nav?.position ?: _state.value.myLocation ?: fallback ?: return
        _state.update { it.copy(reportSheet = false) }
        guarded {
            val before = _state.value.reports.map { it.id }.toSet()
            val report = container.reports().submit(type, at, note)
            show(if (report.id in before) R.string.merged_report else R.string.sent_report)
            lastBounds?.let { loadReports(it) }
            refreshRouteReports()
        }
    }

    fun selectReport(id: String?) = _state.update { s -> s.copy(selectedReport = s.reports.firstOrNull { it.id == id } ?: s.routeReports.firstOrNull { it.id == id }) }

    fun vote(stillThere: Boolean) {
        val report = _state.value.selectedReport ?: return
        _state.update { it.copy(selectedReport = null) }
        guarded {
            container.reports().vote(report.id, stillThere)
            lastBounds?.let { loadReports(it) }
            refreshRouteReports()
        }
    }

    // ---------- search & destination ----------

    fun onQuery(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.trim().length < 3) { _state.update { it.copy(results = emptyList()) }; return }
        searchJob = viewModelScope.launch {
            delay(400) // wait for typing to pause; Photon asks clients not to fire per keystroke
            try {
                // Geocoders often return the same place several times (building, POI, address); show it once.
                val places = container.search.search(q.trim(), _state.value.myLocation)
                    .distinctBy { it.name to it.detail }
                _state.update { it.copy(results = places) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { show(errorMessage(e)) }
        }
    }

    fun choose(place: Place) {
        // A search still in flight from typing must not reopen the results after a choice is made.
        searchJob?.cancel()
        _state.update { it.copy(destination = place, results = emptyList(), query = place.name, route = null, camera = CameraTarget.Point(place.position, 15.0)) }
    }

    fun dropPin(at: LatLng) {
        if (_state.value.navigating) return
        choose(Place(res.getString(R.string.dropped_pin), String.format(java.util.Locale.US, "%.5f, %.5f", at.lat, at.lon), at))
    }

    fun clearDestination() {
        stopNavigation()
        _state.update { it.copy(destination = null, route = null, routeReports = emptyList(), query = "", results = emptyList()) }
    }

    fun requestRoute(fallbackStart: LatLng?) {
        val dest = _state.value.destination ?: return
        val from = _state.value.myLocation ?: fallbackStart ?: return
        _state.update { it.copy(loadingRoute = true) }
        guarded {
            try {
                val route = container.router.route(from, dest.position)
                _state.update { it.copy(route = route, camera = CameraTarget.Fit(route.points)) }
                refreshRouteReports()
            } finally {
                _state.update { it.copy(loadingRoute = false) }
            }
        }
    }

    private suspend fun refreshRouteReports() {
        val route = _state.value.route ?: return
        val list = runCatching { container.reports().alongRoute(route.points) }
            .onFailure { android.util.Log.w("RoadPulse", "Couldn't load reports along the route", it) }
            .getOrDefault(_state.value.routeReports)
        _state.update { it.copy(routeReports = list) }
    }

    // ---------- navigation ----------

    fun startNavigation(simulate: Boolean) {
        val route = _state.value.route ?: return
        navigator = Navigator(route, reportsOnRoute = { _state.value.routeReports })
        announcedSteps.clear()
        _state.update { it.copy(navigating = true, simulating = simulate, nav = null) }
        navJob?.cancel()
        if (simulate) {
            val sim = RouteSimulator(route, metersPerSecond = 22.0)
            navJob = viewModelScope.launch {
                while (isActive && !sim.finished) {
                    onFix(sim.advance(tickMillis / 1000.0))
                    delay(tickMillis)
                }
                onFix(route.points.last())
            }
        } else {
            _state.value.myLocation?.let { onFix(it) }
        }
    }

    fun stopNavigation() {
        navJob?.cancel()
        navigator = null
        // Back to a flat, north-up map around wherever the drive ended.
        val endedAt = _state.value.nav?.position ?: _state.value.myLocation
        _state.update { it.copy(navigating = false, simulating = false, nav = null, rerouting = false,
            camera = endedAt?.let { p -> CameraTarget.Point(p, 15.0) } ?: it.camera) }
    }

    /** One position fix while navigating. Public for tests. */
    fun onFix(p: LatLng) {
        val nav = navigator ?: return
        val s = nav.update(p)
        _state.update { it.copy(nav = s, camera = CameraTarget.Point(s.snapped, 16.5)) }
        val units = _state.value.settings.units
        val voice = _state.value.settings.voice

        s.hazardAhead?.let { h ->
            if (nav.shouldAnnounce(h) && voice) speak?.invoke(res.getString(R.string.spoken_hazard, typeLabel(res, h.type), formatDistance(s.metersToHazard, units)))
        }
        val stepIndex = s.nextStep?.let { nav.route.steps.indexOf(it) } ?: -1
        if (stepIndex >= 0 && s.metersToNextStep < 250 && announcedSteps.add(stepIndex) && voice && !s.arrived) {
            speak?.invoke(res.getString(R.string.m_in, formatDistance(s.metersToNextStep, units), instruction(res, s.nextStep!!.maneuver)))
        }
        if (s.arrived) {
            navJob?.cancel()
            if (voice) speak?.invoke(res.getString(R.string.arrived))
            navigator = null
            return
        }
        if (s.offRoute && !_state.value.rerouting) reroute(p)
    }

    private fun reroute(from: LatLng) {
        val dest = _state.value.destination ?: return
        _state.update { it.copy(rerouting = true) }
        guarded {
            try {
                val route = container.router.route(from, dest.position)
                navigator = Navigator(route, reportsOnRoute = { _state.value.routeReports })
                announcedSteps.clear()
                _state.update { it.copy(route = route) }
                refreshRouteReports()
            } finally {
                _state.update { it.copy(rerouting = false) }
            }
        }
    }

    // ---------- settings ----------

    fun saveSettings(serverUrl: String, voice: Boolean, units: io.github.tendo505.roadpulse.android.data.Units) = viewModelScope.launch {
        container.settings.setServerUrl(serverUrl)
        container.settings.setVoice(voice)
        container.settings.setUnits(units)
    }
}
