// Spring Boot application: hosts the Netty SMPP listener, the REST/SSE API and the bundled web UI.
plugins {
    java
    alias(libs.plugins.spring.boot)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

// Consumes the built SPA from :ui and packages it under static/ so the jar is self-contained.
val ui = configurations.create("ui") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":smpp"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(libs.springdoc.webmvc.ui)
    implementation("org.yaml:snakeyaml")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Independent SMPP client for end-to-end tests against the listener.
    testImplementation(libs.cloudhopper.smpp)
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    ui(project(mapOf("path" to ":ui", "configuration" to "dist")))
}

tasks.processResources {
    from(ui) {
        into("static")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

springBoot {
    buildInfo()
}

tasks.bootBuildImage {
    imageName = "ghcr.io/cellophane/cellophane:${project.version}"
}
