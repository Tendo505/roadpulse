package io.github.tendo505.roadpulse

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What drivers can report, and how long each kind stays on the map without confirmation. */
enum class ReportType(val ttl: Duration) {
    POLICE(Duration.ofMinutes(60)),
    ACCIDENT(Duration.ofMinutes(120)),
    HAZARD(Duration.ofMinutes(180)),
    JAM(Duration.ofMinutes(30)),
    CLOSURE(Duration.ofHours(24)),
}

data class Report(
    val id: UUID,
    val type: ReportType,
    val position: LatLon,
    val note: String?,
    val reporter: String,
    val confirmations: Int,
    val dismissals: Int,
    val createdAt: Instant,
    val lastConfirmedAt: Instant,
    val expiresAt: Instant,
)

/** A report located on a route, nearest first. */
data class RouteReport(val report: Report, val alongRouteMeters: Double, val offRouteMeters: Double)

data class SubmitResult(val report: Report, val merged: Boolean)

sealed class ReportException(message: String) : RuntimeException(message)
class ReportNotFound(id: UUID) : ReportException("Report $id does not exist or has expired")
class AlreadyVoted : ReportException("This device has already voted on this report")
class OwnReport : ReportException("You can't vote on your own report")
class RateLimited(val retryAfter: Duration) : ReportException("Too many reports from this device; try again in ${retryAfter.toMinutes() + 1} min")

// ---------- persistence ----------

private fun Instant.toDb(): OffsetDateTime = atOffset(ZoneOffset.UTC)
private fun ResultSet.instant(c: String): Instant = getObject(c, OffsetDateTime::class.java).toInstant()

@Repository
class ReportRepository(private val jdbc: JdbcClient) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Report(
        id = rs.getObject("id", UUID::class.java),
        type = ReportType.valueOf(rs.getString("type")),
        position = LatLon(rs.getDouble("lat"), rs.getDouble("lon")),
        note = rs.getString("note"),
        reporter = rs.getString("reporter"),
        confirmations = rs.getInt("confirmations"),
        dismissals = rs.getInt("dismissals"),
        createdAt = rs.instant("created_at"),
        lastConfirmedAt = rs.instant("last_confirmed_at"),
        expiresAt = rs.instant("expires_at"),
    )

    fun insert(r: Report) {
        jdbc.sql(
            """
            INSERT INTO reports (id, type, lat, lon, note, reporter, confirmations, dismissals, created_at, last_confirmed_at, expires_at)
            VALUES (:id, :type, :lat, :lon, :note, :reporter, :c, :d, :created, :confirmed, :expires)
            """,
        ).param("id", r.id).param("type", r.type.name).param("lat", r.position.lat).param("lon", r.position.lon)
            .param("note", r.note).param("reporter", r.reporter).param("c", r.confirmations).param("d", r.dismissals)
            .param("created", r.createdAt.toDb()).param("confirmed", r.lastConfirmedAt.toDb()).param("expires", r.expiresAt.toDb())
            .update()
    }

    fun findActive(id: UUID, now: Instant): Report? =
        jdbc.sql("SELECT * FROM reports WHERE id = :id AND expires_at > :now")
            .param("id", id).param("now", now.toDb()).query(::map).optional().orElse(null)

    /** Active reports inside a lat/lon box (served by the (lat, lon) index). */
    fun findInBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, now: Instant, limit: Int, type: ReportType? = null): List<Report> {
        val sql = buildString {
            append("SELECT * FROM reports WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon AND expires_at > :now")
            if (type != null) append(" AND type = :type")
            append(" ORDER BY last_confirmed_at DESC LIMIT :limit")
        }
        var spec = jdbc.sql(sql).param("minLat", minLat).param("maxLat", maxLat).param("minLon", minLon).param("maxLon", maxLon)
            .param("now", now.toDb()).param("limit", limit)
        if (type != null) spec = spec.param("type", type.name)
        return spec.query(::map).list()
    }

    fun countByReporterSince(reporter: String, since: Instant): Int =
        jdbc.sql("SELECT COUNT(*) FROM reports WHERE reporter = :r AND created_at > :since")
            .param("r", reporter).param("since", since.toDb()).query(Int::class.java).single()

    fun oldestByReporterSince(reporter: String, since: Instant): Instant? =
        jdbc.sql("SELECT MIN(created_at) AS t FROM reports WHERE reporter = :r AND created_at > :since")
            .param("r", reporter).param("since", since.toDb())
            .query { rs, _ -> rs.getObject("t", OffsetDateTime::class.java)?.toInstant() }.single()

    /** Someone confirmed the report is still there: count it and push the expiry out. */
    fun confirm(id: UUID, now: Instant, newExpiry: Instant): Boolean =
        jdbc.sql(
            """
            UPDATE reports SET confirmations = confirmations + 1, last_confirmed_at = :now,
                expires_at = CASE WHEN expires_at > :exp THEN expires_at ELSE :exp END
            WHERE id = :id AND expires_at > :now
            """,
        ).param("id", id).param("now", now.toDb()).param("exp", newExpiry.toDb()).update() == 1

    /**
     * Someone says it's gone. Once "gone" votes outnumber "still there" votes
     * (counting the original report as one), the report expires immediately.
     * Done in a single UPDATE so concurrent votes can't lose a count.
     */
    fun dismiss(id: UUID, now: Instant): Boolean =
        jdbc.sql(
            """
            UPDATE reports SET dismissals = dismissals + 1,
                expires_at = CASE WHEN dismissals + 1 > confirmations THEN :now ELSE expires_at END
            WHERE id = :id AND expires_at > :now
            """,
        ).param("id", id).param("now", now.toDb()).update() == 1

    /** Throws DuplicateKeyException if this device already voted. */
    fun insertVote(reportId: UUID, device: String, stillThere: Boolean, now: Instant) {
        jdbc.sql("INSERT INTO votes (report_id, device, still_there, created_at) VALUES (:r, :d, :s, :now)")
            .param("r", reportId).param("d", device).param("s", stillThere).param("now", now.toDb()).update()
    }

    fun deleteExpiredBefore(cutoff: Instant): Int =
        jdbc.sql("DELETE FROM reports WHERE expires_at < :cutoff").param("cutoff", cutoff.toDb()).update()

    fun countActive(now: Instant): Int =
        jdbc.sql("SELECT COUNT(*) FROM reports WHERE expires_at > :now").param("now", now.toDb()).query(Int::class.java).single()
}

