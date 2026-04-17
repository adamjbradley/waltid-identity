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

    /* -- Test -- */
    testImplementation(identityLibs.bundles.waltid.ktortesting)
    testImplementation("io.mockk:mockk:1.14.2")
}

application {
    mainClass.set("id.walt.authop.MainKt")
}

ktor {
    docker {
        portMappings.set(listOf(DockerPortMapping(7005, 7005, DockerPortMappingProtocol.TCP)))
    }
}
