import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "im.molan.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "im.molan.music"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    val localSigning = Properties().apply {
        val signingFile = rootProject.file("signing.properties")
        if (signingFile.isFile) signingFile.inputStream().use { input -> load(input) }
    }
    fun signingProperty(name: String): String? = providers.gradleProperty(name).orNull ?: localSigning.getProperty(name)
    val releaseSigningConfig = signingConfigs.maybeCreate("release")
    val legacyKeystorePath = signingProperty("qingyin.legacy.keystore")
    if (!legacyKeystorePath.isNullOrBlank() && file(legacyKeystorePath).isFile) {
        listOf(signingConfigs.getByName("debug"), releaseSigningConfig).forEach { config ->
            config.storeFile = file(legacyKeystorePath)
            config.storePassword = signingProperty("qingyin.legacy.keystore.password") ?: ""
            config.keyAlias = signingProperty("qingyin.legacy.keystore.alias") ?: "qingyin"
            config.keyPassword = signingProperty("qingyin.legacy.key.password") ?: ""
            config.storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            signingConfig = releaseSigningConfig
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
