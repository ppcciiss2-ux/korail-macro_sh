plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One committed keystore signs EVERY build (debug and release), on any machine, so
// the APK signature never changes — that's what lets a new APK install *over* an old
// one as an update and keep its data (코레일 로그인 정보 / 텔레그램 토큰). It is a
// personal-use signing key with no security value beyond claiming this app id.
val committedKeystore = rootProject.file("signing/korail-macro.jks")
val committedKeystorePassword = "korailmacro"
val committedKeystoreAlias = "korailmacro"

android {
    namespace = "com.korailmacro.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.korailmacro.app"
        minSdk = 26
        targetSdk = 34
        // Bump versionCode on every release you hand out. Android only allows an
        // in-place update (which keeps the app's data — 코레일 로그인 정보, 텔레그램
        // 토큰이 저장된 EncryptedSharedPreferences) when the new APK is signed with
        // the SAME key and has a HIGHER versionCode than the installed one.
        versionCode = 8
        versionName = "1.5-test3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("shared") {
            storeFile = committedKeystore
            storePassword = committedKeystorePassword
            keyAlias = committedKeystoreAlias
            keyPassword = committedKeystorePassword
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            resValue("string", "app_name", "코레일 매크로 테스트")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testBuildType = "preview"
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
