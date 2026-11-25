plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    explicitApi()
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    sourceSets {
        all {
            languageSettings.optIn("kotlin.js.ExperimentalJsExport")
            languageSettings.optIn("kotlin.js.ExperimentalJsStatic")
            languageSettings.optIn("kotlin.js.ExperimentalJsCollectionsApi")
        }
    }
}
