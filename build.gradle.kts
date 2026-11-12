plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.android.application") version "9.2.1" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}

// JDK 21 on Windows decodes Java launcher @argfiles in the native code page.
// Keep compiled classes on an ASCII path when the checkout contains Cyrillic.
val windowsUnicodePath = System.getProperty("os.name").startsWith("Windows") && rootDir.path.any { it.code > 127 }
if (windowsUnicodePath) {
    val checkoutId = java.security.MessageDigest.getInstance("SHA-256")
        .digest(rootDir.absolutePath.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    val temporaryBuildRoot = java.io.File(System.getProperty("java.io.tmpdir"), "habit-tracker-$checkoutId")
    subprojects { layout.buildDirectory.set(temporaryBuildRoot.resolve(name)) }
}
