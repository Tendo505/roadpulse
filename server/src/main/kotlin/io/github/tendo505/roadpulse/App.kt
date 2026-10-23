package io.github.tendo505.roadpulse

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.time.Duration

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
class RoadPulseApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** Seeds a few reports around central Kuala Lumpur so a fresh server has something to show. */
    @Bean
    fun demoSeed(service: ReportService, repo: ReportRepository, props: RoadPulseProperties, clock: Clock) = ApplicationRunner {
        if (!props.seedDemoData || repo.countActive(clock.instant()) > 0) return@ApplicationRunner
        listOf(
            Triple(ReportType.JAM, LatLon(3.1497, 101.7128), "Slow traffic near KLCC"),
            Triple(ReportType.ACCIDENT, LatLon(3.1336, 101.6871), "Two cars, left lane blocked"),
            Triple(ReportType.POLICE, LatLon(3.1577, 101.7116), null),
            Triple(ReportType.HAZARD, LatLon(3.1421, 101.6957), "Pothole"),
            Triple(ReportType.CLOSURE, LatLon(3.1488, 101.6943), "Road works"),
        ).forEachIndexed { i, (type, at, note) -> service.submit(type, at, note, "demo-seed-$i") }
        LoggerFactory.getLogger(javaClass).info("Seeded demo reports around Kuala Lumpur")
    }
}

@ConfigurationProperties("roadpulse")
data class RoadPulseProperties(
    val mergeRadiusMeters: Double = 150.0,
    val maxReportsPerWindow: Int = 10,
    val rateWindow: Duration = Duration.ofMinutes(10),
    val cleanupInterval: Duration = Duration.ofMinutes(10),
    val seedDemoData: Boolean = true,
)

/**
 * Hosting platforms (Render, Heroku, Railway) provide `DATABASE_URL` as
 * `postgres://user:pass@host:port/db`. Spring needs a JDBC URL plus separate
 * credentials, so translate it unless the datasource was configured explicitly.
 */
fun jdbcPropertiesFrom(databaseUrl: String?): Map<String, String> {
    if (databaseUrl.isNullOrBlank() || !databaseUrl.matches(Regex("^postgres(ql)?://.*"))) return emptyMap()
    val uri = java.net.URI(databaseUrl)
    val (user, pass) = (uri.rawUserInfo ?: ":").split(":", limit = 2).map { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        .let { it[0] to it.getOrElse(1) { "" } }
    val port = if (uri.port == -1) 5432 else uri.port
    return mapOf(
        "spring.datasource.url" to "jdbc:postgresql://${uri.host}:$port${uri.rawPath}${uri.rawQuery?.let { "?$it" } ?: ""}",
        "spring.datasource.username" to user,
        "spring.datasource.password" to pass,
    )
}

fun main(args: Array<String>) {
    if (System.getenv("SPRING_DATASOURCE_URL") == null) {
        jdbcPropertiesFrom(System.getenv("DATABASE_URL")).forEach { (k, v) -> System.setProperty(k, v) }
    }
    runApplication<RoadPulseApplication>(*args)
}
