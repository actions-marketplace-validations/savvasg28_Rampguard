plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":adapters:play"))
    implementation(project(":adapters:notifier-slack"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.clikt)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("io.github.savvasg28.rampguard.cli.MainKt")
    applicationName = "rampguard"
}

tasks.shadowJar {
    archiveBaseName.set("rampguard")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
