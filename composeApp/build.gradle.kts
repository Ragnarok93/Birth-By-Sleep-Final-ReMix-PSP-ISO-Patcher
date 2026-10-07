plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val androidKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val androidKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val androidKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val androidKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasCiSigning = listOf(
    androidKeystorePath,
    androidKeystorePassword,
    androidKeyAlias,
    androidKeyPassword,
).all { !it.isNullOrBlank() }

kotlin {
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.oneui.compose)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

compose.resources {
    packageOfResClass = "com.ragnarok93.bbsremix.resources"
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

android {
    namespace = "com.ragnarok93.bbsremix"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ragnarok93.bbsremix"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (hasCiSigning) {
        signingConfigs {
            create("ciRelease") {
                storeFile = file(androidKeystorePath!!)
                storePassword = androidKeystorePassword
                keyAlias = androidKeyAlias
                keyPassword = androidKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasCiSigning) signingConfig = signingConfigs.getByName("ciRelease")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

val hostTargetFormat = run {
    val osName = System.getProperty("os.name").lowercase()
    when {
        osName.contains("win") -> org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
        osName.contains("mac") -> org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg
        else -> org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage
    }
}
compose.desktop {
    application {
        mainClass = "com.ragnarok93.bbsremix.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                hostTargetFormat,
            )
            packageName = "Birth By Sleep - Final ReMix PSP ISO Patcher"
            packageVersion = "1.0.0"
            description = "Direct ISO patcher for Birth By Sleep - Final ReMix PSP images."
        }
    }
}
