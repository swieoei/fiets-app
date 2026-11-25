plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.swieoei.fiets"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.swieoei.fiets"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        // Eén build voor eigen gebruik: ondertekend met de debug-sleutel van deze Mac, WebView-debugging aan.
        getByName("debug") {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
