package id.walt.issuer.tenant

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

class IssuerFeatureIsolationTest {

    @AfterEach
    fun tearDown() {
        IssuerTenantStore.resetForTesting()
    }

    private suspend fun assertServiceUnavailable(response: HttpResponse, label: String) {
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, "Expected 503 for $label")
        assertTrue(response.bodyAsText().contains("not enabled"), "Body should indicate feature not enabled for $label")
    }

    @Test
    fun `all admin issuer endpoints return 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        // No store initialized = feature disabled
        assertServiceUnavailable(client.get("/admin/issuer"), "GET /admin/issuer")
        assertServiceUnavailable(
            client.post("/admin/issuer") {
                contentType(ContentType.Application.Json)
                setBody("""{"legalName":"X","country":"AU","domain":"x.com","contactEmail":"x@x.com"}""")
            },
            "POST /admin/issuer"
        )
        assertServiceUnavailable(client.get("/admin/issuer/some-id"), "GET /admin/issuer/{id}")
        assertServiceUnavailable(
            client.put("/admin/issuer/some-id") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            },
            "PUT /admin/issuer/{id}"
        )
        assertServiceUnavailable(client.delete("/admin/issuer/some-id"), "DELETE /admin/issuer/{id}")
        assertServiceUnavailable(
            client.post("/admin/issuer/some-id/certificate/generate"),
            "POST /admin/issuer/{id}/certificate/generate"
        )
        assertServiceUnavailable(
            client.post("/admin/issuer/some-id/certificate/upload") {
                contentType(ContentType.Application.Json)
                setBody("""{"issuerKeyJwk":{},"x5Chain":[]}""")
            },
            "POST /admin/issuer/{id}/certificate/upload"
        )
        assertServiceUnavailable(
            client.get("/admin/issuer/some-id/certificate/download"),
            "GET /admin/issuer/{id}/certificate/download"
        )
        assertServiceUnavailable(
            client.put("/admin/issuer/some-id/credentials") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            },
            "PUT /admin/issuer/{id}/credentials"
        )
    }
}
