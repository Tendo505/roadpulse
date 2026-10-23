package io.github.tendo505.roadpulse

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@AutoConfigureMockMvc
class ApiTest : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    private fun report(device: String, type: String = "ACCIDENT", lat: Double = 3.139, lon: Double = 101.6869) =
        mvc.post("/api/reports") {
            header("X-Device-Id", device)
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"$type","lat":$lat,"lon":$lon,"note":"Left lane"}"""
        }

    @Test
    fun `submit, merge, view in area, vote`() {
        val created = report("phone-aaaa").andExpect {
            status { isCreated() }
            jsonPath("$.type") { value("ACCIDENT") }
            jsonPath("$.confirmations") { value(1) }
        }.andReturn().response.contentAsString
        val id = json.readTree(created)["id"].asText()

        report("phone-bbbb", lat = 3.1395).andExpect {
            status { isOk() }
            jsonPath("$.merged") { value(true) }
            jsonPath("$.confirmations") { value(2) }
        }

        mvc.get("/api/reports?minLat=3.1&minLon=101.6&maxLat=3.2&maxLon=101.8").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(id) }
        }

        mvc.post("/api/reports/$id/votes") {
            header("X-Device-Id", "phone-cccc"); contentType = MediaType.APPLICATION_JSON; content = """{"stillThere":false}"""
        }.andExpect { status { isOk() }; jsonPath("$.dismissals") { value(1) } }

        mvc.post("/api/reports/$id/votes") {
            header("X-Device-Id", "phone-cccc"); contentType = MediaType.APPLICATION_JSON; content = """{"stillThere":true}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `along-route query over HTTP`() {
        report("phone-aaaa", type = "POLICE", lat = 3.1391, lon = 101.70)
        mvc.post("/api/reports/along-route") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"points":[[3.139,101.6869],[3.139,101.7069]],"corridorMeters":60}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].type") { value("POLICE") }
            jsonPath("$[0].alongRouteMeters") { exists() }
        }
    }

    @Test
    fun `device id is required and validated`() {
        mvc.post("/api/reports") {
            contentType = MediaType.APPLICATION_JSON; content = """{"type":"JAM","lat":3.1,"lon":101.6}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("X-Device-Id")) } }
        report("bad id!").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `validation and rate limit errors are problem+json`() {
        mvc.post("/api/reports") {
            header("X-Device-Id", "phone-aaaa"); contentType = MediaType.APPLICATION_JSON
            content = """{"type":"JAM","lat":95,"lon":101.6}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.errors.lat") { exists() } }

        repeat(10) { i -> report("phone-rate", lat = 3.0 + i * 0.01).andExpect { status { is2xxSuccessful() } } }
        report("phone-rate", lat = 3.5).andExpect {
            status { isTooManyRequests() }
            header { exists("Retry-After") }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `docs and health are served`() {
        mvc.get("/v3/api-docs").andExpect { jsonPath("$.paths['/api/reports/along-route'].post") { exists() } }
        mvc.get("/actuator/health").andExpect { jsonPath("$.status") { value("UP") } }
    }
}
