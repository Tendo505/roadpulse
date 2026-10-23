package io.github.tendo505.roadpulse.android.ui

import android.content.res.Resources
import io.github.tendo505.roadpulse.android.R
import io.github.tendo505.roadpulse.android.data.Maneuver
import io.github.tendo505.roadpulse.android.data.ReportType
import io.github.tendo505.roadpulse.android.data.Units
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Distances the way drivers read them: rounded to steps that matter at
 * speed ("250 m", "1.4 km", "0.3 mi", "800 ft"). Pure, so it's unit tested.
 */
fun formatDistance(meters: Double, units: Units, locale: Locale = Locale.getDefault()): String = when (units) {
    Units.METRIC -> when {
        meters < 100 -> "${(meters / 10).roundToInt() * 10} m"
        meters < 1_000 -> "${(meters / 50).roundToInt() * 50} m"
        meters < 10_000 -> String.format(locale, "%.1f km", meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }
    Units.IMPERIAL -> {
        val feet = meters * 3.28084
        val miles = meters / 1609.344
        when {
            feet < 1_000 -> "${(feet / 50).roundToInt() * 50} ft"
            miles < 10 -> String.format(locale, "%.1f mi", miles)
            else -> "${miles.roundToInt()} mi"
        }
    }
}

fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

fun formatClock(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(instant.atZone(zone))

fun typeLabel(res: Resources, type: ReportType): String = res.getString(
    when (type) {
        ReportType.POLICE -> R.string.type_POLICE
        ReportType.ACCIDENT -> R.string.type_ACCIDENT
        ReportType.HAZARD -> R.string.type_HAZARD
        ReportType.JAM -> R.string.type_JAM
        ReportType.CLOSURE -> R.string.type_CLOSURE
    },
)

fun ageLabel(res: Resources, createdAt: Instant, now: Instant): String {
    val minutes = Duration.between(createdAt, now).toMinutes()
    return when {
        minutes < 1 -> res.getString(R.string.just_now)
        minutes < 60 -> res.getString(R.string.minutes_ago, minutes.toInt())
        else -> res.getString(R.string.hours_ago, (minutes / 60).toInt())
    }
}

/** The string resource for a maneuver's verb, chosen from OSRM's type and modifier. */
fun maneuverRes(m: Maneuver): Int = when {
    m.type == "arrive" -> R.string.m_arrive
    m.type == "depart" -> R.string.m_depart
    m.type in setOf("roundabout", "rotary", "roundabout turn") -> R.string.m_roundabout_plain
    m.type == "merge" -> R.string.m_merge
    m.type in setOf("on ramp", "off ramp") -> R.string.m_ramp
    else -> when (m.modifier) {
        "left" -> R.string.m_left
        "right" -> R.string.m_right
        "slight left" -> R.string.m_slight_left
        "slight right" -> R.string.m_slight_right
        "sharp left" -> R.string.m_sharp_left
        "sharp right" -> R.string.m_sharp_right
        "uturn" -> R.string.m_uturn
        else -> R.string.m_straight
    }
}

/** "Turn left onto Jalan Ampang" / "Belok kiri ke Jalan Ampang". */
fun instruction(res: Resources, m: Maneuver): String {
    val verb = if (m.type in setOf("roundabout", "rotary") && m.exit != null) res.getString(R.string.m_roundabout, m.exit)
    else res.getString(maneuverRes(m))
    return if (m.road != null && m.type != "arrive") res.getString(R.string.m_onto, verb, m.road) else verb
}
