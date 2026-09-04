// Vite + Svelte SPA. Gradle drives npm so the whole project builds with one ./gradlew build,
// and exposes the compiled dist/ to :server through the "dist" configuration.
plugins {
    base
    alias(libs.plugins.node)
}

node {
    version = libs.versions.node.get()
    download = true
}

val npmBuild = tasks.register<com.github.gradle.node.npm.task.NpmTask>("npmBuild") {
    description = "Builds the web UI with Vite."
    group = "build"
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "build")
    inputs.dir("src")
    inputs.files("index.html", "package.json", "package-lock.json", "vite.config.ts", "svelte.config.js", "tsconfig.json")
    outputs.dir(layout.buildDirectory.dir("dist"))
}

val dist = configurations.create("dist") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(dist.name, layout.buildDirectory.dir("dist")) {
        builtBy(npmBuild)
    }
}

tasks.assemble {
    dependsOn(npmBuild)
}
