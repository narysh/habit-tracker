plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("dev.habittracker.probe.MainKt") }
dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.16.0")
    implementation("io.ktor:ktor-server-netty:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-server-body-limit:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("org.slf4j:slf4j-simple:2.0.20")
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")
}
tasks.test { useJUnitPlatform() }

tasks.register("printDistributionPath") {
    doLast { println("PROBE_DIST=" + layout.buildDirectory.dir("install/mcp-probe").get().asFile.absolutePath) }
}
