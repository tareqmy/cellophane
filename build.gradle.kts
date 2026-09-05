// Version comes from -PreleaseVersion=1.2.3 (the publish workflow passes the git tag); otherwise a snapshot.
val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.2.0-SNAPSHOT").get()

allprojects {
    group = "io.cellophane"
    version = releaseVersion
}
