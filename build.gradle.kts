// Top-level build file
// AGP 9.x has built-in Kotlin support; we pin a KGP >= 2.4.0 on the classpath
// because litertlm-android 0.17.0 is compiled with Kotlin 2.4.0 metadata.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
}
