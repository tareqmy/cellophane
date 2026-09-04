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
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}
