package io.github.tendo505.roadpulse.android.ui

import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tendo505.roadpulse.android.data.LatLng
import io.github.tendo505.roadpulse.android.data.Report
import io.github.tendo505.roadpulse.android.data.ReportType
import io.github.tendo505.roadpulse.android.data.Route
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MapLatLng

/** Free, key-less vector tiles from OpenFreeMap (OpenStreetMap data). */
const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

fun reportColor(type: ReportType): Color = when (type) {
    ReportType.POLICE -> Color(0xFF2563EB)
    ReportType.ACCIDENT -> Color(0xFFDC2626)
    ReportType.HAZARD -> Color(0xFFD97706)
    ReportType.JAM -> Color(0xFFEA580C)
    ReportType.CLOSURE -> Color(0xFF475569)
}

fun reportIcon(type: ReportType): ImageVector = when (type) {
    ReportType.POLICE -> Icons.Filled.LocalPolice
    ReportType.ACCIDENT -> Icons.Filled.CarCrash
    ReportType.HAZARD -> Icons.Filled.Warning
    ReportType.JAM -> Icons.Filled.Traffic
    ReportType.CLOSURE -> Icons.Filled.Block
}

private fun LatLng.toMap() = MapLatLng(lat, lon)

/**
 * The MapLibre map, wrapped for Compose. State flows one way: whenever the
 * inputs change, the GeoJSON sources are replaced; user gestures come back
 * out through the callbacks.
 */
@Composable
fun RoadMap(
    reports: List<Report>,
    route: Route?,
    me: LatLng?,
    destination: LatLng?,
    camera: CameraTarget?,
    navigating: Boolean,
    bearing: Double?,
    onCameraHandled: () -> Unit,
    onMoved: (Bounds) -> Unit,
    onLongPress: (LatLng) -> Unit,
    onReportTap: (String) -> Unit,
    onCenterChanged: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val onMovedState by rememberUpdatedState(onMoved)
    val onLongPressState by rememberUpdatedState(onLongPress)
    val onTapState by rememberUpdatedState(onReportTap)
    val onCenterState by rememberUpdatedState(onCenterChanged)

    // Marker images: a coloured disc with a white Material icon, rendered once per type.
    val painters = ReportType.entries.associateWith { rememberVectorPainter(reportIcon(it)) }
    val icons = remember(density) {
        val sizePx = with(density) { 36.dp.toPx() }.toInt()
        ReportType.entries.associateWith { type ->
            val image = ImageBitmap(sizePx, sizePx)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(sizePx.toFloat(), sizePx.toFloat())) {
                val r = size.minDimension / 2
                drawCircle(Color.White, r)
                drawCircle(reportColor(type), r - 2.dp.toPx())
                val inner = size.minDimension * 0.56f
                translate((size.width - inner) / 2, (size.height - inner) / 2) {
                    with(painters.getValue(type)) { draw(Size(inner, inner), colorFilter = ColorFilter.tint(Color.White)) }
                }
            }
            image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isCompassEnabled = true
                m.uiSettings.isRotateGesturesEnabled = true
                m.cameraPosition = CameraPosition.Builder().target(MapLatLng(20.0, 0.0)).zoom(1.5).build()
                m.setStyle(Style.Builder().fromUri(MAP_STYLE)) { s ->
                    icons.forEach { (type, bmp) -> s.addImage("report-${type.name}", bmp) }
                    s.addSource(GeoJsonSource("route"))
                    s.addLayer(LineLayer("route-casing", "route").withProperties(
                        PropertyFactory.lineColor(Color(0xFF0B4F8A).toArgb()), PropertyFactory.lineWidth(11f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                    s.addLayer(LineLayer("route-line", "route").withProperties(
                        PropertyFactory.lineColor(Color(0xFF3B9BFF).toArgb()), PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                    s.addSource(GeoJsonSource("destination"))
                    s.addLayer(CircleLayer("destination-dot", "destination").withProperties(
                        PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(Color(0xFFDC2626).toArgb()),
                        PropertyFactory.circleStrokeColor(Color.White.toArgb()), PropertyFactory.circleStrokeWidth(3f)))
                    s.addSource(GeoJsonSource("reports"))
                    s.addLayer(SymbolLayer("reports-layer", "reports").withProperties(
                        PropertyFactory.iconImage(Expression.get("icon")),
                        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
                    // Your own position is drawn last so report markers never hide it.
                    s.addSource(GeoJsonSource("me"))
                    s.addLayer(CircleLayer("me-halo", "me").withProperties(
                        PropertyFactory.circleRadius(18f), PropertyFactory.circleColor(Color(0x332563EB).toArgb())))
                    s.addLayer(CircleLayer("me-dot", "me").withProperties(
                        PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(Color(0xFF2563EB).toArgb()),
                        PropertyFactory.circleStrokeColor(Color.White.toArgb()), PropertyFactory.circleStrokeWidth(3f)))
                    style = s
                }
                m.addOnCameraIdleListener {
                    val b = m.projection.visibleRegion.latLngBounds
                    onMovedState(Bounds(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast))
                    m.cameraPosition.target?.let { onCenterState(LatLng(it.latitude, it.longitude)) }
                }
                m.addOnMapLongClickListener { p -> onLongPressState(LatLng(p.latitude, p.longitude)); true }
                m.addOnMapClickListener { p ->
                    val hit = m.queryRenderedFeatures(m.projection.toScreenLocation(p), "reports-layer").firstOrNull()
                    hit?.getStringProperty("id")?.let { onTapState(it); true } ?: false
                }
                map = m
            }
        }
    }

    // Forward the activity lifecycle to the MapView, which needs it to manage GL resources.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView.onDestroy() }
    }

    LaunchedEffect(style, reports) {
        style?.getSourceAs<GeoJsonSource>("reports")?.setGeoJson(FeatureCollection.fromFeatures(reports.map { r ->
            Feature.fromGeometry(Point.fromLngLat(r.position.lon, r.position.lat)).apply {
                addStringProperty("id", r.id)
                addStringProperty("icon", "report-${r.type.name}")
            }
        }))
    }
    LaunchedEffect(style, route) {
        val src = style?.getSourceAs<GeoJsonSource>("route") ?: return@LaunchedEffect
        if (route == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(LineString.fromLngLats(route.points.map { Point.fromLngLat(it.lon, it.lat) }))
    }
    LaunchedEffect(style, me) {
        val src = style?.getSourceAs<GeoJsonSource>("me") ?: return@LaunchedEffect
        src.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(me?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })))
    }
    LaunchedEffect(style, destination) {
        val src = style?.getSourceAs<GeoJsonSource>("destination") ?: return@LaunchedEffect
        src.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(destination?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })))
    }
    LaunchedEffect(map, camera) {
        val m = map ?: return@LaunchedEffect
        when (camera) {
            null -> return@LaunchedEffect
            is CameraTarget.Point -> {
                // While driving, put the car in the lower part of the screen so more of the road ahead is visible.
                val topPadding = if (navigating) mapView.height * 0.45 else 0.0
                val pos = CameraPosition.Builder().target(camera.at.toMap()).zoom(camera.zoom)
                    .padding(0.0, topPadding, 0.0, 0.0)
                    .apply { if (navigating && bearing != null) bearing(bearing).tilt(50.0) else bearing(0.0).tilt(0.0) }
                    .build()
                m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), if (navigating) 450 else 900)
            }
            is CameraTarget.Fit -> if (camera.points.size >= 2) {
                val bounds = LatLngBounds.Builder().includes(camera.points.map { it.toMap() }).build()
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 160, 360, 160, 560), 900)
            }
        }
        onCameraHandled()
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
