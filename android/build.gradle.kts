plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Google services Gradle plugin, as given in the Firebase console for project Mabala
    id("com.google.gms.google-services") version "4.6.0" apply false
    // Screenshot tests of every screen on the JVM (no emulator needed)
    id("io.github.takahirom.roborazzi") version "1.79.0" apply false
}
