// Standalone example: bind to Cellophane with cloudhopper, send a message, print the receipt.
// Run with:  gradle run   (from this folder; set CELLOPHANE_SMPP_PORT if not 2775)
plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("com.fizzed:ch-smpp:5.0.9")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.19")
}

application {
    mainClass = "example.SendOtp"
}
