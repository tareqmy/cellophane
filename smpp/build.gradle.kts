// SMPP 3.4 codec and GSM 03.38 / UDH helpers. No Spring dependency by design,
// so this module can be published as a standalone library.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
    withSourcesJar()
}

dependencies {
    api(platform(libs.netty.bom))
    api(libs.netty.codec)
    api(libs.netty.handler)
    api(libs.netty.transport)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // Independent SMPP implementation (Netty 3 based) used as the client in conformance tests.
    testImplementation(libs.cloudhopper.smpp)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

// Production code must be warning-free; tests talk to cloudhopper's raw generic types.
tasks.compileJava {
    options.compilerArgs.add("-Werror")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
}
