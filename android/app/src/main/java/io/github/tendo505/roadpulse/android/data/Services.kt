package io.github.tendo505.roadpulse.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Problems the UI can explain. */
sealed class AppError(message: String) : Exception(message) {
    class Offline : AppError("offline")
    class NoRoute : AppError("no_route")
    class RateLimited : AppError("rate_limited")
    class AlreadyVoted : AppError("already_voted")
    class Server(val code: Int) : AppError("server_$code")
}

val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

/**
 * The free public map services (OSRM, Photon, OpenStreetMap) ask apps to
 * identify themselves, so every request carries a descriptive User-Agent.
 */
fun httpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", "RoadPulse/1.0 (+https://github.com/Tendo505/roadpulse)").build())
    }
    .build()

internal fun retrofit(baseUrl: String, client: OkHttpClient) = Retrofit.Builder()
    .baseUrl(baseUrl.trimEnd('/') + "/")
    .client(client)
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .build()

suspend fun <T> networkCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    throw when (e.code()) {
        429 -> AppError.RateLimited()
        409 -> AppError.AlreadyVoted()
        else -> AppError.Server(e.code())
    }
} catch (e: IOException) {
    throw AppError.Offline()
}

// ---------- routing: OSRM ----------

@Serializable data class OsrmResponse(val code: String, val routes: List<OsrmRoute> = emptyList())
@Serializable data class OsrmRoute(val distance: Double, val duration: Double, val geometry: OsrmGeometry, val legs: List<OsrmLeg>)
@Serializable data class OsrmGeometry(val coordinates: List<List<Double>>)
@Serializable data class OsrmLeg(val steps: List<OsrmStep> = emptyList())
@Serializable data class OsrmStep(val distance: Double, val duration: Double, val name: String? = null, val maneuver: OsrmManeuver)
@Serializable data class OsrmManeuver(val type: String, val modifier: String? = null, val location: List<Double>, val exit: Int? = null)

interface OsrmApi {
    @GET("route/v1/driving/{coords}?overview=full&geometries=geojson&steps=true")
    suspend fun route(@Path("coords", encoded = true) coords: String): OsrmResponse
}

/** Converts an OSRM response (coordinates are [lon, lat]) into our model. */
fun OsrmResponse.toRoute(): Route {
    val r = routes.firstOrNull() ?: throw AppError.NoRoute()
    return Route(
        points = r.geometry.coordinates.map { LatLng(it[1], it[0]) },
        distanceMeters = r.distance,
        durationSeconds = r.duration,
        steps = r.legs.flatMap { it.steps }.map { s ->
            Step(
                Maneuver(s.maneuver.type, s.maneuver.modifier, s.name?.takeIf { it.isNotBlank() }, s.maneuver.exit,
                    LatLng(s.maneuver.location[1], s.maneuver.location[0])),
                s.distance, s.duration,
            )
        },
    )
}

open class Router(baseUrl: String = "https://router.project-osrm.org", client: OkHttpClient = httpClient()) {
    private val api = retrofit(baseUrl, client).create(OsrmApi::class.java)

    open suspend fun route(from: LatLng, to: LatLng): Route = networkCall {
        val coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", from.lon, from.lat, to.lon, to.lat)
        val response = api.route(coords)
        if (response.code != "Ok") throw AppError.NoRoute()
        response.toRoute()
    }
}

// ---------- search: Photon (OpenStreetMap geocoder) ----------

@Serializable data class PhotonResponse(val features: List<PhotonFeature> = emptyList())
@Serializable data class PhotonFeature(val geometry: PhotonGeometry, val properties: PhotonProperties)
@Serializable data class PhotonGeometry(val coordinates: List<Double>)
@Serializable data class PhotonProperties(
    val name: String? = null, val street: String? = null, val housenumber: String? = null,
    val city: String? = null, val state: String? = null, val country: String? = null,
)

interface PhotonApi {
    @GET("api/")
    suspend fun search(
        @Query("q") q: String, @Query("limit") limit: Int = 8,
        @Query("lat") lat: Double? = null, @Query("lon") lon: Double? = null, @Query("lang") lang: String? = null,
    ): PhotonResponse
}

fun PhotonFeature.toPlace(): Place {
    val p = properties
    val street = listOfNotNull(p.housenumber, p.street).joinToString(" ").ifBlank { null }
    val name = p.name ?: street ?: p.city ?: "Unnamed place"
    val detail = listOfNotNull(street.takeIf { it != name }, p.city.takeIf { it != name }, p.country).distinct().joinToString(", ").ifBlank { null }
    return Place(name, detail, LatLng(geometry.coordinates[1], geometry.coordinates[0]))
}

open class Search(baseUrl: String = "https://photon.komoot.io", client: OkHttpClient = httpClient()) {
    private val api = retrofit(baseUrl, client).create(PhotonApi::class.java)

    /** Results are biased toward [near] (usually the user's position) when given. */
    open suspend fun search(query: String, near: LatLng?): List<Place> = networkCall {
        // Photon supports a few UI languages; anything else falls back to local names.
        val lang = Locale.getDefault().language.takeIf { it in setOf("en", "de", "fr", "it") }
        api.search(query, lat = near?.lat, lon = near?.lon, lang = lang).features.map { it.toPlace() }
    }
}

// ---------- RoadPulse server ----------

@Serializable data class ReportDto(
    val id: String, val type: String, val lat: Double, val lon: Double, val note: String? = null,
    val confirmations: Int, val dismissals: Int, val createdAt: String, val expiresAt: String, val alongRouteMeters: Int? = null,
) {
    fun toReport(myReportIds: Set<String>) = Report(
        id, ReportType.valueOf(type), LatLng(lat, lon), note, confirmations, dismissals,
        Instant.parse(createdAt), Instant.parse(expiresAt), mine = id in myReportIds,
    )
}
@Serializable data class SubmitBody(val type: String, val lat: Double, val lon: Double, val note: String? = null)
@Serializable data class VoteBody(val stillThere: Boolean)
@Serializable data class RouteBody(val points: List<List<Double>>, val corridorMeters: Double = 60.0)

interface RoadPulseApi {
    @GET("api/reports")
    suspend fun inBox(@Query("minLat") minLat: Double, @Query("minLon") minLon: Double, @Query("maxLat") maxLat: Double, @Query("maxLon") maxLon: Double): List<ReportDto>
    @POST("api/reports") suspend fun submit(@Header("X-Device-Id") device: String, @Body body: SubmitBody): ReportDto
    @POST("api/reports/{id}/votes") suspend fun vote(@Path("id") id: String, @Header("X-Device-Id") device: String, @Body body: VoteBody): retrofit2.Response<ReportDto>
    @POST("api/reports/along-route") suspend fun alongRoute(@Body body: RouteBody): List<ReportDto>
}
