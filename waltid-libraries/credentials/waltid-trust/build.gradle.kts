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
        outputModuleName = "trust"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(identityLibs.kotlinx.serialization.json)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

            // walt.id - DigitalCredential is part of TrustService public API
            api(project(":waltid-libraries:credentials:waltid-digital-credentials"))
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
        name.set("walt.id Trust")
        description.set("walt.id Trust Service interfaces for EUDI trust framework integration")
    }
}
