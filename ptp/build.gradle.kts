import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.register<JavaExec>("runFakeCamera") {
    group = "verification"
    description = "Serves a pretend Nikon D5600 on 127.0.0.1:15740; the emulator reaches it at 10.0.2.2."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.viewfinder.ptp.FakeCameraMainKt")
    systemProperty("java.awt.headless", "true")
    findProperty("kbps")?.let { systemProperty("fake.kbps", it.toString()) }
}
