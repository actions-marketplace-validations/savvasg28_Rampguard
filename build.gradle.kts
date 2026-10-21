import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover)
}

allprojects {
    group = "io.github.savvasg28.rampguard"
    version = providers.gradleProperty("rampguard.version").getOrElse("0.1.0-SNAPSHOT")
}

/** Google library types stay inside adapters/play, behind the ports. */
val checkGoogleTypesConfined by tasks.registering {
    group = "verification"
    description = "Fails the build if any module other than adapters/play imports com.google.*"
    val sources =
        fileTree(rootDir) {
            include("**/src/**/*.kt")
            exclude("adapters/play/**", "**/build/**")
        }
    inputs.files(sources)
    doLast {
        val offenders = sources.files.filter { file -> file.readLines().any { it.startsWith("import com.google.") } }
        check(offenders.isEmpty()) {
            "Google types must stay in adapters/play. Offending files:\n" +
                offenders.joinToString("\n") { "  - ${it.relativeTo(rootDir)}" }
        }
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.named("check") { dependsOn(rootProject.tasks.named("checkGoogleTypesConfined")) }
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                allWarningsAsErrors.set(true)
            }
        }
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "io.gitlab.arturbosch.detekt")
        apply(plugin = "org.jetbrains.kotlinx.kover")

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(files())
}

subprojects {
    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        }
        tasks.withType<Detekt>().configureEach {
            jvmTarget = "21"
        }
    }
}

dependencies {
    kover(project(":core"))
}

kover {
    reports {
        verify {
            rule("core line coverage") {
                minBound(90)
            }
        }
        total {
            html { onCheck.set(false) }
            xml { onCheck.set(true) }
        }
    }
}
