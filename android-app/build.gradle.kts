import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

val backendOrigin = providers.gradleProperty("habitTrackerOrigin").orElse("").get().trim().trimEnd('/')
if (backendOrigin.isNotEmpty()) {
    val uri = URI(backendOrigin)
    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.path.isNullOrEmpty() &&
        uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)) {
        "habitTrackerOrigin must be an HTTPS origin without a path or credentials"
    }
}

val releaseSigning = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith {
    providers.environmentVariable("HABIT_TRACKER_SIGNING_$it").orNull?.takeIf(String::isNotBlank)
}
val hasReleaseSigning = releaseSigning.values.any { it != null }
if (hasReleaseSigning) {
    require(releaseSigning.values.all { it != null }) { "Set all four HABIT_TRACKER_SIGNING_* variables for release signing" }
    require(backendOrigin.isNotEmpty()) { "Set -PhabitTrackerOrigin to the release HTTPS backend before signing" }
}

android {
    namespace = "dev.habittracker.android"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "dev.habittracker.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.5.0-preview.6"
        buildConfigField("String", "BACKEND_ORIGIN", "\"$backendOrigin\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (hasReleaseSigning) {
        val releaseKey = signingConfigs.create("distribution") {
            storeFile = rootProject.file(releaseSigning.getValue("STORE_FILE")!!)
            storePassword = releaseSigning.getValue("STORE_PASSWORD")
            keyAlias = releaseSigning.getValue("KEY_ALIAS")
            keyPassword = releaseSigning.getValue("KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = releaseKey
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":shared"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation(kotlin("test-junit"))
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // The installed API 37 emulator needs the updated InputManagerGlobal injector.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.8.0-alpha04")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
