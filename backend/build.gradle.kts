plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
kotlin { jvmToolchain(21) }
application { mainClass.set("dev.habittracker.backend.MainKt") }
dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-netty:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-server-status-pages:3.5.2")
    implementation("io.ktor:ktor-server-body-limit:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.16.0")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("org.flywaydb:flyway-core:11.10.5")
    implementation("org.flywaydb:flyway-database-postgresql:11.10.5")
    implementation("org.slf4j:slf4j-simple:2.0.20")
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.6.0"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")
}
tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("runLocal") {
    description = "Run the backend with a persistent loopback PostgreSQL for local development (no Docker)."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.habittracker.backend.LocalDevServerKt")
    workingDir(rootProject.projectDir)
}
