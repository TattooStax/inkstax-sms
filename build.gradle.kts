plugins {
    alias(libs.plugins.android).apply(false)
    // SMS-1-TOUCHPOINT 5/5: pure-Kotlin module plugin for :transport (see transport/README.md)
    alias(libs.plugins.kotlinJvm).apply(false)
    alias(libs.plugins.kotlinSerialization).apply(false)
    alias(libs.plugins.ksp).apply(false)
    alias(libs.plugins.detekt).apply(false)
}

tasks.register<Delete>("clean") {
    delete {
        rootProject.buildDir
    }
}
