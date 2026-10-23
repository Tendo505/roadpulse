package io.github.tendo505.roadpulse.android.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tendo505.roadpulse.android.R
import io.github.tendo505.roadpulse.android.data.LatLng
import io.github.tendo505.roadpulse.android.data.Maneuver
import io.github.tendo505.roadpulse.android.data.Report
import io.github.tendo505.roadpulse.android.data.ReportType
import io.github.tendo505.roadpulse.android.data.Units
import java.time.Instant

@Composable
fun MapScreen(vm: MapViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = context.resources
    val snackbar = remember { SnackbarHostState() }
    var mapCenter by remember { mutableStateOf<LatLng?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val reportLabel = stringResource(R.string.report)

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        vm.onLocationPermission(grants.values.any { it })
    }
    LaunchedEffect(Unit) {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) vm.onLocationPermission(true)
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(res.getString(it)); vm.messageShown() }
    }
    // Keep the screen on while driving.
    val view = LocalView.current
    DisposableEffect(s.navigating) {
        view.keepScreenOn = s.navigating
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
        RoadMap(
            reports = s.reports.ifEmpty { s.routeReports },
            route = s.route,
            me = s.nav?.snapped ?: s.myLocation,
            destination = s.destination?.position,
            camera = s.camera,
            navigating = s.navigating,
            bearing = s.nav?.bearing,
            onCameraHandled = vm::cameraHandled,
            onMoved = vm::onMapMoved,
            onLongPress = vm::dropPin,
            onReportTap = vm::selectReport,
            onCenterChanged = { mapCenter = it },
            modifier = Modifier.fillMaxSize(),
        )

        // ---------- top ----------
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            when {
                s.navigating && s.nav?.arrived == true -> ArrivedBanner()
                s.navigating && s.nav != null -> ManeuverBanner(s.nav!!.nextStep?.maneuver, s.nav!!.metersToNextStep, s.settings.units, s.rerouting)
                else -> SearchBar(s.query, vm::onQuery, onSettings = { showSettings = true }, onClear = vm::clearDestination)
            }
            if (!s.navigating && s.results.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 320.dp), shape = RoundedCornerShape(16.dp)) {
                    LazyColumn {
                        items(s.results) { place ->
                            Row(Modifier.fillMaxWidth().clickable { focus.clearFocus(); vm.choose(place) }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(place.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    place.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            if (s.navigating && s.nav?.hazardAhead != null) {
                Spacer(Modifier.height(8.dp))
                HazardBanner(s.nav!!.hazardAhead!!, s.nav!!.metersToHazard, s.settings.units)
            }
            if (!s.hasLocationPermission && !s.navigating) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.location_needed), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                            Text(stringResource(R.string.allow))
                        }
                    }
                }
            }
        }

        // ---------- bottom ----------
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                ExtendedFloatingActionButton(
                    onClick = vm::openReportSheet,
                    icon = { Icon(Icons.Filled.Campaign, null) },
                    text = { Text(stringResource(R.string.report), fontWeight = FontWeight.Bold) },
                    containerColor = Color(0xFFF97316), contentColor = Color.White,
                    // Explicit label so screen readers announce the button, not just "button".
                    modifier = Modifier.testTag("report-fab").semantics { contentDescription = reportLabel },
                )
                SmallFloatingActionButton(onClick = vm::recenter, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.MyLocation, stringResource(R.string.my_location))
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                s.navigating && s.nav?.arrived == true -> Button(onClick = vm::clearDestination,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("end")) {
                    Text(stringResource(R.string.stop), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                s.navigating && s.nav != null -> TripBar(s.nav!!.remainingMeters, s.nav!!.remainingSeconds, s.settings.units, onEnd = vm::stopNavigation)
                s.route != null -> RoutePreview(s, onStart = { vm.startNavigation(simulate = false) }, onSimulate = { vm.startNavigation(simulate = true) },
                    onClose = vm::clearDestination, canDriveForReal = s.myLocation != null)
                s.destination != null -> DestinationCard(s.destination!!.name, s.destination!!.detail, s.loadingRoute,
                    onDirections = { vm.requestRoute(mapCenter) }, onClose = vm::clearDestination)
                else -> Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                    Text(stringResource(if (s.shared) R.string.mode_shared else R.string.mode_local) + " · " + stringResource(R.string.long_press_hint),
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.Center))
    }

    if (s.reportSheet) ReportSheet(onDismiss = vm::closeReportSheet, onSend = { type, note -> vm.submitReport(type, note, mapCenter) })
    s.selectedReport?.let { r ->
        ReportDetailsSheet(r, canVote = !r.mine || !s.shared, onVote = vm::vote, onDismiss = { vm.selectReport(null) })
    }
    if (showSettings) SettingsSheet(s.settings.serverUrl, s.settings.voice, s.settings.units,
        onSave = { url, voice, units -> vm.saveSettings(url, voice, units); showSettings = false },
        onDismiss = { showSettings = false })
}

// ---------- pieces ----------

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onSettings: () -> Unit, onClear: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
        TextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().testTag("search"),
        )
    }
}

private fun maneuverIcon(m: Maneuver?): ImageVector = when {
    m == null -> Icons.Filled.Straight
    m.type == "arrive" -> Icons.Filled.Flag
    m.modifier?.contains("left") == true -> Icons.Filled.TurnLeft
    m.modifier?.contains("right") == true -> Icons.Filled.TurnRight
    m.modifier == "uturn" -> Icons.Filled.UTurnLeft
    else -> Icons.Filled.Straight
}

