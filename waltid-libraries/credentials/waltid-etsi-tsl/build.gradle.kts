@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("waltid.multiplatform.library")
    id("waltid.publish.maven")
    id("waltid.publish.npm")
}

group = "id.walt.credentials"

kotlin {
    js(IR) {
        outputModuleName = "etsi-tsl"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(identityLibs.kotlinx.serialization.json)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            implementation(identityLibs.oshai.kotlinlogging)

            // HTTP client
            implementation(identityLibs.bundles.waltid.ktor.client)

            // walt.id
            implementation(project(":waltid-libraries:credentials:waltid-trust"))
            implementation(project(":waltid-libraries:crypto:waltid-x509"))
            implementation(project(":waltid-libraries:crypto:waltid-crypto"))
        }
        commonTest.dependencies {
            implementation(kotlin("test-common"))
            implementation(kotlin("test-annotations-common"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        jvmMain.dependencies {
        }
        jvmTest.dependencies {
            implementation("org.slf4j:slf4j-simple:2.0.17")
            implementation(kotlin("test"))
            implementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
            implementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
        }
        jsMain.dependencies {
        }
        jsTest.dependencies {
            implementation(kotlin("test-js"))
        }
    }
}

mavenPublishing {
    pom {
        name.set("walt.id ETSI TSL")
        description.set("walt.id ETSI TS 119 612 Trust Service List parser and fetcher")
    }
}
