package id.walt.openid4vp.verifier.rp

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RpFeatureIsolationTest {

    @AfterEach
    fun tearDown() {
        RelyingPartyStore.resetForTesting()
    }

    private suspend fun assertServiceUnavailable(response: io.ktor.client.statement.HttpResponse, label: String) {
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, "Expected 503 for $label")
        assertTrue(response.bodyAsText().contains("not enabled"), "Body should indicate feature not enabled for $label")
    }

    @Test
    fun `all admin rp endpoints return 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }

        // No store initialized = feature disabled
        assertServiceUnavailable(client.get("/admin/rp"), "GET /admin/rp")
        assertServiceUnavailable(
            client.post("/admin/rp") {
                contentType(ContentType.Application.Json)
                setBody("""{"legalName":"X","country":"AU","domain":"x.com","contactEmail":"x@x.com"}""")
            },
            "POST /admin/rp"
        )
        assertServiceUnavailable(client.get("/admin/rp/some-id"), "GET /admin/rp/{id}")
        assertServiceUnavailable(
            client.put("/admin/rp/some-id") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            },
            "PUT /admin/rp/{id}"
        )
        assertServiceUnavailable(client.delete("/admin/rp/some-id"), "DELETE /admin/rp/{id}")
        assertServiceUnavailable(
            client.post("/admin/rp/some-id/certificate/generate"),
            "POST /admin/rp/{id}/certificate/generate"
        )
        assertServiceUnavailable(
            client.post("/admin/rp/some-id/certificate/upload") {
                contentType(ContentType.Application.Json)
                setBody("""{"certificatePem":"test"}""")
            },
            "POST /admin/rp/{id}/certificate/upload"
        )
        assertServiceUnavailable(
            client.get("/admin/rp/some-id/certificate/download"),
            "GET /admin/rp/{id}/certificate/download"
        )
        assertServiceUnavailable(
            client.put("/admin/rp/some-id/intended-use") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            },
            "PUT /admin/rp/{id}/intended-use"
        )
    }
}
