import io.ktor.plugin.features.*

object Versions {
    const val KTOR_VERSION = "3.3.3"
    const val COROUTINES_VERSION = "1.10.2"
    const val HOPLITE_VERSION = "2.9.0"
    const val EXPOSED_VERSION = "1.0.0-rc-1"
}

plugins {
    id("waltid.ktorbackend")   // Handles Kotlin, App config, Start scripts, Version props
    id("waltid.ktordocker")    // Handles Jib, Docker credentials, Platforms
}

group = "id.walt"

dependencies {
    // Service commons (provides persistence, redis/jedis, config, OpenAPI, logging, etc.)
    api(project(":waltid-services:waltid-service-commons"))

    /* -- KTOR SERVER -- */
    implementation("io.ktor:ktor-server-core-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-auth-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-sessions-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-auto-head-response-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-double-receive-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-host-common-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-status-pages-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-compression-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-cors-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-forwarded-header-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-call-logging-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-call-id-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-cio-jvm:${Versions.KTOR_VERSION}")

    /* -- OPENAPI / SWAGGER -- */
    implementation("io.github.smiley4:ktor-swagger-ui:5.3.0")

    /* -- KTOR CLIENT (for verifier-api2 calls) -- */
    implementation("io.ktor:ktor-client-core-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-client-serialization-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-client-content-negotiation:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-client-json-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-client-okhttp-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-client-logging-jvm:${Versions.KTOR_VERSION}")

    /* -- KOTLINX -- */
    // Serialization
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:${Versions.KTOR_VERSION}")

    // Date
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.COROUTINES_VERSION}")

    // Uuid
    implementation("app.softwork:kotlinx-uuid-core:0.1.6")

    /* -- DATABASE (Exposed ORM) -- */
    implementation("org.jetbrains.exposed:exposed-core:${Versions.EXPOSED_VERSION}")
    implementation("org.jetbrains.exposed:exposed-jdbc:${Versions.EXPOSED_VERSION}")
    implementation("org.jetbrains.exposed:exposed-dao:${Versions.EXPOSED_VERSION}")
    implementation("org.jetbrains.exposed:exposed-java-time:${Versions.EXPOSED_VERSION}")
    implementation("org.jetbrains.exposed:exposed-json:${Versions.EXPOSED_VERSION}")

    // Database drivers
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.2.1")

    /* -- QR CODE GENERATION -- */
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.google.zxing:javase:3.5.2")

    /* -- CONFIG -- */
    implementation("com.sksamuel.hoplite:hoplite-core:${Versions.HOPLITE_VERSION}")
    implementation("com.sksamuel.hoplite:hoplite-hocon:${Versions.HOPLITE_VERSION}")
    implementation("com.sksamuel.hoplite:hoplite-hikaricp:${Versions.HOPLITE_VERSION}")

    /* -- LOGGING -- */
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.5")
    implementation("org.slf4j:jul-to-slf4j:2.0.17")
    implementation("io.klogging:klogging-jvm:0.11.6")
    implementation("io.klogging:slf4j-klogging:0.11.6")

    /* -- TEST -- */
    testImplementation(identityLibs.bundles.waltid.ktortesting)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.COROUTINES_VERSION}")
    testImplementation("io.mockk:mockk:1.13.16")
}

application {
    mainClass.set("id.walt.verifyapi.MainKt")
}

ktor {
    docker {
        portMappings.set(listOf(DockerPortMapping(7010, 7010, DockerPortMappingProtocol.TCP)))
    }
}
