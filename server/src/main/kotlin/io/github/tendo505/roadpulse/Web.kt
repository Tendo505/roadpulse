package io.github.tendo505.roadpulse

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI
import java.time.Instant
import java.util.UUID

// ---------- DTOs ----------

data class SubmitReportRequest(
    @field:NotNull val type: ReportType?,
    @field:NotNull @field:DecimalMin("-90") @field:DecimalMax("90") val lat: Double?,
    @field:NotNull @field:DecimalMin("-180") @field:DecimalMax("180") val lon: Double?,
    @field:Size(max = 140) val note: String? = null,
)

data class VoteRequest(@field:NotNull val stillThere: Boolean?)

data class RouteQuery(
    /** Route as [[lat, lon], ...], e.g. the decoded geometry from a routing engine. */
    @field:NotNull @field:Size(min = 2, max = 5000) val points: List<List<Double>>?,
    val corridorMeters: Double = 60.0,
)

data class ReportResponse(
    val id: UUID,
    val type: ReportType,
    val lat: Double,
    val lon: Double,
    val note: String?,
    val confirmations: Int,
    val dismissals: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
    val merged: Boolean? = null,
    val alongRouteMeters: Int? = null,
) {
    companion object {
        fun of(r: Report, merged: Boolean? = null, along: Double? = null) = ReportResponse(
            r.id, r.type, r.position.lat, r.position.lon, r.note, r.confirmations, r.dismissals, r.createdAt, r.expiresAt,
            merged, along?.toInt(),
        )
    }
}

private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

private fun device(id: String?): String {
    require(id != null && DEVICE_ID.matches(id)) { "X-Device-Id header required: 8-64 letters, digits, '-' or '_'" }
    return id
}

// ---------- controller ----------

@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports")
class ReportController(private val service: ReportService) {

    @PostMapping
    @Operation(summary = "Report something on the road. Nearby reports of the same type are merged.")
    fun submit(@RequestHeader("X-Device-Id", required = false) deviceId: String?, @Valid @RequestBody req: SubmitReportRequest): ResponseEntity<ReportResponse> {
        val result = service.submit(req.type!!, LatLon(req.lat!!, req.lon!!), req.note, device(deviceId))
        val body = ReportResponse.of(result.report, merged = result.merged)
        return if (result.merged) ResponseEntity.ok(body)
        else ResponseEntity.created(URI.create("/api/reports/${result.report.id}")).body(body)
    }

    @GetMapping
    @Operation(summary = "Active reports inside the visible map area")
    fun inBox(
        @RequestParam minLat: Double, @RequestParam minLon: Double,
        @RequestParam maxLat: Double, @RequestParam maxLon: Double,
    ): List<ReportResponse> = service.inBox(minLat, minLon, maxLat, maxLon).map { ReportResponse.of(it) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ReportResponse = ReportResponse.of(service.get(id))

    @PostMapping("/{id}/votes")
    @Operation(summary = "Vote whether a report is still there. Enough 'gone' votes remove it.")
    fun vote(
        @PathVariable id: UUID,
        @RequestHeader("X-Device-Id", required = false) deviceId: String?,
        @Valid @RequestBody req: VoteRequest,
    ): ResponseEntity<ReportResponse> {
        val updated = service.vote(id, device(deviceId), req.stillThere!!)
        return if (updated == null) ResponseEntity.noContent().build() else ResponseEntity.ok(ReportResponse.of(updated))
    }

    @PostMapping("/along-route")
    @Operation(summary = "Reports on or near a route, ordered by distance along it")
    fun alongRoute(@Valid @RequestBody q: RouteQuery): List<ReportResponse> {
        val route = q.points!!.map {
            require(it.size == 2) { "each point must be [lat, lon]" }
            LatLon(it[0], it[1])
        }
        return service.alongRoute(route, q.corridorMeters).map { ReportResponse.of(it.report, along = it.alongRouteMeters) }
    }
}

// ---------- errors as RFC 9457 problem+json ----------

@RestControllerAdvice
class ApiErrors : ResponseEntityExceptionHandler() {

    @ExceptionHandler(ReportException::class)
    fun report(e: ReportException): ResponseEntity<ProblemDetail> {
        val status = when (e) {
            is ReportNotFound -> HttpStatus.NOT_FOUND
            is AlreadyVoted, is OwnReport -> HttpStatus.CONFLICT
            is RateLimited -> HttpStatus.TOO_MANY_REQUESTS
        }
        val problem = ProblemDetail.forStatusAndDetail(status, e.message ?: status.reasonPhrase)
        val builder = ResponseEntity.status(status)
        if (e is RateLimited) builder.header(HttpHeaders.RETRY_AFTER, (e.retryAfter.seconds + 1).toString())
        return builder.body(problem)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "Bad request")

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException, headers: HttpHeaders, status: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed").apply {
            setProperty("errors", ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") })
        }
        return ResponseEntity.badRequest().body(problem)
    }
}
