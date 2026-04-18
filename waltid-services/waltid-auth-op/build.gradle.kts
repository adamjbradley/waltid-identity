import io.ktor.plugin.features.*

object Versions {
    const val KTOR_VERSION = "3.3.3"
}

plugins {
    id("waltid.ktorbackend")   // Handles Kotlin, App config, Start scripts, Version props
    id("waltid.ktordocker")    // Handles Jib, Docker credentials, Platforms
}

group = "id.walt"

dependencies {
    api(project(":waltid-services:waltid-service-commons"))

    /* -- KTOR server -- */
    implementation("io.ktor:ktor-server-core-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-netty-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-html-builder-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-auth-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-status-pages-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-call-logging-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-sessions-jvm:${Versions.KTOR_VERSION}")

    /* -- Serialization -- */
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:${Versions.KTOR_VERSION}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    /* -- In-memory state stores (TTL caches) -- */
    // Caffeine backs the four state stores (auth-request, auth-code, session,
    // vp-session) with expireAfterWrite TTLs. Ticker is injected by tests so
    // expiration can be driven by a TestClock. Other walt services pull 2.9.3
    // transitively via ktor-authnz; we declare 3.1.8 directly to get the Java-21
    // tuned build and an explicit first-party dependency.
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    /* -- Ktor HTTP client (upstream OIDC) --
     * Used by id.walt.authop.upstream.OidcClient to call upstream IdPs
     * (Keycloak, Authentik, etc.) for OIDC realms. OkHttp engine matches the
     * repo-wide convention (issuer-api / verifier-api / wallet-api / etc.).
     * Tests swap the engine for MockEngine via the OidcClient ctor. */
    implementation("io.ktor:ktor-client-core-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-client-okhttp-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:${Versions.KTOR_VERSION}")

    /* -- Test -- */
    testImplementation(identityLibs.bundles.waltid.ktortesting)
    testImplementation("io.mockk:mockk:1.14.2")
    // Client-side content negotiation lets tests decode JSON responses via
    // `response.body<JsonObject>()` without manual parsing.
    testImplementation("io.ktor:ktor-client-content-negotiation:${Versions.KTOR_VERSION}")
    // MockEngine fakes the upstream OIDC OP in OidcClientTest. Not in the
    // default waltid-ktortesting bundle (it's specific to client-side tests).
    testImplementation("io.ktor:ktor-client-mock:${Versions.KTOR_VERSION}")
}

application {
    mainClass.set("id.walt.authop.MainKt")
}

ktor {
    docker {
        portMappings.set(listOf(DockerPortMapping(7005, 7005, DockerPortMappingProtocol.TCP)))
    }
}
