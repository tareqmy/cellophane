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

// Runs the real container image and talks to it over SMPP and HTTP. Needs Docker, so it is not part of
// `build`; run it with ./gradlew :server:imageTest (CI does, before publishing an image).
testing {
    suites {
        register<JvmTestSuite>("imageTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(project())
                implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
                implementation("org.testcontainers:testcontainers")
                implementation("org.springframework:spring-web")
                implementation("tools.jackson.core:jackson-databind")
                implementation("org.awaitility:awaitility")
                implementation(libs.assertj.core)
                implementation(libs.cloudhopper.smpp)
                runtimeOnly(libs.slf4j.simple)
            }
            targets.all {
                testTask.configure {
                    dependsOn(tasks.bootBuildImage)
                    systemProperty("cellophane.image", tasks.bootBuildImage.get().imageName.get())
                    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
                    shouldRunAfter(tasks.test)
                }
            }
        }
    }
}

// Container image via Paketo buildpacks (no Dockerfile). Locally: ./gradlew :server:bootBuildImage
// The publish workflow passes -PimageRepository=ghcr.io/<owner>/cellophane and the registry credentials.
val imageRepository = providers.gradleProperty("imageRepository").orElse("cellophane/cellophane").get()
val isSnapshot = project.version.toString().endsWith("-SNAPSHOT")

tasks.bootBuildImage {
    imageName = "$imageRepository:${project.version}"
    if (!isSnapshot) {
        tags = listOf("$imageRepository:latest")
    }
    environment = mapOf(
        "BP_JVM_VERSION" to libs.versions.java.get(),
        // Small heap is plenty: the inbox is bounded and Netty is off-heap.
        "BPL_JVM_THREAD_COUNT" to "50",
    )
    publish = providers.gradleProperty("publishImage").map { it.toBoolean() }.orElse(false).get()
    docker {
        publishRegistry {
            username = providers.environmentVariable("REGISTRY_USERNAME").orElse("").get()
            password = providers.environmentVariable("REGISTRY_PASSWORD").orElse("").get()
        }
    }
}