// ---------- business rules ----------

@Service
class ReportService(
    private val repo: ReportRepository,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val props: RoadPulseProperties,
) {
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    /**
     * Submit a report. If an active report of the same type already exists
     * within the merge radius, it counts as a confirmation of that report
     * instead, so ten drivers passing one accident produce one marker with
     * ten confirmations, not ten markers.
     */
    fun submit(type: ReportType, at: LatLon, note: String?, device: String): SubmitResult = tx.execute {
        val now = now()
        enforceRateLimit(device, now)

        val (dLat, dLon) = Geo.metersToDegrees(props.mergeRadiusMeters, at.lat)
        val nearby = repo.findInBox(at.lat - dLat, at.lon - dLon, at.lat + dLat, at.lon + dLon, now, limit = 50, type = type)
            .map { it to Geo.distance(it.position, at) }
            .filter { (_, d) -> d <= props.mergeRadiusMeters }
            .minByOrNull { (_, d) -> d }?.first

        if (nearby != null) {
            if (nearby.reporter != device) {
                try {
                    repo.insertVote(nearby.id, device, stillThere = true, now)
                    repo.confirm(nearby.id, now, now.plus(type.ttl))
                } catch (_: DuplicateKeyException) {
                    // Already confirmed by this device: re-reporting is a no-op.
                }
            }
            return@execute SubmitResult(repo.findActive(nearby.id, now) ?: nearby, merged = true)
        }

        val report = Report(
            id = UUID.randomUUID(), type = type, position = at, note = note?.trim()?.takeIf { it.isNotEmpty() },
            reporter = device, confirmations = 1, dismissals = 0,
            createdAt = now, lastConfirmedAt = now, expiresAt = now.plus(type.ttl),
        )
        repo.insert(report)
        SubmitResult(report, merged = false)
    }!!

    private fun enforceRateLimit(device: String, now: Instant) {
        val since = now.minus(props.rateWindow)
        if (repo.countByReporterSince(device, since) >= props.maxReportsPerWindow) {
            val oldest = repo.oldestByReporterSince(device, since) ?: now
            throw RateLimited(Duration.between(now, oldest.plus(props.rateWindow)).coerceAtLeast(Duration.ZERO))
        }
    }

    /** "Still there" or "gone". One vote per device; reporters can't vote on their own reports. */
    fun vote(id: UUID, device: String, stillThere: Boolean): Report? = tx.execute {
        val now = now()
        val report = repo.findActive(id, now) ?: throw ReportNotFound(id)
        if (report.reporter == device) throw OwnReport()
        try {
            repo.insertVote(id, device, stillThere, now)
        } catch (_: DuplicateKeyException) {
            throw AlreadyVoted()
        }
        if (stillThere) repo.confirm(id, now, now.plus(report.type.ttl)) else repo.dismiss(id, now)
        repo.findActive(id, now) // null when this vote removed it
    }

    fun inBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Report> {
        require(minLat < maxLat && minLon < maxLon) { "min must be less than max" }
        require(maxLat - minLat <= 2.0 && maxLon - minLon <= 2.0) { "area too large: zoom in (max 2 degrees per side)" }
        return repo.findInBox(minLat, minLon, maxLat, maxLon, now(), limit = 500)
    }

    /**
     * Reports within [corridorMeters] of a route, ordered by how far along the
     * route they are. The database narrows candidates with the route's bounding
     * box; exact corridor matching happens in Kotlin.
     */
    fun alongRoute(route: List<LatLon>, corridorMeters: Double): List<RouteReport> {
        require(route.size in 2..5_000) { "route must have between 2 and 5000 points" }
        require(corridorMeters in 5.0..500.0) { "corridor must be between 5 and 500 metres" }
        val midLat = (route.minOf { it.lat } + route.maxOf { it.lat }) / 2
        val (dLat, dLon) = Geo.metersToDegrees(corridorMeters, midLat)
        val candidates = repo.findInBox(
            route.minOf { it.lat } - dLat, route.minOf { it.lon } - dLon,
            route.maxOf { it.lat } + dLat, route.maxOf { it.lon } + dLon,
            now(), limit = 2_000,
        )
        return candidates
            .map { r -> Geo.matchToRoute(r.position, route).let { RouteReport(r, it.alongRouteMeters, it.offRouteMeters) } }
            .filter { it.offRouteMeters <= corridorMeters }
            .sortedBy { it.alongRouteMeters }
    }

    fun get(id: UUID): Report = repo.findActive(id, now()) ?: throw ReportNotFound(id)
}

/** Deletes long-expired rows so the table only holds roughly a day of history. */
@Component
@ConditionalOnProperty("roadpulse.cleanup.enabled", havingValue = "true", matchIfMissing = true)
class ExpiredReportCleanup(private val repo: ReportRepository, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${roadpulse.cleanup-interval}")
    fun clean() {
        val removed = repo.deleteExpiredBefore(clock.instant().minus(Duration.ofHours(24)))
        if (removed > 0) log.info("Deleted {} expired report(s)", removed)
    }
}
