object Versions {
    const val KTOR_VERSION = "3.3.3"
}

plugins {
    id("waltid.ktorbackend")   // Handles Kotlin, App config, Start scripts, Version props
}

group = "id.walt"

dependencies {
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

    /* -- Logging -- */
    implementation("ch.qos.logback:logback-classic:1.5.16")

    /* -- Test -- */
    testImplementation("io.ktor:ktor-server-test-host:${Versions.KTOR_VERSION}")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.mockk:mockk:1.14.2")
}

application {
    mainClass.set("id.walt.authop.MainKt")
}