@Composable
private fun ManeuverBanner(m: Maneuver?, meters: Double, units: Units, rerouting: Boolean) {
    val res = LocalContext.current.resources
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF0F5132), shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("maneuver")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(maneuverIcon(m), null, tint = Color.White, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                if (rerouting) {
                    Text(stringResource(R.string.rerouting), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text(formatDistance(meters, units), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    m?.let { Text(instruction(res, it), color = Color.White.copy(alpha = 0.92f), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun ArrivedBanner() {
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF0F5132), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Flag, null, tint = Color.White, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.arrived), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HazardBanner(r: Report, meters: Double, units: Units) {
    val res = LocalContext.current.resources
    Surface(shape = RoundedCornerShape(16.dp), color = reportColor(r.type), shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }.testTag("hazard")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(reportIcon(r.type), null, tint = Color.White, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.hazard_ahead, typeLabel(res, r.type), formatDistance(meters, units)),
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TripBar(remainingMeters: Double, remainingSeconds: Double, units: Units, onEnd: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatDuration(remainingSeconds), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F7A4A))
                Text(stringResource(R.string.route_summary, formatDistance(remainingMeters, units),
                    stringResource(R.string.arrival, formatClock(Instant.now().plusSeconds(remainingSeconds.toLong())))),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onEnd, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 52.dp).testTag("end")) { Text(stringResource(R.string.stop), fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun DestinationCard(name: String, detail: String?, loading: Boolean, onDirections: () -> Unit, onClose: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onDirections, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("directions")) {
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else { Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.directions), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun RoutePreview(s: MapUiState, onStart: () -> Unit, onSimulate: () -> Unit, onClose: () -> Unit, canDriveForReal: Boolean) {
    val route = s.route ?: return
    val res = LocalContext.current.resources
    Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatDuration(route.durationSeconds), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F7A4A), modifier = Modifier.testTag("eta"))
                    Text(stringResource(R.string.route_summary, formatDistance(route.distanceMeters, s.settings.units), s.destination?.name ?: ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
            }
            Spacer(Modifier.height(6.dp))
            val n = s.routeReports.size
            Row(verticalAlignment = Alignment.CenterVertically) {
                s.routeReports.map { it.type }.distinct().forEach { t ->
                    Box(Modifier.size(22.dp).background(reportColor(t), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(reportIcon(t), typeLabel(res, t), tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(if (n == 0) stringResource(R.string.no_hazards_on_route) else stringResource(R.string.hazards_on_route, n),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("route-reports"))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStart, enabled = canDriveForReal, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("start")) {
                    Text(stringResource(R.string.start), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onSimulate, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("simulate")) {
                    Text(stringResource(R.string.simulate))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportSheet(onDismiss: () -> Unit, onSend: (ReportType, String?) -> Unit) {
    val res = LocalContext.current.resources
    var note by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.report_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(14.dp))
            // Big targets: these get tapped by a passenger, or quickly at a red light.
            ReportType.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    row.forEach { t ->
                        Column(
                            Modifier.weight(1f).heightIn(min = 96.dp).background(reportColor(t), RoundedCornerShape(18.dp))
                                .clickable { onSend(t, note.ifBlank { null }) }.padding(10.dp).testTag("report-${t.name}"),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(reportIcon(t), null, tint = Color.White, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(typeLabel(res, t), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            OutlinedTextField(note, { if (it.length <= 140) note = it }, label = { Text(stringResource(R.string.report_note_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportDetailsSheet(r: Report, canVote: Boolean, onVote: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val res = LocalContext.current.resources
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(reportColor(r.type), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(reportIcon(r.type), null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(typeLabel(res, r.type), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.confirmations, r.confirmations, ageLabel(res, r.createdAt, Instant.now())),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            r.note?.let { Spacer(Modifier.height(10.dp)); Text("“$it”", style = MaterialTheme.typography.bodyLarge) }
            Spacer(Modifier.height(18.dp))
            if (canVote) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { onVote(true) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Icon(Icons.Filled.ThumbUp, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.still_there))
                    }
                    OutlinedButton(onClick = { onVote(false) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Icon(Icons.Filled.ThumbDown, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.gone))
                    }
                }
            } else {
                Text(stringResource(R.string.your_report), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(url: String, voice: Boolean, units: Units, onSave: (String, Boolean, Units) -> Unit, onDismiss: () -> Unit) {
    var u by remember { mutableStateOf(url) }
    var v by remember { mutableStateOf(voice) }
    var un by remember { mutableStateOf(units) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close)) }
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { v = !v }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.voice), Modifier.weight(1f))
                Switch(checked = v, onCheckedChange = { v = it })
            }
            Text(stringResource(R.string.units), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Units.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { un = option }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = un == option, onClick = { un = option })
                    Text(stringResource(if (option == Units.METRIC) R.string.metric else R.string.imperial))
                }
            }
            Text(stringResource(R.string.server), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.server_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(u, { u = it }, label = { Text(stringResource(R.string.server_url)) }, placeholder = { Text("https://…") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSave(u, v, un) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.save)) }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.map_attribution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
