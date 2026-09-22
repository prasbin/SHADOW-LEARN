// Top-level build file. Stable versions pinned per docs/SETUP.md.
// AGP 8.13.2 + Gradle 8.13 + Kotlin 2.1.20 + KSP 2.1.20-2.0.1.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.devtools.ksp") version "2.1.20-2.0.1" apply false
}
