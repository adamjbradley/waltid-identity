import io.ktor.plugin.features.*

object Versions {
    const val KTOR_VERSION = "3.3.3"
    const val COROUTINES_VERSION = "1.10.2"
}

plugins {
    id("waltid.ktorbackend")   // Handles Kotlin, App config, Start scripts, Version props
    id("waltid.ktordocker")    // Handles Jib, Docker credentials, Platforms
}

group = "id.walt"

dependencies {
    /* -- KTOR -- */

    // Ktor server (minimal set for feature-flagged service)
    implementation("io.ktor:ktor-server-core-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-netty-jvm:${Versions.KTOR_VERSION}")
    implementation("io.ktor:ktor-server-host-common-jvm:${Versions.KTOR_VERSION}")

    /* -- Kotlin -- */

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.COROUTINES_VERSION}")

    /* -- Logging -- */
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    // Test
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("id.walt.verifyapi.MainKt")
}

ktor {
    docker {
        portMappings.set(listOf(DockerPortMapping(7010, 7010, DockerPortMappingProtocol.TCP)))
    }
}
