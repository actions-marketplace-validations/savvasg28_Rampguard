plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)

    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotest.assertions)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * Architecture rule: core has zero I/O dependencies.
 *
 * Only the Kotlin standard library, kotlinx (coroutines, serialization) and the
 * YAML parser (kaml and its pure parsing dependencies) may appear on core's
 * runtime classpath. Anything that smells of HTTP, Google, GitHub or git fails the build.
 */
val allowedCoreDependencyGroups =
    setOf(
        "org.jetbrains.kotlin",
        "org.jetbrains.kotlinx",
        "org.jetbrains",
        "com.charleskorn.kaml",
        "it.krzeminski",
        "com.squareup.okio",
        "net.thauvin.erik.urlencoder",
    )

val checkCoreDependencies by tasks.registering {
    group = "verification"
    description = "Fails the build if core depends on anything beyond Kotlin, kotlinx and the YAML parser."
    val runtimeClasspath = configurations.runtimeClasspath
    val groups =
        runtimeClasspath.map { cp ->
            cp.resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.toString() }
        }
    inputs.property("resolved", groups)
    doLast {
        val offenders =
            groups.get().filterNot { id -> allowedCoreDependencyGroups.any { id.startsWith("$it:") } }
        check(offenders.isEmpty()) {
            "core must stay free of I/O libraries. Forbidden dependencies on core runtime classpath:\n" +
                offenders.joinToString("\n") { "  - $it" }
        }
    }
}

tasks.named("check") { dependsOn(checkCoreDependencies) }
