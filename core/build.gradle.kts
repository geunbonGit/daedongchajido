plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // QR codes for share cards (MIT, Project Nayuki). Not in the version catalog on purpose: core-only dependency.
    implementation("io.nayuki:qrcodegen:1.8.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    // reads the generated QR modules back in the share tests
    testImplementation("com.google.zxing:core:3.5.3")
}

tasks.test {
    useJUnit()
    // ./gradlew :core:test -PodomapWriteVectors rewrites src/test/resources/share/vectors.json (share-format test vectors for the web viewer)
    systemProperty("odomap.writeVectors", if (project.hasProperty("odomapWriteVectors")) "true" else "false")
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
