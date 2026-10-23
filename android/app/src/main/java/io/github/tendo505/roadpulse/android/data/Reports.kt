package io.github.tendo505.roadpulse.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Where reports come from: a shared RoadPulse server, or this phone only. */
interface ReportSource {
    /** True when reports are shared with other drivers. */
    val shared: Boolean
    suspend fun inBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Report>
    suspend fun submit(type: ReportType, at: LatLng, note: String?): Report
    /** Returns the updated report, or null if the vote removed it. */
    suspend fun vote(id: String, stillThere: Boolean): Report?
    suspend fun alongRoute(points: List<LatLng>): List<Report>
}

/** Talks to a RoadPulse server, so reports are shared with every other driver using it. */
class RemoteReports(
    baseUrl: String,
    private val deviceId: String,
    private val myReports: MutableSet<String>,
    client: OkHttpClient = httpClient(),
) : ReportSource {
    override val shared = true
    private val api = retrofit(baseUrl, client).create(RoadPulseApi::class.java)

    override suspend fun inBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) =
        networkCall { api.inBox(minLat, minLon, maxLat, maxLon).map { it.toReport(myReports) } }

    override suspend fun submit(type: ReportType, at: LatLng, note: String?): Report = networkCall {
        val dto = api.submit(deviceId, SubmitBody(type.name, at.lat, at.lon, note))
        myReports += dto.id
        dto.toReport(myReports)
    }

    override suspend fun vote(id: String, stillThere: Boolean): Report? = networkCall {
        val response = api.vote(id, deviceId, VoteBody(stillThere))
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        response.body()?.toReport(myReports)
    }

    override suspend fun alongRoute(points: List<LatLng>): List<Report> = networkCall {
        // A 60 m corridor doesn't need every vertex of a long route.
        val step = points.size / 2000 + 1
        val thinned = points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
        api.alongRoute(RouteBody(thinned.map { listOf(it.lat, it.lon) })).map { it.toReport(myReports) }
    }
}

/**
 * Reports kept on this phone only, so the app is useful with no server at
 * all (anywhere in the world, even offline). Applies the same rules as the
 * server: nearby reports of one type merge, each type expires on its own,
 * and "still there" extends a report while "gone" removes it.
 */
class LocalReports(
    private val file: File?,
    private val clock: Clock = Clock.systemUTC(),
    private val mergeRadiusMeters: Double = 150.0,
) : ReportSource {
    override val shared = false
    private val lock = Mutex()
    private var reports: MutableList<Report>? = null

    @Serializable
    private data class Stored(val id: String, val type: String, val lat: Double, val lon: Double, val note: String?,
                              val confirmations: Int, val createdAt: String, val expiresAt: String)

    private val serializer = ListSerializer(Stored.serializer())

    private suspend fun all(): MutableList<Report> {
        reports?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                file?.takeIf { it.exists() }?.readText()?.let { text ->
                    json.decodeFromString(serializer, text).map {
                        Report(it.id, ReportType.valueOf(it.type), LatLng(it.lat, it.lon), it.note, it.confirmations, 0,
                            Instant.parse(it.createdAt), Instant.parse(it.expiresAt))
                    }
                }
            }.getOrNull() ?: emptyList() // a corrupt file starts fresh rather than crashing
        }
        return loaded.toMutableList().also { reports = it }
    }

    private suspend fun persist(list: List<Report>) {
        val f = file ?: return
        val text = json.encodeToString(serializer, list.map {
            Stored(it.id, it.type.name, it.position.lat, it.position.lon, it.note, it.confirmations, it.createdAt.toString(), it.expiresAt.toString())
        })
        withContext(Dispatchers.IO) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun MutableList<Report>.pruneExpired(now: Instant) = removeAll { !it.expiresAt.isAfter(now) }

    override suspend fun inBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Report> = lock.withLock {
        val list = all().apply { pruneExpired(clock.instant()) }
        list.filter { it.position.lat in minLat..maxLat && it.position.lon in minLon..maxLon }
    }

    override suspend fun submit(type: ReportType, at: LatLng, note: String?): Report = lock.withLock {
        val now = clock.instant()
        val list = all().apply { pruneExpired(now) }
        val nearby = list.filter { it.type == type }.minByOrNull { Geo.distance(it.position, at) }
            ?.takeIf { Geo.distance(it.position, at) <= mergeRadiusMeters }
        val result = if (nearby != null) {
            nearby.copy(confirmations = nearby.confirmations + 1, expiresAt = maxOf(nearby.expiresAt, now.plus(type.ttl)))
                .also { updated -> list[list.indexOf(nearby)] = updated }
        } else {
            Report(UUID.randomUUID().toString(), type, at, note?.trim()?.ifEmpty { null }, 1, 0, now, now.plus(type.ttl))
                .also { list += it }
        }
        persist(list)
        result
    }

    override suspend fun vote(id: String, stillThere: Boolean): Report? = lock.withLock {
        val now = clock.instant()
        val list = all().apply { pruneExpired(now) }
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return@withLock null
        val r = list[i]
        val result = if (stillThere) {
            r.copy(confirmations = r.confirmations + 1, expiresAt = maxOf(r.expiresAt, now.plus(r.type.ttl))).also { list[i] = it }
        } else {
            list.removeAt(i)
            null
        }
        persist(list)
        result
    }

    override suspend fun alongRoute(points: List<LatLng>): List<Report> = lock.withLock {
        val list = all().apply { pruneExpired(clock.instant()) }
        list.filter { Geo.matchToRoute(it.position, points).offRouteMeters <= 60.0 }
    }
}
